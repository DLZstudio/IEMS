package com.iems.core.energy;

import java.math.BigInteger;

/**
 * 能量单位。
 * <p>
 * 换算关系：FE 为基准，AE = 1 FE。SE（电网内部标准单位）的汇率
 * 由配置文件 {@code config/DLZstudio/IEMS/Energy.toml} 决定，见 {@link EnergyConfig}：
 * 默认 1 SE = 10 FE，上限 9×10²⁶ FE（旧版固定值）。
 * </p>
 */
public enum EnergyUnit {
    FE("FE", "1"),
    AE("AE", "1"),
    SE("SE", "10");

    private final String name;
    private final BigInteger defaultFeFactor;

    EnergyUnit(String name, String defaultFeFactor) {
        this.name = name;
        this.defaultFeFactor = new BigInteger(defaultFeFactor);
    }

    public String getName() {
        return name;
    }

    /** 默认换算系数：1 个该单位 = 多少 FE。 */
    public BigInteger getDefaultFeFactor() {
        return defaultFeFactor;
    }
}
