package com.mc;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pu 插件 API 执行器。
 *
 * <p>遵循「先检查、后调用」：
 * <ol>
 *   <li>检查阶段：校验每个 API 的字段完整性、函数 ID 唯一性、
 *       依赖是否都能解析、类型是否被支持、必填参数是否齐全；</li>
 *   <li>执行阶段：只有全部检查通过的 API 才会执行；
 *       每个 API 内部按依赖拓扑排序依次调用函数。</li>
 * </ol>
 *
 * <p>任一 API 检查失败时，该 API 整体跳过，不影响其它 API。
 */
public class ApiExecutor {

    /** 一个支持的功能类型。 */
    private static final Set<String> TYPES = new LinkedHashSet<>(java.util.Arrays.asList(
            "command", "http", "script", "download", "java", "log"));

    private final File serverDir;
    private final List<String> pendingCommands = new ArrayList<>();

    public ApiExecutor(File serverDir) {
        this.serverDir = serverDir;
    }

    /** 检查结果。 */
    public static class Check {
        public final PluginApi api;
        public final List<String> errors = new ArrayList<>();
        public final List<PluginApi.ApiFunction> order = new ArrayList<>();
        Check(PluginApi api) { this.api = api; }
        public boolean ok() { return errors.isEmpty(); }
    }

    /**
     * 对一组 API 全部执行「检查 + 调用」。
     *
     * @param bundles 由 ApiLoader 加载的结果
     */
    public void runAll(Map<String, ApiLoader.AuthorBundle> bundles) {
        if (bundles.isEmpty()) {
            Messages.log("Pu 目录没有任何可用的插件 API");
            return;
        }

        int checked = 0, executed = 0, skipped = 0;
        for (ApiLoader.AuthorBundle bundle : bundles.values()) {
            Messages.log("作者的 API 组：" + bundle.author
                    + "（" + bundle.apis.size() + " 个 API）");
            for (PluginApi api : bundle.apis) {
                checked++;
                Check chk = check(api);
                if (!chk.ok()) {
                    Messages.err("API " + api.id() + " 检查未通过，已跳过：");
                    for (String e : chk.errors) Messages.err("  - " + e);
                    skipped++;
                    continue;
                }
                if (!api.enabled) {
                    Messages.warn("API " + api.id() + " 已禁用（enabled: false），跳过");
                    skipped++;
                    continue;
                }
                Messages.ok("API " + api.id() + " 检查通过（" + api.functions.size() + " 个功能）");
                try {
                    execute(api, chk.order);
                    executed++;
                } catch (Exception e) {
                    Messages.err("API " + api.id() + " 执行中断：" + e.getMessage());
                }
            }
        }
        Messages.log("Pu API 汇总：检查 " + checked + " 个，执行 " + executed
                + " 个，跳过 " + skipped + " 个");
    }

    /**
     * 检查阶段：字段、类型、依赖、参数、拓扑序。
     * 只有完全通过才返回 ok()，并自带可执行的顺序。
     */
    public Check check(PluginApi api) {
        Check c = new Check(api);

        if (api.author == null || api.author.isEmpty()) c.errors.add("缺少 author");
        if (api.api == null || api.api.isEmpty()) c.errors.add("缺少 api 名称");
        if (api.functions.isEmpty()) c.errors.add("没有任何 functions");

        // 函数 ID 唯一性
        Set<String> ids = new LinkedHashSet<>();
        for (PluginApi.ApiFunction f : api.functions) {
            if (f.id == null || f.id.isEmpty()) {
                c.errors.add("存在没有 id 的功能");
                continue;
            }
            if (!ids.add(f.id)) {
                c.errors.add("功能 id 重复：" + f.id);
            }
        }

        // 类型与参数
        for (PluginApi.ApiFunction f : api.functions) {
            if (f.type == null || !TYPES.contains(f.type)) {
                c.errors.add("功能 " + f.id + " 的类型无效：" + f.type
                        + "（可用：" + TYPES + "）");
                continue;
            }
            switch (f.type) {
                case "command":
                    if (isBlank(f.run)) c.errors.add("功能 " + f.id + " 缺少 run（要执行的指令）");
                    break;
                case "http":
                    if (isBlank(f.url)) c.errors.add("功能 " + f.id + " 缺少 url");
                    break;
                case "script":
                    if (isBlank(f.run)) c.errors.add("功能 " + f.id + " 缺少 run（程序或脚本路径）");
                    break;
                case "download":
                    if (isBlank(f.url)) c.errors.add("功能 " + f.id + " 缺少 url");
                    if (isBlank(f.to)) c.errors.add("功能 " + f.id + " 缺少 to（保存路径）");
                    break;
                case "java":
                    if (isBlank(f.className)) c.errors.add("功能 " + f.id + " 缺少 class");
                    if (isBlank(f.javaMethod)) c.errors.add("功能 " + f.id + " 缺少 methodName");
                    break;
                case "log":
                    if (isBlank(f.message)) c.errors.add("功能 " + f.id + " 缺少 message");
                    break;
                default:
                    break;
            }
        }

        // 依赖必须指向本 API 内存在的功能
        for (PluginApi.ApiFunction f : api.functions) {
            for (String dep : f.requires) {
                if (!ids.contains(dep)) {
                    c.errors.add("功能 " + f.id + " 依赖了不存在的功能：" + dep);
                }
            }
        }

        if (c.ok()) {
            c.order.addAll(topologicalSort(api, c));
        }
        return c;
    }

    /** 按依赖排出执行顺序；检测到环则报错。 */
    private List<PluginApi.ApiFunction> topologicalSort(PluginApi api, Check c) {
        Map<String, PluginApi.ApiFunction> byId = new java.util.LinkedHashMap<>();
        for (PluginApi.ApiFunction f : api.functions) byId.put(f.id, f);

        List<PluginApi.ApiFunction> order = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();

        for (PluginApi.ApiFunction f : api.functions) {
            visit(f, byId, done, visiting, order, c);
        }
        return order;
    }

    private void visit(PluginApi.ApiFunction f,
                       Map<String, PluginApi.ApiFunction> byId,
                       Set<String> done, Set<String> visiting,
                       List<PluginApi.ApiFunction> order, Check c) {
        if (done.contains(f.id)) return;
        if (!visiting.add(f.id)) {
            c.errors.add("功能依赖存在循环：" + f.id);
            return;
        }
        for (String dep : f.requires) {
            PluginApi.ApiFunction d = byId.get(dep);
            if (d != null) visit(d, byId, done, visiting, order, c);
        }
        visiting.remove(f.id);
        if (!done.contains(f.id)) {
            done.add(f.id);
            order.add(f);
        }
    }

    /** 执行阶段：按检查算出的顺序依次调用。 */
    private void execute(PluginApi api, List<PluginApi.ApiFunction> order) {
        for (PluginApi.ApiFunction f : order) {
            Messages.log("  执行功能 " + api.id() + "." + f.id + " [" + f.type + "]");
            try {
                dispatch(api, f);
            } catch (Exception e) {
                Messages.err("  功能 " + f.id + " 失败：" + e.getMessage());
                // 单个功能失败不中断整个 API，继续下一个
            }
        }
    }

    private void dispatch(PluginApi api, PluginApi.ApiFunction f) throws Exception {
        switch (f.type) {
            case "log":
                Messages.raw("[Pu:" + api.author + "/" + api.api + "] " + f.message);
                break;

            case "command":
                // 服务端还没启动时先缓存，启动后再发
                Messages.ok("  指令已排入队列：" + f.run);
                pendingCommands.add(f.run);
                break;

            case "http":
                doHttp(f);
                break;

            case "script":
                doScript(f);
                break;

            case "download":
                doDownload(f);
                break;

            case "java":
                doJava(f);
                break;

            default:
                throw new RuntimeException("未知类型 " + f.type);
        }
    }

    private void doHttp(PluginApi.ApiFunction f) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(f.url).openConnection();
        conn.setRequestMethod(f.method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Minecraft-Launcher/1.0 (Pu-API)");
        if (f.body != null && !f.body.isEmpty()
                && ("POST".equals(f.method) || "PUT".equals(f.method) || "PATCH".equals(f.method))) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(f.body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        if (code != f.expect) {
            throw new RuntimeException("HTTP 返回 " + code + "，期望 " + f.expect);
        }
        Messages.ok("  HTTP " + f.method + " " + f.url + " -> " + code);
    }

    private void doScript(PluginApi.ApiFunction f) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        // 允许写完整命令行；先按空格拆，若带引号则整体走 shell
        if (f.run.contains(" ")) {
            pb.command("cmd.exe", "/c", f.run);
        } else {
            pb.command(f.run);
        }
        pb.directory(f.cwd != null && !f.cwd.isEmpty()
                ? resolve(f.cwd) : serverDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                Messages.raw("    [脚本] " + line);
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("脚本退出码 " + code);
        }
        Messages.ok("  脚本执行成功");
    }

    private void doDownload(PluginApi.ApiFunction f) throws Exception {
        File dest = resolve(f.to);
        dest.getParentFile().mkdirs();
        Messages.log("  下载 " + f.url + " -> " + dest.getName());
        new DownloadTask(new java.net.URL(f.url), dest, 0, -1).execute();
        Messages.ok("  已保存：" + dest.getAbsolutePath());
    }

    /**
     * 反射调用静态方法 —— 自由度最高的功能。
     * args 会按目标方法签名自动转换类型。
     */
    private void doJava(PluginApi.ApiFunction f) throws Exception {
        Class<?> cls = Class.forName(f.className);
        java.lang.reflect.Method target = null;
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (!m.getName().equals(f.javaMethod)) continue;
            if (m.getParameterCount() == f.args.size()) { target = m; break; }
        }
        if (target == null) {
            throw new RuntimeException("找不到方法 " + f.className + "."
                    + f.javaMethod + "(" + f.args.size() + " 个参数)");
        }

        Class<?>[] types = target.getParameterTypes();
        Object[] params = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            params[i] = convert(f.args.get(i), types[i]);
        }
        Object ret = target.invoke(null, params);
        if (ret != null) {
            Messages.ok("  调用返回：" + ret);
        } else {
            Messages.ok("  调用完成");
        }
    }

    private static Object convert(String raw, Class<?> type) {
        if (type == String.class) return raw;
        if (type == int.class || type == Integer.class) return Integer.parseInt(raw);
        if (type == long.class || type == Long.class) return Long.parseLong(raw);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(raw);
        if (type == double.class || type == Double.class) return Double.parseDouble(raw);
        if (type == float.class || type == Float.class) return Float.parseFloat(raw);
        return raw;
    }

    private File resolve(String path) {
        File f = new File(path);
        return f.isAbsolute() ? f : new File(serverDir, path);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 取出排队的服务端指令（启动后由 Launcher 发送）。 */
    public List<String> getPendingCommands() {
        return pendingCommands;
    }
}