package com.mc;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Hangar 插件源（PaperMC 官方插件平台）。
 *
 * <p>API：
 * <pre>
 * GET https://hangar.papermc.io/api/v1/projects/{作者}/{项目}/versions?limit=N
 *   -> result[].name                      版本号
 *   -> result[].platformDependencies.PAPER 支持的游戏版本列表
 *   -> result[].downloads.PAPER.downloadUrl
 *   -> result[].downloads.PAPER.fileInfo.{name,sizeBytes,sha256Hash}
 * </pre>
 *
 * <p>注意：部分项目在 Hangar 上没有直链，只有一个指向 Modrinth 等站点的
 * externalUrl。这种情况本类会跳过，交给下一个来源处理。
 *
 * <p>插件名格式为 "作者/项目"，如 William278/HuskHomes。
 */
public class HangarSource implements PluginSource {

    private static final String API = "https://hangar.papermc.io/api/v1/projects/";

    @Override
    public String name() {
        return "Hangar";
    }

    @Override
    public boolean accepts(String id) {
        return id.contains("/");
    }

    @Override
    public PluginManager.Plugin resolve(String id, String gameVersion) throws Exception {
        if (!id.contains("/")) {
            throw new RuntimeException("Hangar 插件名格式应为 作者/项目，例如 William278/HuskHomes");
        }

        // 多取几条，便于挑选适配当前游戏版本的构建
        // 响应顶层是 { pagination, result }，result 才是版本数组
        String url = API + id + "/versions?limit=20";
        java.util.Map<String, Object> page = Json.obj(Json.parse(get(url)));
        List<Object> versions = Json.arr(page.get("result"));
        if (versions == null || versions.isEmpty()) {
            throw new RuntimeException("Hangar 上找不到项目 " + id + " 的版本");
        }

        for (Object o : versions) {
            Map<String, Object> v = Json.obj(o);
            if (!supportsVersion(v, gameVersion)) continue;

            Map<String, Object> dl = pickDownload(v);
            if (dl == null) continue;   // 该版本没有直链，换下一个

            String fileName = Json.str(dl, "fileInfo", "name");
            String downloadUrl = Json.str(dl, "downloadUrl");
            long size = Json.lng(dl, "fileInfo", "sizeBytes");
            if (fileName == null || downloadUrl == null) continue;

            String versionName = Json.str(v, "name");
            return new PluginManager.Plugin(id, versionName, PluginSource.url(downloadUrl), fileName, size);
        }

        throw new RuntimeException("Hangar 上没有适配 " + gameVersion + " 且提供直链的版本");
    }

    /** 检查该版本的平台版本列表是否包含目标游戏版本。 */
    private boolean supportsVersion(Map<String, Object> v, String gameVersion) {
        Object pd = v.get("platformDependencies");
        if (pd == null) return false;
        for (String platform : new String[]{"PAPER", "WATERFALL", "VELOCITY"}) {
            Object listObj = Json.obj(pd).get(platform);
            if (listObj == null) continue;
            List<Object> versions = Json.arr(listObj);
            if (gameVersion == null) {
                return !versions.isEmpty();
            }
            for (Object x : versions) {
                if (gameVersion.equals(String.valueOf(x))) return true;
            }
        }
        return false;
    }

    /** 找出带内置下载地址的下载项（优先 PAPER）。 */
    private Map<String, Object> pickDownload(Map<String, Object> v) {
        Object downloads = v.get("downloads");
        if (downloads == null) return null;
        Map<String, Object> dm = Json.obj(downloads);

        // 优先 PAPER，其次任意有 downloadUrl 的平台
        Object paperObj = dm.get("PAPER");
        if (paperObj != null) {
            Map<String, Object> pd = Json.obj(paperObj);
            if (Json.str(pd, "downloadUrl") != null) return pd;
        }
        for (Object o : dm.values()) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> d = Json.obj(o);
            if (Json.str(d, "downloadUrl") != null) return d;
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Minecraft-Launcher/1.0 (hangar)");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code + " " + url);
        try (java.io.InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}