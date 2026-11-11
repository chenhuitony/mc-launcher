package com.mc;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 中文输出工具。
 *
 * 编码自适应是关键：
 *   - Windows 中文控制台默认代码页是 GBK(936)，直接写 UTF-8 会乱码；
 *   - 但用户执行了 chcp 65001 后，控制台又变成 UTF-8。
 * 所以不能写死编码，要跟随控制台实际编码：
 *   优先 stdout.encoding（会随 chcp 变化） -> native.encoding -> UTF-8。
 */
public final class Messages {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static PrintStream OUT;

    private Messages() {}

    public static void init() {
        // 真控制台：跟随控制台自身编码（run.bat 已 chcp 65001 时为 UTF-8）；
        // 被重定向/管道时：统一用 UTF-8，避免把 GBK 字节写进 UTF-8 流造成乱码。
        String enc = "UTF-8";
        if (System.console() != null) {
            String se = System.getProperty("stdout.encoding");
            if (se != null) enc = se;
        }
        try {
            OUT = new PrintStream(
                    new java.io.BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 8192),
                    true, Charset.forName(enc));
        } catch (Exception e) {
            OUT = System.out;
        }
    }

    private static String ts() {
        return "[" + LocalDateTime.now().format(FMT) + "]";
    }

    /** 原样输出一行（用于服务端翻译后的日志）。 */
    public static void raw(String m) {
        OUT.println(m);
        OUT.flush();
    }

    public static void log(String m)  { OUT.println(ts() + " [INFO] " + m); }
    public static void ok(String m)   { OUT.println(ts() + " [ OK ] " + m); }
    public static void warn(String m) { OUT.println(ts() + " [WARN] " + m); }
    public static void err(String m)  { OUT.println(ts() + "[ERROR] " + m); }

    /** 单行进度：用 \r 覆盖同一行。 */
    public static void progress(int pct, long got, long total) {
        String g = String.format("%.1f", got / 1024.0 / 1024.0);
        String t = total > 0 ? String.format("%.1f MB", total / 1024.0 / 1024.0) : "?";
        int blocks = pct / 5;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 20; i++) bar.append(i < blocks ? '#' : '-');
        OUT.print("\r" + ts() + " [>>>] [" + bar + "] " + pct + "%  " + g + "MB / " + t + "   ");
        OUT.flush();
    }

    public static void progressDone() {
        OUT.println();
        OUT.flush();
    }
}