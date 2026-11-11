package com.mc;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个插件 API 的描述。
 *
 * <p>API 由作者定义，放在 Pu/ 目录下，文件名或目录名即作者名：
 * <pre>
 *   Pu/chenhuitony.yml               单个 API
 *   Pu/chenhuitony/welcome-kit.yml   该作者的多个 API
 * </pre>
 *
 * <p>描述文件格式：
 * <pre>
 * author: chenhuitony         必需，且必须与文件名/目录名一致
 * api: welcome-kit            必需，API 名称
 * version: 1.0.0              可选
 * description: 新玩家欢迎礼包   可选
 * enabled: true               可选，默认 true
 *
 * functions:                  功能列表，按顺序执行
 *   - id: ping                必需，函数 ID（API 内唯一）
 *     type: http              必需，见下方类型说明
 *     requires: [other.func]  可选，依赖的其它函数，需先执行
 *     <各类型自己的参数>
 * </pre>
 *
 * <p>支持的功能类型（自由度体现在这里）：
 * <table border="1">
 *   <tr><th>type</th><th>作用</th><th>参数</th></tr>
 *   <tr><td>command</td><td>向运行中的服务端发送控制台指令</td><td>run</td></tr>
 *   <tr><td>http</td><td>发起 HTTP 请求（Webhook / 健康检查）</td><td>url, method, body, expect</td></tr>
 *   <tr><td>script</td><td>执行外部程序或脚本</td><td>run, cwd</td></tr>
 *   <tr><td>download</td><td>下载文件到服务端目录</td><td>url, to</td></tr>
 *   <tr><td>java</td><td>反射调用一个静态方法（最大自由度）</td><td>class, method, args</td></tr>
 *   <tr><td>log</td><td>打印一条消息</td><td>message</td></tr>
 * </table>
 */
public class PluginApi {

    public String author;
    public String api;
    public String version = "1.0.0";
    public String description = "";
    public boolean enabled = true;
    public List<ApiFunction> functions = new ArrayList<>();

    /** API 的来源文件，用于报错定位。 */
    public java.io.File source;

    /** 唯一标识：author:api */
    public String id() {
        return author + ":" + api;
    }

    @Override
    public String toString() {
        return id() + " v" + version + "（" + functions.size() + " 个功能）";
    }

    /** 一个可执行功能。 */
    public static class ApiFunction {
        public String id;
        public String type;
        public List<String> requires = new ArrayList<>();

        // command / script
        public String run;
        public String cwd;

        // http
        public String url;
        public String method = "GET";
        public String body;
        public int expect = 200;

        // download
        public String to;

        // java
        public String className;
        public String javaMethod;
        public List<String> args = new ArrayList<>();

        // log
        public String message;

        /** 全局唯一名：author:api.functionId */
        public String qualified(PluginApi owner) {
            return owner.id() + "." + id;
        }

        @Override
        public String toString() {
            return id + "(" + type + ")";
        }
    }
}