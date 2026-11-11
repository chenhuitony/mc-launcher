package com.mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 YAML 子集解析器，用于读取插件 jar 内的 plugin.yml / paper-plugin.yml。
 *
 * 只处理插件元数据实际用到的结构：
 * <pre>
 * name: LuckPerms
 * version: 5.5.71
 * api-version: '1.21'
 * authors: [Luck]
 * softdepend:
 *   - Vault
 * commands:
 *   lp:
 *     description: 不会读到（缩进更深）
 * </pre>
 *
 * 规则：
 *   - 只把「顶格（缩进 0）」的键当作字段；
 *   - 顶格键的值为空时，收集其下紧跟的 "- xxx" 列表项；
 *   - 更深层的嵌套映射直接忽略（插件元数据不需要）。
 */
public final class MiniYaml {

    private MiniYaml() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> root = new LinkedHashMap<>();
        String currentKey = null;

        for (String raw : lines) {
            if (raw == null) continue;
            String line = stripTrailingComment(raw.replace("\t", "    "));
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#")) continue;

            int indent = line.length() - line.stripLeading().length();

            if (indent == 0) {
                int c = colon(trimmed);
                if (c < 0) { currentKey = null; continue; }
                String key = unquote(trimmed.substring(0, c).trim());
                String val = trimmed.substring(c + 1).trim();

                if (val.isEmpty()) {
                    root.put(key, new ArrayList<String>());
                    currentKey = key;
                } else {
                    root.put(key, scalar(val));
                    currentKey = null;
                }
            } else if (currentKey != null && trimmed.startsWith("- ")) {
                Object o = root.get(currentKey);
                if (!(o instanceof List)) {
                    o = new ArrayList<String>();
                    root.put(currentKey, o);
                }
                ((List<Object>) o).add(scalar(trimmed.substring(2).trim()));
            }
            // 其它更深缩进的行一律忽略
        }
        return root;
    }

    /**
     * 去掉行尾注释。
     * 只有「空白 + #」才算注释开头，避免把 URL 里的 # 或引号内的 # 误删。
     */
    private static String stripTrailingComment(String line) {
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

    /** 找到第一个不在引号内的冒号。 */
    private static int colon(String s) {        boolean inS = false, inD = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inD) inS = !inS;
            else if (c == '"' && !inS) inD = !inD;
            else if (c == ':' && !inS && !inD) return i;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Object scalar(String v) {
        String s = v.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            List<Object> list = new ArrayList<>();
            String inner = s.substring(1, s.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (String part : inner.split(",")) {
                    String p = unquote(part.trim());
                    if (!p.isEmpty()) list.add(p);
                }
            }
            return list;
        }
        return unquote(s);
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /** 取字符串字段。 */
    public static String str(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o == null ? null : String.valueOf(o);
    }

    /** 取字符串列表字段，兼容单个字符串与无字段。 */
    public static List<String> list(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        Object o = m.get(key);
        if (o == null) return out;
        if (o instanceof List) {
            for (Object x : (List<Object>) o) out.add(String.valueOf(x));
        } else {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}