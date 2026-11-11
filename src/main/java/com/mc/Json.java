package com.mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（无第三方依赖）。
 *
 * 只支持标准 JSON 子集：对象/数组/字符串/数字/布尔/null。
 * 足够解析 Mojang 的版本清单和版本描述文件。
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) { this.s = s; this.i = 0; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return (List<Object>) o;
    }

    public static String str(Map<String, Object> m, String... path) {
        Object cur = m;
        for (int k = 0; k < path.length; k++) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(path[k]);
        }
        return cur == null ? null : String.valueOf(cur);
    }

    public static long lng(Map<String, Object> m, String... path) {
        String v = str(m, path);
        if (v == null) return -1;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return -1; }
    }

    private Object value() {
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default:  return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String key = string();
            ws();
            i++; // :
            ws();
            m.put(key, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') break;
            if (c != ',') throw err("期望 , 或 }，实际 " + c);
        }
        return m;
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++; // [
        ws();
        if (s.charAt(i) == ']') { i++; return list; }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') break;
            if (c != ',') throw err("期望 , 或 ]，实际 " + c);
        }
        return list;
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++; // "
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.contains(".") || n.contains("e") || n.contains("E")) {
            return Double.parseDouble(n);
        }
        try { return Long.parseLong(n); } catch (NumberFormatException ex) { return Double.parseDouble(n); }
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private RuntimeException err(String m) {
        return new RuntimeException("JSON 解析失败（位置 " + i + "）：" + m);
    }
}