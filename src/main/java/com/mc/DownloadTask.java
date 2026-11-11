package com.mc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * 单线程 HTTP 下载器，支持断点续传 + 边下边存续传点。
 * 只用 JDK 内置的 HttpURLConnection，无第三方依赖。
 */
public class DownloadTask {

    private final URL url;
    private final File dest;
    private final File lock;
    private final long startOffset;
    private final long expectedSize;

    public DownloadTask(URL url, File dest, long startOffset, long expectedSize) {
        this.url = url;
        this.dest = dest;
        this.lock = new File(dest.getParentFile(), ".downloading");
        this.startOffset = startOffset;
        this.expectedSize = expectedSize;
    }

    public void execute() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Minecraft-Launcher/1.0");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);

        if (startOffset > 0) {
            conn.setRequestProperty("Range", "bytes=" + startOffset + "-");
        }

        int code = conn.getResponseCode();
        boolean partial = (code == 206);
        if (code != 200 && !partial) {
            throw new RuntimeException("HTTP " + code + " " + conn.getResponseMessage());
        }

        long total = expectedSize > 0 ? expectedSize : -1;
        if (total < 0) {
            Map<String, List<String>> headers = conn.getHeaderFields();
            if (headers != null) {
                List<String> cl = headers.get("Content-Length");
                if (cl != null && !cl.isEmpty()) {
                    long contentLen = Long.parseLong(cl.get(0));
                    total = startOffset + contentLen;
                }
            }
        }

        // 服务器不支持 Range 时从头下
        boolean resume = partial && startOffset > 0;
        if (startOffset > 0 && !resume) {
            Messages.warn("服务器不支持断点续传，将从头开始下载");
        }

        try (InputStream in = conn.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {

            if (resume) {
                raf.seek(startOffset);
            } else {
                raf.setLength(0);
                raf.seek(0);
            }

            byte[] buf = new byte[64 * 1024];
            long downloaded = resume ? startOffset : 0;
            int n;
            long lastUi = 0;
            long lastLock = 0;

            while ((n = in.read(buf)) != -1) {
                raf.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 200) {
                    int pct = total > 0 ? (int) (downloaded * 100 / total) : 0;
                    Messages.progress(pct, downloaded, total);
                    lastUi = now;
                }
                if (now - lastLock > 3000) {
                    writeLock(downloaded);
                    lastLock = now;
                }
            }
        }
        Messages.progressDone();
        writeLock(dest.length());
    }

    private void writeLock(long bytes) {
        try (FileOutputStream fos = new FileOutputStream(lock)) {
            fos.write(String.valueOf(bytes).getBytes());
        } catch (Exception ignore) { /* 续传点失败不影响主流程 */ }
    }
}