package com.mc;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 插件管理器（多来源）。
 *
 * <p>支持三个插件平台，用前缀指定；不写前缀则自动探测：
 * <pre>
 *   luckperms                    自动：Modrinth → Hangar → Spiget
 *   modrinth:luckperms           强制 Modrinth（slug）
 *   hangar:William278/HuskHomes  强制 Hangar（作者/项目）
 *   spiget:34315                 强制 Spiget（资源 ID）
 *   luckperms@5.5.71             锁定版本（仅 Modrinth 支持精确版本号）
 * </pre>
 *
 * <p>配置写在 servers/plugins.txt，每行一个。
 */
public class PluginManager {

    /** 自动探测顺序：Modrinth 覆盖最广，优先。 */
    private static final List<PluginSource> AUTO_ORDER = Arrays.asList(
            new ModrinthSource(), new HangarSource(), new SpigetSource());

    public static class Plugin {
        public final String id;
        public final String version;
        public final URL url;
        public final String fileName;
        public final long size;
        public Plugin(String id, String version, URL url, String fileName, long size) {
            this.id = id;
            this.version = version;
            this.url = url;
            this.fileName = fileName;
            this.size = size;
        }
    }

    public static void installAll(File serverDir, String gameVersion, boolean offline) throws Exception {
        File listFile = new File(serverDir, "plugins.txt");
        File pluginDir = new File(serverDir, "plugins");
        pluginDir.mkdirs();

        if (!listFile.exists()) {
            writeTemplate(listFile);
            Messages.warn("未找到 plugins.txt，已生成模板：" + listFile.getAbsolutePath());
            Messages.warn("在其中按行填写插件后重新启动即可自动下载");
            return;
        }

        List<String> wanted = readList(listFile);
        if (wanted.isEmpty()) {
            Messages.log("plugins.txt 中没有启用任何插件");
            return;
        }

        Messages.log("检测到 " + wanted.size() + " 个待安装插件（游戏版本 " + gameVersion + "）");
        int ok = 0, failed = 0;
        for (String spec : wanted) {
            try {
                if (installOne(spec, serverDir, pluginDir, gameVersion, offline)) ok++;
                else ok++;   // 已存在也算成功
            } catch (Exception e) {
                Messages.err("插件 " + spec + " 安装失败：" + e.getMessage());
                failed++;
            }
        }
        Messages.log("插件处理完成：成功 " + ok + " 个，失败 " + failed + " 个");
    }

    private static boolean installOne(String spec, File serverDir, File pluginDir,
                                      String gameVersion, boolean offline) throws Exception {
        String body = spec;
        String pinned = null;
        int at = body.indexOf('@');
        if (at > 0) {
            pinned = body.substring(at + 1);
            body = body.substring(0, at);
        }

        PluginSource source = null;
        String id = body;
        int colon = body.indexOf(':');
        if (colon > 0) {
            String prefix = body.substring(0, colon);
            id = body.substring(colon + 1);
            source = byName(prefix);
            if (source == null) {
                throw new RuntimeException("未知的插件来源前缀 \"" + prefix
                        + "\"，可用：modrinth / hangar / spiget");
            }
        }

        Plugin p;
        if (source != null) {
            p = resolveWith(source, id, pinned, gameVersion);
        } else {
            p = resolveAuto(id, pinned, gameVersion);
        }

        File dest = new File(pluginDir, p.fileName);
        if (dest.exists() && dest.length() > 0 && (p.size <= 0 || dest.length() == p.size)) {
            Messages.ok("插件已存在，跳过：" + p.fileName);
            return false;
        }
        if (offline) {
            throw new RuntimeException("离线模式无法下载");
        }
        Messages.log("下载插件 " + p.id + " " + p.version);
        new DownloadTask(p.url, dest, 0, p.size).execute();
        Messages.ok("已安装：" + p.fileName);
        return true;
    }

    /**
     * 按顺序尝试各来源，直到某个来源成功解析。
     * 只有 accepts() 为真的来源才尝试，避免把 34315 当成 Modrinth slug 去查。
     */
    private static Plugin resolveAuto(String id, String pinned, String gameVersion) throws Exception {
        List<String> errors = new ArrayList<>();
        for (PluginSource s : AUTO_ORDER) {
            if (!s.accepts(id)) continue;
            try {
                Plugin p = resolveWith(s, id, pinned, gameVersion);
                Messages.log("已在 " + s.name() + " 找到插件 " + id);
                return p;
            } catch (Exception e) {
                errors.add(s.name() + ": " + e.getMessage());
            }
        }
        throw new RuntimeException("所有插件源均未找到 \"" + id + "\"（" + String.join("；", errors) + "）");
    }

    private static Plugin resolveWith(PluginSource source, String id, String pinned,
                                      String gameVersion) throws Exception {
        if (pinned != null && source instanceof ModrinthSource) {
            return resolveModrinthPinned(id, pinned, gameVersion);
        }
        if (pinned != null) {
            Messages.warn(source.name() + " 不支持 @版本 锁定，将安装最新适配版本");
        }
        return source.resolve(id, gameVersion);
    }

    /** Modrinth 支持按版本号精确查询。 */
    private static Plugin resolveModrinthPinned(String slug, String versionNumber,
                                                String gameVersion) throws Exception {
        String url = "https://api.modrinth.com/v2/project/" + slug + "/version";
        List<Object> list = Json.arr(Json.parse(get(url)));
        for (Object o : list) {
            java.util.Map<String, Object> v = Json.obj(o);
            if (!versionNumber.equals(Json.str(v, "version_number"))) continue;
            List<Object> files = Json.arr(v.get("files"));
            if (files == null || files.isEmpty()) {
                throw new RuntimeException("该版本没有可下载文件");
            }
            java.util.Map<String, Object> f = null;
            for (Object fo : files) {
                java.util.Map<String, Object> fm = Json.obj(fo);
                if (Boolean.TRUE.equals(fm.get("primary"))) { f = fm; break; }
            }
            if (f == null) f = Json.obj(files.get(0));
            return new Plugin(slug, versionNumber,
                    PluginSource.url(Json.str(f, "url")),
                    Json.str(f, "filename"),
                    Json.lng(f, "size"));
        }
        throw new RuntimeException("Modrinth 上找不到版本 " + versionNumber);
    }

    private static PluginSource byName(String prefix) {
        switch (prefix.toLowerCase()) {
            case "modrinth": return new ModrinthSource();
            case "hangar":   return new HangarSource();
            case "spiget":   return new SpigetSource();
            default:         return null;
        }
    }

    private static List<String> readList(File f) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            out.add(s);
        }
        return out;
    }

    private static void writeTemplate(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Paper 插件列表（每行一个）\n");
        sb.append("#\n");
        sb.append("# 三个插件来源，用前缀指定；不写前缀则自动探测\n");
        sb.append("#   luckperms                    -> 自动（Modrinth -> Hangar -> Spiget）\n");
        sb.append("#   modrinth:luckperms           -> 强制 Modrinth（插件 slug）\n");
        sb.append("#   hangar:作者/项目              -> 强制 Hangar，例如 hangar:William278/HuskHomes\n");
        sb.append("#   spiget:资源ID                 -> 强制 Spiget，例如 spiget:34315\n");
        sb.append("#   luckperms@5.5.71             -> 锁定版本（仅 Modrinth 生效）\n");
        sb.append("#\n");
        sb.append("# 常用插件示例（取消注释即可启用）：\n");
        sb.append("#\n");
        sb.append("# luckperms                      权限管理（Modrinth）\n");
        sb.append("# essentialsx                    基础指令套件（Modrinth）\n");
        sb.append("# worldedit                      创世神（Modrinth）\n");
        sb.append("# worldguard                     区域保护（Modrinth）\n");
        sb.append("# viaversion                     跨版本兼容（Modrinth）\n");
        sb.append("# hangar:William278/HuskHomes     回家插件（Hangar 示例）\n");
        sb.append("# spiget:34315                   Vault 经济接口（Spiget 示例）\n");
        java.nio.file.Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String get(String url) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Minecraft-Launcher/1.0 (plugin-manager)");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code + " " + url);
        try (java.io.InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}