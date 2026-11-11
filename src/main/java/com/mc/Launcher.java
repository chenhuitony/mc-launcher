package com.mc;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动器主入口。
 *
 * 两种服务端：
 *   默认（原版 Vanilla）：Mojang 官方 server.jar
 *   --paper           ：PaperMC 高性能服务端，支持插件
 *
 * 流程：
 *   1. 初始化中文输出
 *   2. 解析并下载服务端（SHA 校验）
 *   3. 写入 eula=true
 *   4. Paper 模式下安装 plugins.txt 中列出的插件
 *   5. 启动服务端，日志实时翻译成中文
 */
public class Launcher {

    private static final File BASE_DIR = new File("servers");

    public static void main(String[] args) {
        Messages.init();
        boolean paper = hasArg(args, "--paper");
        String versionId = arg(args, "--version");
        boolean offline = hasArg(args, "--offline");
        String memory = arg(args, "--memory");

        Messages.log("Minecraft 服务端启动器 v1.1 by chenhuitony —— "
                + (paper ? "Paper（支持插件）" : "原版"));

        File jar = new File(BASE_DIR, paper ? "paper.jar" : "server.jar");
        File eula = new File(BASE_DIR, "eula.txt");

        try {
            BASE_DIR.mkdirs();

            if (!jar.exists() || jar.length() <= 0) {
                if (offline) {
                    throw new RuntimeException(
                            "离线模式且本地无服务端文件，请手动放置：\n  " + jar.getAbsolutePath());
                }
                if (paper) {
                    downloadPaper(jar, versionId);
                } else {
                    downloadVanilla(jar, versionId);
                }
            } else {
                Messages.ok("本地已存在 " + jar.getName() + " (" + humanSize(jar.length()) + ")，跳过下载");
            }

            ensureEula(eula);

            String gameVersion = detectGameVersion(jar);
            if (paper) {
                PluginManager.installAll(BASE_DIR, gameVersion, offline);
            }

            // Pu 插件 API：先检查，再调用
            File puDir = new File(BASE_DIR, "Pu");
            ApiExecutor apiExec = new ApiExecutor(BASE_DIR);
            try {
                java.util.Map<String, ApiLoader.AuthorBundle> bundles = ApiLoader.loadAll(puDir);
                apiExec.runAll(bundles);
            } catch (Exception e) {
                Messages.err("加载 Pu 插件 API 失败：" + e.getMessage());
            }

            startServer(jar, memory == null ? "2G" : memory, apiExec);
        } catch (InterruptedException e) {
            Messages.err("用户中断");
        } catch (Exception e) {
            Messages.err("启动失败：" + e.getMessage());
            System.exit(1);
        }
    }

    private static void downloadVanilla(File jar, String versionId) throws Exception {
        Messages.log("本地未检测到 server.jar，开始下载原版服务端...");
        ServerFetcher.Result r = ServerFetcher.resolve(versionId);
        long start = resumeFrom(jar);
        new DownloadTask(r.serverUrl, jar, start, r.size).execute();
        if (r.sha1 != null) verifySha(jar, r.sha1, "SHA1");
        writeVersion(r.versionId);
        Messages.ok("下载完成：" + jar.getAbsolutePath() + " (" + humanSize(jar.length()) + ")");
    }

    private static void downloadPaper(File jar, String versionId) throws Exception {
        Messages.log("本地未检测到 paper.jar，开始下载 Paper 服务端...");
        PaperFetcher.Result r = PaperFetcher.resolve(versionId);
        long start = resumeFrom(jar);
        new DownloadTask(r.url, jar, start, r.size).execute();
        if (r.sha256 != null) verifySha(jar, r.sha256, "SHA256");
        writeVersion(r.version);
        Messages.ok("下载完成：" + jar.getAbsolutePath() + " (" + humanSize(jar.length()) + ")");
    }

    /** 把当前服务端对应的游戏版本记到小文件里，供插件筛选使用。 */
    private static void writeVersion(String version) {
        if (version == null) return;
        try {
            java.nio.file.Files.write(new File(BASE_DIR, ".game-version").toPath(),
                    version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignore) { }
    }

    private static long resumeFrom(File jar) {
        File lock = new File(jar.getParentFile(), ".downloading");
        if (!lock.exists()) return 0;
        try {
            long n = Long.parseLong(new String(
                    java.nio.file.Files.readAllBytes(lock.toPath())).trim());
            if (n > 0) Messages.log("检测到未完成下载，从 " + humanSize(n) + " 处续传");
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void verifySha(File f, String expected, String algo) throws Exception {
        Messages.log("正在校验 " + algo + "...");
        String actual = hash(f, algo.equals("SHA256") ? "SHA-256" : "SHA-1");
        if (!actual.equalsIgnoreCase(expected)) {
            throw new RuntimeException(algo + " 校验失败，文件可能损坏：\n  期望 " + expected + "\n  实际 " + actual);
        }
        Messages.ok(algo + " 校验通过");
    }

    private static String hash(File f, String algo) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance(algo);
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * 推断游戏版本，供插件筛选使用。依次尝试：
     *   1. .game-version 记录文件（下载时写入）
     *   2. versions/ 目录名（Paper 运行后生成）
     *   3. jar 文件名 paper-<版本>-<构建>.jar
     * 都拿不到就返回 null，此时插件只按 loader 过滤。
     */
    private static String detectGameVersion(File jar) {
        File rec = new File(BASE_DIR, ".game-version");
        if (rec.exists()) {
            try {
                String v = new String(java.nio.file.Files.readAllBytes(rec.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) {
                    Messages.log("识别到游戏版本：" + v);
                    return v;
                }
            } catch (Exception ignore) { }
        }

        File versionsDir = new File(BASE_DIR, "versions");
        if (versionsDir.isDirectory()) {
            File[] dirs = versionsDir.listFiles(File::isDirectory);
            if (dirs != null && dirs.length > 0) {
                String v = dirs[0].getName();
                Messages.log("识别到游戏版本：" + v);
                return v;
            }
        }

        String name = jar.getName();
        if (name.startsWith("paper-")) {
            String s = name.substring("paper-".length());
            int dash = s.lastIndexOf('-');
            if (dash > 0) {
                String v = s.substring(0, dash);
                Messages.log("识别到游戏版本：" + v);
                return v;
            }
        }
        Messages.warn("无法识别游戏版本，插件将按 loader 匹配最新版");
        return null;
    }

    private static void ensureEula(File eula) throws Exception {
        if (eula.exists()) {
            Messages.ok("eula.txt 已存在");
            return;
        }
        java.nio.file.Files.write(eula.toPath(),
                ("# 由启动器自动生成\n"
                        + "# 同意 Minecraft EULA：https://aka.ms/MinecraftEULA\n"
                        + "eula=true\n").getBytes("UTF-8"));
        Messages.ok("已生成 eula.txt（eula=true）");
    }

    /**
     * 确保 server.properties 启用了 RCON，Pu API 的 command 功能依赖它。
     * 只在文件里没有这些键时追加，不覆盖用户已有配置。
     */
    private static String[] ensureRcon(String rconPassword) throws Exception {
        File props = new File(BASE_DIR, "server.properties");
        String host = "127.0.0.1";
        int port = 25575;

        if (!props.exists()) {
            // 服务端首次启动会自己生成；此处先记下默认值，等它生成后再写
            return new String[]{host, String.valueOf(port), rconPassword};
        }

        List<String> lines = new java.util.ArrayList<>(
                java.nio.file.Files.readAllLines(props.toPath(), java.nio.charset.StandardCharsets.UTF_8));
        boolean hasEnable = false, hasPort = false, hasPass = false;
        boolean changed = false;

        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("enable-rcon=")) {
                hasEnable = true;
                String v = l.substring("enable-rcon=".length()).trim();
                if (!"true".equalsIgnoreCase(v)) {
                    lines.set(i, "enable-rcon=true");
                    changed = true;
                }
            } else if (l.startsWith("rcon.port=")) {
                hasPort = true;
                try { port = Integer.parseInt(l.substring("rcon.port=".length()).trim()); }
                catch (Exception ignore) { }
            } else if (l.startsWith("rcon.password=")) {
                hasPass = true;
                String p = l.substring("rcon.password=".length()).trim();
                if (!p.isEmpty()) {
                    rconPassword = p;        // 用户已有密码优先
                } else {
                    lines.set(i, "rcon.password=" + rconPassword);
                    changed = true;
                }
            }
        }

        if (!hasEnable) { lines.add("enable-rcon=true"); changed = true; }
        if (!hasPort)   { lines.add("rcon.port=" + port); changed = true; }
        if (!hasPass)   { lines.add("rcon.password=" + rconPassword); changed = true; }

        if (changed) {
            java.nio.file.Files.write(props.toPath(),
                    String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Messages.ok("已启用 RCON（端口 " + port + "），用于执行 Pu API 指令");
        }
        return new String[]{host, String.valueOf(port), rconPassword};
    }

    private static void startServer(File jar, String mem, ApiExecutor apiExec) throws Exception {
        Messages.log("正在启动服务端（内存 -Xmx" + mem + "）...");

        List<String> pending = apiExec == null
                ? new java.util.ArrayList<>() : apiExec.getPendingCommands();
        String[] rcon = null;
        if (!pending.isEmpty()) {
            rcon = ensureRcon("pu-" + Long.toHexString(System.nanoTime()));
            Messages.log("服务端启动后将通过 RCON 执行 " + pending.size() + " 条 Pu API 指令");
        }

        Messages.log("日志将实时翻译为中文，直接在控制台输入指令即可操作");

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xmx" + mem);
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        cmd.add("-jar");
        cmd.add(jar.getAbsolutePath());
        cmd.add("nogui");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(BASE_DIR);
        pb.redirectErrorStream(true);
        // stdin 必须继承真实控制台：JLine 读到管道输入会解析失败
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

        Process p = pb.start();

        final String[] rconRef = rcon;
        final List<String> pendingRef = pending;
        final boolean[] fired = {false};

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String zh = Translator.translate(line);
                if (zh != null) Messages.raw(zh);

                // 检测「启动完成」，此时才发指令
                if (!fired[0] && rconRef != null
                        && line.contains("Done (") && line.contains("For help")) {
                    fired[0] = true;
                    firePendingCommands(rconRef, pendingRef);
                }
            }
        }

        int code = p.waitFor();
        if (code != 0) {
            Messages.err("服务端以退出码 " + code + " 退出");
            System.exit(code);
        } else {
            Messages.ok("服务端正常关闭");
        }
    }

    /** 通过 RCON 依次执行排队的指令。 */
    private static void firePendingCommands(String[] rcon, List<String> commands) {
        // 服务端刚 Done 时 RCON 可能还没开始监听，重试几次
        RconClient client = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                client = new RconClient(rcon[0], Integer.parseInt(rcon[1]), rcon[2]);
                break;
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignore) { }
            }
        }
        if (client == null) {
            Messages.err("RCON 连接失败，Pu API 指令未能执行");
            return;
        }
        try (RconClient c = client) {
            for (String cmd : commands) {
                try {
                    String resp = c.command(cmd);
                    Messages.ok("  [RCON] " + cmd
                            + (resp == null || resp.isEmpty() ? "" : " -> " + resp.trim()));
                } catch (Exception e) {
                    Messages.err("  [RCON] " + cmd + " 失败：" + e.getMessage());
                }
            }
        }
    }

    private static String arg(String[] args, String key) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(key) && i + 1 < args.length) return args[i + 1];
        }
        return null;
    }

    private static boolean hasArg(String[] args, String key) {
        for (String a : args) if (a.equals(key)) return true;
        return false;
    }

    private static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1024.0 / 1024.0);
    }
}