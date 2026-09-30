package com.iems.core.energy;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 能量汇率配置。
 * <p>
 * 配置文件 {@code config/DLZstudio/IEMS/Energy.toml}（TOML 子集：{@code [energy]} 段 +
 * {@code key = value} 行，{@code #} 注释）。目录或文件缺失时自动创建并生成默认值落盘。
 * </p>
 * <p>
 * 可调项为 SE 与 FE 的汇率 {@code fePerSe}（1 SE 折算多少 FE）——SE 是电网内部标准
 * 单位，其量级决定了「多大的电网缺口才值得动用 FE 级设备」，因此按整合包实际能量
 * 量级开放配置。
 * </p>
 * <ul>
 *   <li>默认 {@link #DEFAULT_FE_PER_SE}：{@code 1 SE = 10 FE}；</li>
 *   <li>下限 {@link #MIN_FE_PER_SE}：{@code 1}（1 SE = 1 FE）;</li>
 *   <li>上限 {@link #MAX_FE_PER_SE}：{@code 9×10²⁶}（旧版硬编码默认值，可一键恢复
 *       旧行为；超出此值无实际意义，故不开放）。</li>
 * </ul>
 * <p>
 * 越界值按上下限收敛、非数字回退默认值；两项均在读取时安静处理（有效值由模组入口
 * 加载后打印日志，便于核对）。改动后在游戏内执行 {@code /iems reload} 即时生效，
 * 无需重启游戏（见 {@link #reload(Path)}）。
 * </p>
 * <p>
 * <b>重载语义</b>：SE 是电网内部记账基准，改动汇率会立即重新解释电网中<b>所有</b>
 * 已存在的 SE 数值（例如核心储能、设备容量），即「1 SE 代表多少 FE」被重定义；
 * 各设备间的 SE 比例关系不变，电网结算依然守恒，但对外呈现的 FE 当量会整体缩放。
 * </p>
 *
 * @param fePerSe 1 SE 折算的 FE 数量（≥ 1）
 */
public record EnergyConfig(BigInteger fePerSe) {

    /** 配置所在子目录（相对游戏 config 目录，工作室统一命名空间）。 */
    public static final String SUBDIR_1 = "DLZstudio";
    public static final String SUBDIR_2 = "IEMS";

    /** 配置文件名。 */
    public static final String FILE_NAME = "Energy.toml";

    /** 配置段名。 */
    public static final String SECTION_NAME = "energy";

    /** 默认汇率：1 SE = 10 FE（与 {@link EnergyUnit#SE} 的出厂系数一致，单一来源）。 */
    public static final BigInteger DEFAULT_FE_PER_SE = EnergyUnit.SE.getDefaultFeFactor();

    /** 汇率下限：1 SE = 1 FE。 */
    public static final BigInteger MIN_FE_PER_SE = BigInteger.ONE;

    /** 汇率上限：9×10²⁶ FE（旧版硬编码默认值）。 */
    public static final BigInteger MAX_FE_PER_SE = new BigInteger("900000000000000000000000000");

    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([A-Za-z_][A-Za-z0-9_]*)\\]\\s*$");

    /** 进程内当前生效配置（{@link #reload(Path)} 后同步刷新；解析缺键时作为回退基准）。 */
    private static volatile EnergyConfig current = defaults();

    /** 默认配置。 */
    public static EnergyConfig defaults() {
        return new EnergyConfig(DEFAULT_FE_PER_SE);
    }

    /** 配置文件完整路径：{@code <configDir>/DLZstudio/IEMS/Energy.toml}。 */
    public static Path configFile(Path configDir) {
        return configDir.resolve(SUBDIR_1).resolve(SUBDIR_2).resolve(FILE_NAME);
    }

    /**
     * 从游戏配置目录读取（文件缺失时生成默认并落盘）。
     * <p>模组入口加载期调用一次，结果经 {@link #apply(EnergyConfig)} 注入换算器。</p>
     */
    public static EnergyConfig load(Path configDir) {
        Path file = configFile(configDir);
        EnergyConfig def = defaults();
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
            return parse(Files.readString(file, StandardCharsets.UTF_8), def);
        } catch (IOException e) {
            return def;
        }
    }

    /**
     * 手动重载：读取配置文件并立即应用新汇率（由 {@code /iems reload} 触发，不做变更探测）。
     * <p>
     * 文件缺失时生成默认配置落盘；解析缺键时回退<b>当前生效值</b>而非出厂默认，
     * 避免局部编辑把其它项打回默认。命令在服务器主线程执行，与 tick 结算互斥，
     * 因此切换是原子的（{@link EnergyConverter} 仅被 tick 线程访问，无需额外同步）。
     * </p>
     *
     * @return 已应用的新配置（读取失败时返回当前生效值原样）
     */
    public static EnergyConfig reload(Path configDir) {
        Path file = configFile(configDir);
        EnergyConfig cfg;
        if (!Files.exists(file)) {
            cfg = defaults();
            try {
                Files.createDirectories(file.getParent());
                write(file, cfg);
            } catch (IOException ignored) {
                // 写盘失败不影响本次应用
            }
        } else {
            try {
                cfg = parse(Files.readString(file, StandardCharsets.UTF_8), current);
            } catch (IOException e) {
                cfg = current;
            }
        }
        apply(cfg);
        return cfg;
    }

    /**
     * 解析 TOML 子集内容；缺失的键回退 {@code fallback} 对应值。
     * 仅识别 {@code [energy]} 段，未知键忽略（向前兼容）。
     */
    public static EnergyConfig parse(String content, EnergyConfig fallback) {
        boolean inSection = false;
        BigInteger rate = fallback.fePerSe();
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
            if ("fePerSe".equals(kv.group(1))) {
                rate = parseRate(kv.group(2).trim(), fallback.fePerSe());
            }
        }
        return new EnergyConfig(rate);
    }

    /** 写回配置文件（UTF-8）。 */
    public static void write(Path file, EnergyConfig cfg) throws IOException {
        Files.writeString(file, render(cfg), StandardCharsets.UTF_8);
    }

    /** 渲染配置文件文本（落盘与默认文件内容一致，便于内容比对）。 */
    private static String render(EnergyConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("# IEMS 能量汇率配置\n");
        sb.append("[").append(SECTION_NAME).append("]\n");
        sb.append("# 1 SE 折算多少 FE（即 SE:FE = 1:N）。\n");
        sb.append("# 默认 ").append(DEFAULT_FE_PER_SE).append("；取值范围 [").append(MIN_FE_PER_SE)
                .append(", ").append(MAX_FE_PER_SE).append("]（上限即旧版固定值，可一键恢复旧行为）。\n");
        sb.append("# 修改后在游戏内执行 /iems reload 即时生效（无需重启游戏/退出世界）。\n");
        sb.append("# 注意：改动会重新解释电网中已有的 SE 数值（1 SE 的 FE 当量被重定义），请谨慎调整。\n");
        sb.append("fePerSe = ").append(cfg.fePerSe()).append('\n');
        return sb.toString();
    }

    /** 把本配置注入 {@link EnergyConverter}（越界/非法值已在解析期收敛，此处不再校验）。 */
    public static void apply(EnergyConfig cfg) {
        EnergyConverter.setFeFactor(EnergyUnit.SE, cfg.fePerSe());
        current = cfg;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 解析汇率字面量：仅接受纯十进制整数。非数字回退 {@code fallback}，
     * 越界按 {@link #MIN_FE_PER_SE}/{@link #MAX_FE_PER_SE} 收敛。
     */
    private static BigInteger parseRate(String literal, BigInteger fallback) {
        BigInteger value;
        try {
            value = new BigInteger(literal);
        } catch (NumberFormatException e) {
            return fallback;
        }
        if (value.signum() <= 0) {
            return MIN_FE_PER_SE;
        }
        return value.max(MIN_FE_PER_SE).min(MAX_FE_PER_SE);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }
}