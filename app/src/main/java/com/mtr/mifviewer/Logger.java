package com.mtr.mifviewer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** 应用内日志：内存环形缓冲 + 落盘（崩溃后仍可查看）。 */
public class Logger {
    private static final int MAX_LINES = 800;
    private static final long MAX_FILE = 200 * 1024;
    private static final LinkedList<String> buf = new LinkedList<>();
    private static File logFile;
    private static final SimpleDateFormat fmt =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    public static void init(File f) {
        logFile = f;
        loadFromFile();
        d("APP", "=== IMGF 查看器启动 ===");
    }

    public static synchronized void d(String tag, String msg) {
        String line = fmt.format(new Date()) + " [" + tag + "] " + msg;
        buf.add(line);
        while (buf.size() > MAX_LINES) buf.removeFirst();
        appendToFile(line);
    }

    public static synchronized void e(String tag, String msg, Throwable t) {
        d(tag, msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            for (String l : sw.toString().split("\n")) d(tag, l);
        }
    }

    public static synchronized String getAll() {
        if (buf.isEmpty()) return "(暂无日志)";
        StringBuilder sb = new StringBuilder();
        for (String l : buf) sb.append(l).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        buf.clear();
        if (logFile != null && logFile.exists()) logFile.delete();
    }

    /** 安装全局崩溃捕获：写日志后交还系统默认处理。 */
    public static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler def =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                e("CRASH", "FATAL in thread " + t.getName(), e);
            } catch (Throwable ignored) {}
            if (def != null) def.uncaughtException(t, e);
        });
        d("APP", "崩溃捕获已安装");
    }

    private static void appendToFile(String line) {
        if (logFile == null) return;
        try {
            if (logFile.exists() && logFile.length() > MAX_FILE) {
                List<String> keep = new ArrayList<>(
                        buf.subList(Math.max(0, buf.size() - 300), buf.size()));
                try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, false))) {
                    for (String l : keep) pw.println(l);
                }
                return;
            }
            try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
                pw.println(line);
            }
        } catch (IOException ignored) {}
    }

    private static void loadFromFile() {
        if (logFile == null || !logFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String l;
            while ((l = br.readLine()) != null) {
                buf.add(l);
                while (buf.size() > MAX_LINES) buf.removeFirst();
            }
        } catch (IOException ignored) {}
    }
}
