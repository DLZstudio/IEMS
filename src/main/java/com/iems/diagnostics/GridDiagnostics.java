package com.iems.diagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * IEMS 电网诊断日志（M8.2）。
 * <p>
 * 自建日志文件 {@code logs/iems/grid.log}（服务器工作目录下，每次会话追加，
 * 文件头带会话分隔线），与 Minecraft 主日志隔离，便于单独排查连接功能问题：
 * </p>
 * <ul>
 *   <li><b>每 tick 状态行</b>（{@code [T]}）：设备池/核心/能量/协议/连接/快照/
 *       调度器统计/同步推送的完整截面，由 IEMSEvents.onServerTick 写入；
 *       可用 {@code /iems debug} 开关（事件日志不受开关影响）。</li>
 *   <li><b>事件行</b>（{@code [E]}）：设备注册注销、核心注册注销、连接增删、
 *       关停/复电翻转、C2S 拉线请求校验、自动连接扫描结果、快照重建。</li>
 * </ul>
 * <p>
 * 所有写入吞异常（诊断系统故障不得影响游戏运行），单机诊断场景下
 * 每 tick 一行 + 立即 flush 的开销可接受。
 * </p>
 */
public final class GridDiagnostics {

    /** 日志目录（相对服务器工作目录）。 */
    private static final Path LOG_DIR = Paths.get("logs", "iems");

    /** 日志文件名。 */
    private static final Path LOG_FILE = LOG_DIR.resolve("grid.log");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static volatile BufferedWriter writer;

    /** 每 tick 状态行开关（事件行恒开）。 */
    private static volatile boolean tickLogEnabled = true;

    private GridDiagnostics() {
    }

    /** 打开日志文件（服务器启动时调用，追加模式 + 会话分隔头）。 */
    public static void open(String sessionTitle) {
        try {
            Files.createDirectories(LOG_DIR);
            writer = Files.newBufferedWriter(LOG_FILE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writeRaw("=========================================================");
            writeRaw("IEMS session start: " + LocalDateTime.now() + "  " + sessionTitle);
            writeRaw("tick log enabled = " + tickLogEnabled + "  (toggle: /iems debug)");
            writeRaw("=========================================================");
        } catch (IOException e) {
            writer = null;
        }
    }

    /** 关闭日志文件（服务器完全停止后调用）。 */
    public static void close() {
        BufferedWriter w = writer;
        writer = null;
        if (w != null) {
            try {
                w.write("IEMS session end: " + LocalDateTime.now());
                w.newLine();
                w.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 每 tick 状态行（tickLogEnabled 关闭时丢弃）。 */
    public static void tick(String stateLine) {
        if (!tickLogEnabled || writer == null) {
            return;
        }
        writeRaw(stamp() + " [T] " + stateLine);
    }

    /** 事件行（恒开）：格式化参数，例如 {@code event("+conn %s <-> %s", a, b)}。 */
    public static void event(String format, Object... args) {
        if (writer == null) {
            return;
        }
        String body;
        try {
            body = String.format(format, args);
        } catch (Exception e) {
            body = format;
        }
        writeRaw(stamp() + " [E] " + body);
    }

    /** 每 tick 状态行开关当前值（供调用方先行判断，避免关闭时无谓拼串）。 */
    public static boolean isTickLogEnabled() {
        return tickLogEnabled;
    }

    /** 切换每 tick 状态行开关（/iems debug）。 */
    public static boolean toggleTickLog() {
        tickLogEnabled = !tickLogEnabled;
        event("tick log %s", tickLogEnabled ? "ENABLED" : "DISABLED");
        return tickLogEnabled;
    }

    private static String stamp() {
        return LocalDateTime.now().format(TIME);
    }

    private static synchronized void writeRaw(String line) {
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        try {
            w.write(line);
            w.newLine();
            w.flush();
        } catch (IOException ignored) {
        }
    }
}
