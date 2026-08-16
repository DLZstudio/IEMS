package com.iems.core.energy;

import java.math.BigInteger;

/**
 * 能量单位。
 * <p>
 * 换算关系（默认值，可经配置文件修改）：
 * FE 为基准，AE = 1 FE，GE = 9×10¹⁸ FE，SE = 9×10²⁶ FE（内部标准）。
 * </p>
 */
public enum EnergyUnit {
    FE("FE", "1"),
    AE("AE", "1"),
    GE("GE", "9000000000000000000"),
    SE("SE", "900000000000000000000000000");

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
