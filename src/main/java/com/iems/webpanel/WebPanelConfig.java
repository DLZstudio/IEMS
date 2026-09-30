package com.iems.webpanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 服务配置（M10 §3，M11 迁移）。
 * <p>
 * 配置文件 {@code config/DLZstudio/IEMS/APIserve.toml}（TOML 子集：{@code [APIserve]} 段 +
 * {@code key = value} 行，{@code #} 注释）。目录或文件缺失时自动创建并生成默认值落盘；
 * Token 首次启动用 {@link SecureRandom} 随机生成（32 字节 Base64）。
 * </p>
 *
 * @param enabled 是否启用面板
 * @param host    绑定地址（默认 127.0.0.1）
 * @param port    监听端口（默认 28567）
 * @param token   全部 API 端点鉴权 Token
 */
public record WebPanelConfig(boolean enabled, String host, int port, String token) {

    /** 配置所在子目录（相对游戏 config 目录，工作室统一命名空间）。 */
    public static final String SUBDIR_1 = "DLZstudio";
    public static final String SUBDIR_2 = "IEMS";

    /** 配置文件名（取代旧 {@code iems-webpanel.toml}）。 */
    public static final String FILE_NAME = "APIserve.toml";

    /** 配置段名。 */
    public static final String SECTION_NAME = "APIserve";

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 28567;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([A-Za-z_][A-Za-z0-9_]*)\\]\\s*$");

    /** 默认配置（随机 Token）。 */
    public static WebPanelConfig defaults() {
        return new WebPanelConfig(true, DEFAULT_HOST, DEFAULT_PORT, randomToken());
    }

    /** 配置文件完整路径：{@code <configDir>/DLZstudio/IEMS/APIserve.toml}。 */
    public static Path configFile(Path configDir) {
        return configDir.resolve(SUBDIR_1).resolve(SUBDIR_2).resolve(FILE_NAME);
    }

    /** 从游戏配置目录读取（自动定位 {@link #configFile(Path)}）；文件缺失时生成默认并落盘。 */
    public static WebPanelConfig load(Path configDir) {
        Path file = configFile(configDir);
        WebPanelConfig def = defaults();
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                write(file, def);
            } catch (IOException e) {
                return def;
            }
            return def;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return parse(content, def);
        } catch (IOException e) {
            return def;
        }
    }

    /**
     * 解析 TOML 子集内容；缺失的键回退 {@code fallback} 对应值。
     * 仅识别 {@code [APIserve]} 段。
     */
    public static WebPanelConfig parse(String content, WebPanelConfig fallback) {
        boolean inSection = false;
        boolean enabled = fallback.enabled();
        String host = fallback.host();
        int port = fallback.port();
        String token = fallback.token();
        for (String rawLine : content.split("\\R")) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                inSection = SECTION_NAME.equals(section.group(1));
                continue;
            }
            if (!inSection) {
                continue;
            }
            Matcher kv = KEY_VALUE.matcher(line);
            if (!kv.matches()) {
                continue;
            }
            String key = kv.group(1);
            String value = kv.group(2);
            switch (key) {
                case "enabled" -> enabled = Boolean.parseBoolean(value);
                case "host" -> host = unquote(value);
                case "port" -> {
                    try {
                        int parsed = Integer.parseInt(value);
                        // L-01：仅接受合法端口范围 [0,65535]（0 = 系统分配，测试用），
                        // 越界视为非法回退默认，避免 new InetSocketAddress 抛异常
                        if (parsed >= 0 && parsed <= 65535) {
                            port = parsed;
                        }
                    } catch (NumberFormatException ignored) {
                        // 非法端口回退默认
                    }
                }
                case "token" -> token = unquote(value);
                default -> {
                    // 未知键忽略（向前兼容）
                }
            }
        }
        return new WebPanelConfig(enabled, host, port, token);
    }

    /** 写回配置文件（UTF-8）。 */
    public static void write(Path file, WebPanelConfig cfg) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# IEMS Web 监控面板配置（M10）\n");
        sb.append("[").append(SECTION_NAME).append("]\n");
        sb.append("enabled = ").append(cfg.enabled()).append('\n');
        sb.append("host = \"").append(cfg.host()).append("\"\n");
        sb.append("port = ").append(cfg.port()).append('\n');
        sb.append("token = \"").append(cfg.token()).append("\"\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** 32 字节随机 Token，Base64 编码。 */
    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
