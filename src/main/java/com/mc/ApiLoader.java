package com.mc;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pu 插件 API 加载器。
 *
 * <p>扫描 Pu/ 目录，读取每个 YAML 描述文件，构造 PluginApi 对象，
 * 并做完整性检查（作者名一致、函数 ID 不重复、依赖存在等）。
 *
 * <p>目录约定：
 * <pre>
 *   Pu/chenhuitony.yml                   -- 作者 chenhuitony 的默认 API
 *   Pu/chenhuitony/welcome-kit.yml       -- 同一作者的另一个 API
 *   Pu/chenhuitony/sub/dir/xxx.yml       -- 任意层级都支持
 * </pre>
 *
 * <p>核心规则：**API 的作者名必须与它在 Pu/ 下的第一层名字一致**。
 * 文件名或目录名就是作者名，文件内部再写一遍 author 做交叉校验，
 * 不一致就拒绝加载 —— 这样作者空间天然隔离，不会互相覆盖。
 */
public class ApiLoader {

    /** 一个作者拥有的所有 API。 */
    public static class AuthorBundle {
        public final String author;
        public final List<PluginApi> apis = new ArrayList<>();
        public AuthorBundle(String author) { this.author = author; }
    }

    /**
     * 加载全部 API。
     *
     * @param puDir Pu 目录
     * @return 按作者分组的 API
     */
    public static Map<String, AuthorBundle> loadAll(File puDir) {
        Map<String, AuthorBundle> result = new LinkedHashMap<>();
        if (!puDir.exists()) {
            puDir.mkdirs();
            return result;
        }

        File[] entries = puDir.listFiles();
        if (entries == null) return result;

        for (File entry : entries) {
            if (entry.getName().startsWith(".")) continue;
            if (entry.isFile()) {
                // Pu/<作者>.yml
                if (!isYaml(entry.getName())) continue;
                String author = stripExt(entry.getName());
                loadOne(entry, author, result);
            } else if (entry.isDirectory()) {
                // Pu/<作者>/**/*.yml
                String author = entry.getName();
                List<File> files = new ArrayList<>();
                collectYaml(entry, files);
                for (File f : files) {
                    loadOne(f, author, result);
                }
            }
        }
        return result;
    }

    private static void collectYaml(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) collectYaml(f, out);
            else if (isYaml(f.getName())) out.add(f);
        }
    }

    private static void loadOne(File file, String expectedAuthor,
                                Map<String, AuthorBundle> result) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(
                    file.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> yml = MiniYaml.parse(lines);

            PluginApi api = new PluginApi();
            api.source = file;

            String declaredAuthor = MiniYaml.str(yml, "author");
            if (declaredAuthor == null || declaredAuthor.trim().isEmpty()) {
                Messages.warn("跳过 " + rel(file) + "：缺少 author 字段");
                return;
            }
            declaredAuthor = declaredAuthor.trim();
            if (!declaredAuthor.equals(expectedAuthor)) {
                Messages.warn("跳过 " + rel(file) + "：author=" + declaredAuthor
                        + " 与所在位置 " + expectedAuthor + " 不一致");
                return;
            }
            api.author = declaredAuthor;

            String name = MiniYaml.str(yml, "api");
            if (name == null || name.trim().isEmpty()) {
                Messages.warn("跳过 " + rel(file) + "：缺少 api 字段");
                return;
            }
            api.api = name.trim();

            String ver = MiniYaml.str(yml, "version");
            if (ver != null && !ver.isEmpty()) api.version = ver;

            String desc = MiniYaml.str(yml, "description");
            if (desc != null) api.description = desc;

            String enabled = MiniYaml.str(yml, "enabled");
            if (enabled != null) api.enabled = !"false".equalsIgnoreCase(enabled);

            // 功能列表：只读取顶格的 functions 下的 "- id: xxx" 起始块
            parseFunctions(file, lines, api);

            if (api.functions.isEmpty()) {
                Messages.warn("跳过 " + rel(file) + "：没有任何 functions");
                return;
            }

            AuthorBundle bundle = result.computeIfAbsent(api.author, AuthorBundle::new);
            // 同一作者下 API 名不可重复
            for (PluginApi existing : bundle.apis) {
                if (existing.api.equals(api.api)) {
                    Messages.warn("跳过 " + rel(file) + "：作者 " + api.author
                            + " 已有同名 API \"" + api.api + "\"");
                    return;
                }
            }
            bundle.apis.add(api);
        } catch (Exception e) {
            Messages.err("读取 " + rel(file) + " 失败：" + e.getMessage());
        }
    }

    /**
     * 解析 functions 段。
     *
     * <p>YAML 形如：
     * <pre>
     * functions:
     *   - id: ping
     *     type: log
     *     message: hello
     *   - id: notify
     *     type: http
     *     url: https://...
     *     requires: [ping]
     * </pre>
     * 用缩进状态机解析，避免引入完整 YAML 库。
     */
    private static void parseFunctions(File file, List<String> lines, PluginApi api) {
        boolean inFunctions = false;
        PluginApi.ApiFunction cur = null;
        int funcIndent = -1;

        for (String raw : lines) {
            if (raw == null) continue;
            String line = stripComment(raw.replace("\t", "    "));
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            int indent = line.length() - line.stripLeading().length();

            if (indent == 0) {
                inFunctions = trimmed.equals("functions:") || trimmed.startsWith("functions:");
                cur = null;
                funcIndent = -1;
                continue;
            }
            if (!inFunctions) continue;

            if (trimmed.startsWith("- ")) {
                // 新函数开始
                if (funcIndent < 0) funcIndent = indent;
                if (indent == funcIndent) {
                    if (cur != null) api.functions.add(cur);
                    cur = new PluginApi.ApiFunction();
                    applyFunctionField(cur, trimmed.substring(2).trim(), file);
                }
                continue;
            }

            if (cur != null && indent > funcIndent) {
                applyFunctionField(cur, trimmed, file);
            }
        }
        if (cur != null) api.functions.add(cur);
    }

    private static void applyFunctionField(PluginApi.ApiFunction f, String kv, File file) {
        int c = kv.indexOf(':');
        if (c < 0) return;
        String key = kv.substring(0, c).trim();
        String rawVal = kv.substring(c + 1).trim();
        String val = stripQuotes(rawVal);

        switch (key) {
            case "id":       f.id = val; break;
            case "type":     f.type = val; break;
            case "requires": f.requires = parseList(rawVal); break;
            case "run":      f.run = val; break;
            case "cwd":      f.cwd = val; break;
            case "url":      f.url = val; break;
            case "method":   f.method = val.toUpperCase(); break;
            case "body":     f.body = val; break;
            case "expect":   try { f.expect = Integer.parseInt(val); } catch (Exception ignore) { } break;
            case "to":       f.to = val; break;
            case "class":    f.className = val; break;
            case "methodName":
            case "java-method": f.javaMethod = val; break;
            case "args":     f.args = parseList(rawVal); break;
            case "message":  f.message = val; break;
            default:
                // 未知字段不报错，允许作者写注释性字段，保持高自由度
                break;
        }
    }

    private static String stripComment(String line) {
        boolean inS = false, inD = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inD) inS = !inS;
            else if (c == '"' && !inS) inD = !inD;
            else if (c == '#' && !inS && !inD) {
                if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
                    return line.substring(0, i);
                }
            }
        }
        return line;
    }

    private static List<String> parseList(String raw) {
        List<String> out = new ArrayList<>();
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            String inner = s.substring(1, s.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (String p : inner.split(",")) {
                    String v = stripQuotes(p.trim());
                    if (!v.isEmpty()) out.add(v);
                }
            }
        } else if (!s.isEmpty()) {
            String v = stripQuotes(s);
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static boolean isYaml(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".yml") || n.endsWith(".yaml");
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String rel(File f) {
        String p = f.getPath().replace('\\', '/');
        int i = p.lastIndexOf("/Pu/");
        return i >= 0 ? p.substring(i + 1) : f.getName();
    }
}