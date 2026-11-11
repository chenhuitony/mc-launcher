package com.mc;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Spiget 插件源（SpigotMC 资源站的第三方 API）。
 *
 * <p>API：
 * <pre>
 * GET https://api.spiget.org/v2/resources/{资源ID}
 *   -> name, testedVersions[], file.size, file.sizeUnit
 * GET https://api.spiget.org/v2/resources/{资源ID}/download
 *   -> 302 跳转，最终返回 jar 文件
 * </pre>
 *
 * <p>插件名直接写资源 ID（纯数字），如 34315（Vault）。
 * 资源 ID 就是 SpigotMC 页面地址中的数字：
 * https://www.spigotmc.org/resources/vault.34315/
 *
 * <p>Spiget 不提供 SHA 校验，也不提供精确的文件名，
 * 因此文件名用 "{资源名}-{ID}.jar" 生成。
 */
public class SpigetSource implements PluginSource {

    private static final String API = "https://api.spiget.org/v2/resources/";

    @Override
    public String name() {
        return "Spiget";
    }

    @Override
    public boolean accepts(String id) {
        return id.matches("\\d+");
    }

    @Override
    public PluginManager.Plugin resolve(String id, String gameVersion) throws Exception {
        if (!id.matches("\\d+")) {
            throw new RuntimeException("Spiget 插件名应为纯数字资源 ID，例如 34315");
        }

        Map<String, Object> res = Json.obj(Json.parse(get(API + id)));
        String resName = Json.str(res, "name");
        if (resName == null) {
            throw new RuntimeException("Spiget 上找不到资源 " + id);
        }

        // 若指定了游戏版本，检查是否为该资源声明支持的版本
        if (gameVersion != null) {
            Object tv = res.get("testedVersions");
            if (tv != null) {
                List<Object> versions = Json.arr(tv);
                boolean hit = false;
                for (Object v : versions) {
                    if (gameVersion.equals(String.valueOf(v))) { hit = true; break; }
                }
                if (!hit && !versions.isEmpty()) {
                    Messages.warn("资源 " + resName + " 声明支持的版本为 "
                            + versions + "，未包含 " + gameVersion + "，仍将尝试安装");
                }
            }
        }

        // 大小换算成字节
        long size = 0;
        Object fileObj = res.get("file");
        if (fileObj != null) {
            long raw = Json.lng(Json.obj(fileObj), "size");
            String unit = Json.str(Json.obj(fileObj), "sizeUnit");
            size = toBytes(raw, unit);
        }

        String safeName = resName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeName + "-" + id + ".jar";
        URL downloadUrl = new URL(API + id + "/download");

        return new PluginManager.Plugin(id, "资源 #" + id, downloadUrl, fileName, size);
    }

    private static long toBytes(long size, String unit) {
        if (size <= 0 || unit == null) return 0;
        switch (unit.toUpperCase()) {
            case "B":  return size;
            case "KB": return size * 1024L;
            case "MB": return size * 1024L * 1024L;
            case "GB": return size * 1024L * 1024L * 1024L;
            default:   return size * 1024L;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Minecraft-Launcher/1.0 (spiget)");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code + " " + url);
        try (java.io.InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}