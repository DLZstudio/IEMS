package com.iems.client;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渲染器配置（M11）。
 * <p>
 * 配置文件 {@code config/DLZstudio/IEMS/Renderer.toml}（TOML 子集：{@code [renderer]} 段 +
 * {@code key = value} 行，{@code #} 注释）。目录或文件缺失时自动创建并生成默认值落盘。
 * </p>
 * <p>
 * 与 {@code WebPanelConfig}（服务端 API 配置）同目录、同解析风格，但本类只在客户端使用
 * （渲染发生在客户端），专用服务器不会触碰。首次读取后进程内缓存，{@link #invalidate()}
 * 可清空使下次进入世界生效新配置。
 * </p>
 *
 * @param colorizeAutoConnect {@code true} = 自动连接（适配器 ↔ 外部 FE 设备的 {@code ADAPTER_BRIDGE}
 *                            桥接带）以青色绘制，与普通连线形成颜色差异；
 *                            {@code false} = 与普通连线同色、不做区分（连接本身仍然渲染）
 */
public record RendererConfig(boolean colorizeAutoConnect) {

    /** 配置所在子目录（相对游戏 config 目录，工作室统一命名空间）。 */
    public static final String SUBDIR_1 = "DLZstudio";
    public static final String SUBDIR_2 = "IEMS";

    /** 配置文件名。 */
    public static final String FILE_NAME = "Renderer.toml";

    /** 配置段名。 */
    public static final String SECTION_NAME = "renderer";

    /** 默认开启颜色差异（保留既有设计意图：桥接带以青色呈现）。 */
    public static final boolean DEFAULT_COLORIZE_AUTO_CONNECT = true;

    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([A-Za-z_][A-Za-z0-9_]*)\\]\\s*$");

    /** 进程内惰性缓存（首次读取后固定）。 */
    private static volatile RendererConfig cached;

    /** 默认配置。 */
    public static RendererConfig defaults() {
        return new RendererConfig(DEFAULT_COLORIZE_AUTO_CONNECT);
    }

    /** 配置文件完整路径：{@code <configDir>/DLZstudio/IEMS/Renderer.toml}。 */
    public static Path configFile(Path configDir) {
        return configDir.resolve(SUBDIR_1).resolve(SUBDIR_2).resolve(FILE_NAME);
    }

    /**
     * 供渲染线程调用：进程内只读一次，首次访问时加载并落盘缺失的默认文件。
     * <p>渲染每帧读取，因此必须廉价——命中缓存时仅为一次 volatile 读。</p>
     */
    public static RendererConfig get() {
        RendererConfig local = cached;
        if (local == null) {
            local = load(FMLPaths.CONFIGDIR.get());
            cached = local;
        }
        return local;
    }

    /** 清空缓存（退出世界时调用），使下次进入世界重新读取配置文件。 */
    public static void invalidate() {
        cached = null;
    }

    /** 从游戏配置目录读取；文件缺失时生成默认并落盘。 */
    public static RendererConfig load(Path configDir) {
        Path file = configFile(configDir);
        RendererConfig def = defaults();
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
     * 仅识别 {@code [renderer]} 段。
     */
    public static RendererConfig parse(String content, RendererConfig fallback) {
        boolean inSection = false;
        boolean colorize = fallback.colorizeAutoConnect();
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
            if ("colorizeAutoConnect".equals(kv.group(1))) {
                colorize = Boolean.parseBoolean(kv.group(2).trim());
            }
            // 未知键忽略（向前兼容）
        }
        return new RendererConfig(colorize);
    }

    /** 写回配置文件（UTF-8）。 */
    public static void write(Path file, RendererConfig cfg) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# IEMS 渲染器配置（M11）\n");
        sb.append("[").append(SECTION_NAME).append("]\n");
        sb.append("# true  = 自动连接（适配器 ↔ 外部 FE 设备的桥接带）以青色区分；\n");
        sb.append("# false = 与普通连线同色、不区分（连接本身仍然渲染）。\n");
        sb.append("colorizeAutoConnect = ").append(cfg.colorizeAutoConnect()).append('\n');
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }
}