package com.mc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 RCON 客户端（Minecraft 远程控制协议）。
 *
 * <p>为什么需要它：启动器把 stdin 继承给了真实控制台（否则 JLine 会解析坏命令），
 * 因此无法再用管道向服务端写指令。RCON 是官方支持的远程指令通道，
 * 用于在服务端启动完成后自动执行 Pu API 里排队的指令。
 *
 * <p>协议：每个包都是 little-endian 的
 * {@code 长度(int) | 请求ID(int) | 类型(int) | 载荷(字节) | 0x00 0x00}
 * 类型：3=登录，2=执行指令，0=响应。
 */
public class RconClient implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private int requestId = 1;

    public RconClient(String host, int port, String password) throws Exception {
        socket = new Socket(host, port);
        socket.setSoTimeout(10000);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
        login(password);
    }

    private void login(String password) throws Exception {
        send(3, password);
        int[] resp = read();
        if (resp[0] == -1) {
            throw new RuntimeException("RCON 密码错误");
        }
    }

    /** 执行一条指令，返回服务端响应文本。 */
    public String command(String cmd) throws Exception {
        send(2, cmd);
        int[] resp = read();
        // resp 已把载荷存入 lastPayload
        return lastPayload;
    }

    private String lastPayload = "";

    private void send(int type, String payload) throws Exception {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        int len = 4 + 4 + body.length + 2;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        writeLeInt(d, len);
        writeLeInt(d, requestId);
        writeLeInt(d, type);
        d.write(body);
        d.write(0);
        d.write(0);
        out.write(buf.toByteArray());
        out.flush();
    }

    /** 读取一个响应包，返回 {请求ID, 类型} 并把载荷写入 lastPayload。 */
    private int[] read() throws Exception {
        int len = readLeInt(in);
        if (len < 10 || len > 1024 * 1024) {
            throw new RuntimeException("RCON 响应长度异常：" + len);
        }
        int id = readLeInt(in);
        int type = readLeInt(in);
        byte[] rest = new byte[len - 8];
        in.readFully(rest);
        int textLen = Math.max(0, rest.length - 2);
        lastPayload = new String(rest, 0, textLen, StandardCharsets.UTF_8);
        return new int[]{id, type};
    }

    private static void writeLeInt(DataOutputStream d, int v) throws Exception {
        d.write(v & 0xFF);
        d.write((v >>> 8) & 0xFF);
        d.write((v >>> 16) & 0xFF);
        d.write((v >>> 24) & 0xFF);
    }

    private static int readLeInt(DataInputStream in) throws Exception {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    @Override
    public void close() {
        try { socket.close(); } catch (Exception ignore) { }
    }
}