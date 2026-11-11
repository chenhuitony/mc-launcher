package com.mc;

import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Paper 服务端解析器。
 *
 * 使用 PaperMC 官方 v3 API：
 *   GET https://fill.papermc.io/v3/projects/paper
 *       -> versions: { "26.3": [...], "1.21": [...] }
 *   GET .../versions/<版本>/builds/latest
 *       -> downloads["server:default"].url 即服务端 jar
 */
public class PaperFetcher {

    public static final String API_BASE = "https://fill.papermc.io/v3/projects/paper";

    public static class Result {
        public final String version;
        public final String build;
        public final URL url;
        public final long size;
        public final String sha256;
        public Result(String version, String build, URL url, long size, String sha256) {
            this.version = version;
            this.build = build;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public static Result resolve(String versionId) throws Exception {
        String version = (versionId == null || versionId.isEmpty())
                ? latestMajorVersion()
                : versionId;
        Messages.log("Paper 目标版本：" + version);

        String url = API_BASE + "/versions/" + version + "/builds/latest";
        Messages.log("查询最新构建：" + url);
        Map<String, Object> build = Json.obj(Json.parse(get(url)));

        String buildId = Json.str(build, "id");
        String channel = Json.str(build, "channel");
        String downloadUrl = Json.str(build, "downloads", "server:default", "url");
        String name = Json.str(build, "downloads", "server:default", "name");
        long size = Json.lng(build, "downloads", "server:default", "size");
        String sha256 = Json.str(build, "downloads", "server:default", "checksums", "sha256");

        if (downloadUrl == null) {
            throw new RuntimeException("未找到 Paper " + version + " 的服务端下载项");
        }
        Messages.ok("构建 #" + buildId + "（" + channel + "）：" + name);
        return new Result(version, buildId, new URL(downloadUrl), size, sha256);
    }

    /** 取一个可用的最新正式版本号。 */
    private static String latestMajorVersion() throws Exception {
        Map<String, Object> proj = Json.obj(Json.parse(get(API_BASE)));
        Map<String, Object> versions = Json.obj(proj.get("versions"));
        // versions 按 "26.3"、"26.2" ... 分组，值是该组内所有版本；
        // 取第一个分组的第一个值作为最新。
        for (Map.Entry<String, Object> e : versions.entrySet()) {
            List<Object> group = Json.arr(e.getValue());
            if (!group.isEmpty()) {
                for (Object o : group) {
                    String v = String.valueOf(o);
                    if (!v.contains("-")) return v;   // 跳过 rc / pre / snapshot
                }
            }
        }
        throw new RuntimeException("无法从 Paper API 读取可用版本");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Minecraft-Launcher/1.0 (paper)");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code + " " + url);
        try (java.io.InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}