package com.mc;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Modrinth 插件源（默认源，覆盖最广）。
 *
 * <p>API：
 * <pre>
 * GET https://api.modrinth.com/v2/project/{slug}/version
 *       ?loaders=["paper","bukkit","spigot","purpur"]&amp;game_versions=["1.21.8"]
 *   -> [].version_number
 *   -> [].files[].{url,filename,size,primary}
 * </pre>
 */
public class ModrinthSource implements PluginSource {

    private static final String API = "https://api.modrinth.com/v2/project/";

    @Override
    public String name() {
        return "Modrinth";
    }

    @Override
    public boolean accepts(String id) {
        // slug 只含字母数字和连字符/下划线；Hangar 格式带斜杠、Spiget 是纯数字
        return id.matches("[A-Za-z0-9_\\-]+");
    }

    @Override
    public PluginManager.Plugin resolve(String id, String gameVersion) throws Exception {
        String loaders = "[\"paper\",\"bukkit\",\"spigot\",\"purpur\"]";
        String gv = gameVersion == null ? "[]" : "[\"" + gameVersion + "\"]";
        String url = API + id + "/version?loaders=" + enc(loaders) + "&game_versions=" + enc(gv);

        List<Object> list = Json.arr(Json.parse(get(url)));
        if (list == null || list.isEmpty()) {
            throw new RuntimeException("Modrinth 上没有适配 " + gameVersion + " 的版本");
        }

        Map<String, Object> ver = Json.obj(list.get(0));
        String versionNumber = Json.str(ver, "version_number");
        List<Object> files = Json.arr(ver.get("files"));
        if (files == null || files.isEmpty()) {
            throw new RuntimeException("该版本没有可下载文件");
        }

        Map<String, Object> file = null;
        for (Object o : files) {
            Map<String, Object> f = Json.obj(o);
            if (Boolean.TRUE.equals(f.get("primary"))) { file = f; break; }
        }
        if (file == null) file = Json.obj(files.get(0));

        String fileName = Json.str(file, "filename");
        String fileUrl = Json.str(file, "url");
        long size = Json.lng(file, "size");
        if (fileUrl == null) throw new RuntimeException("文件地址缺失");

        return new PluginManager.Plugin(id, versionNumber, PluginSource.url(fileUrl), fileName, size);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Minecraft-Launcher/1.0 (modrinth)");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code + " " + url);
        try (java.io.InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}