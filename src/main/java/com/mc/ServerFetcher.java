package com.mc;

import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 服务端版本清单解析器。
 *
 * Mojang 的下载不是一步到位，而是两层：
 *   1. version_manifest_v2.json  ->  latest.release 及各版本的描述文件 url
 *   2. <版本>.json               ->  downloads.server.url 才是真正的 server.jar
 * 所以必须两步请求。
 */
public class ServerFetcher {

    /** Mojang 官方版本清单（v2 版本，字段更全）。 */
    public static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public static class Result {
        public final String versionId;
        public final URL serverUrl;
        public final long size;
        public final String sha1;
        public Result(String versionId, URL serverUrl, long size, String sha1) {
            this.versionId = versionId;
            this.serverUrl = serverUrl;
            this.size = size;
            this.sha1 = sha1;
        }
    }

    /**
     * 解析出指定版本的服务端下载地址。
     * versionId 为 null/空 时取最新正式版（latest.release）。
     */
    public static Result resolve(String versionId) throws Exception {
        Messages.log("第 1 步：获取版本清单 " + MANIFEST_URL);
        Map<String, Object> manifest = Json.obj(Json.parse(readText(new URL(MANIFEST_URL))));

        String wanted = (versionId == null || versionId.isEmpty())
                ? Json.str(manifest, "latest", "release")
                : versionId;
        if (wanted == null) throw new RuntimeException("无法从清单中读取最新版本号");
        Messages.log("目标版本：" + wanted);

        List<Object> versions = Json.arr(manifest.get("versions"));
        String versionJsonUrl = null;
        for (Object o : versions) {
            Map<String, Object> v = Json.obj(o);
            if (wanted.equals(Json.str(v, "id"))) {
                versionJsonUrl = Json.str(v, "url");
                break;
            }
        }
        if (versionJsonUrl == null) {
            throw new RuntimeException("清单中未找到版本：" + wanted);
        }
        Messages.log("第 2 步：获取版本描述文件");
        Map<String, Object> ver = Json.obj(Json.parse(readText(new URL(versionJsonUrl))));

        String serverUrl = Json.str(ver, "downloads", "server", "url");
        long size = Json.lng(ver, "downloads", "server", "size");
        String sha1 = Json.str(ver, "downloads", "server", "sha1");
        if (serverUrl == null) {
            throw new RuntimeException("版本 " + wanted + " 没有服务端下载项（可能是旧版本，服务端需单独获取）");
        }

        Messages.ok("解析到服务端：" + serverUrl);
        if (size > 0) Messages.log("大小：" + String.format("%.1f MB", size / 1024.0 / 1024.0));
        if (sha1 != null) Messages.log("SHA1：" + sha1);

        return new Result(wanted, new URL(serverUrl), size, sha1);
    }

    private static String readText(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Minecraft-Launcher/1.0");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        int code = conn.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code + " " + url);
        try (java.io.InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}