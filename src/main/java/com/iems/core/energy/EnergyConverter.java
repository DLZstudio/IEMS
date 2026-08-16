package com.iems.core.energy;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Map;

/**
 * 能量换算器。
 * <p>
 * 换算系数以 FE 为基准，默认值取自 {@link EnergyUnit}，
 * 可通过 {@link #setFeFactor(EnergyUnit, BigInteger)} 在运行时覆盖（对应配置文件 iems.toml）。
 * 内部标准单位为 SE，所有能量运算均以 BigInteger 精确进行。
 * </p>
 */
public final class EnergyConverter {

    private static final Map<EnergyUnit, BigInteger> FE_FACTORS = new EnumMap<>(EnergyUnit.class);

    static {
        for (EnergyUnit unit : EnergyUnit.values()) {
            FE_FACTORS.put(unit, unit.getDefaultFeFactor());
        }
    }

    private EnergyConverter() {
    }

    /** 运行时修改某单位的换算系数（用于读取配置文件后覆盖默认值）。 */
    public static void setFeFactor(EnergyUnit unit, BigInteger feFactor) {
        if (feFactor == null || feFactor.signum() <= 0) {
            throw new IllegalArgumentException("feFactor must be positive: " + feFactor);
        }
        FE_FACTORS.put(unit, feFactor);
    }

    /** 1 个该单位 = 多少 FE。 */
    public static BigInteger getFeFactor(EnergyUnit unit) {
        return FE_FACTORS.get(unit);
    }

    /** 把 amount（from 单位）换算为 to 单位。 */
    public static BigInteger convert(BigInteger amount, EnergyUnit from, EnergyUnit to) {
        return amount.multiply(FE_FACTORS.get(from)).divide(FE_FACTORS.get(to));
    }

    /** 把 amount（unit 单位）换算为内部标准 SE。 */
    public static BigInteger toSE(BigInteger amount, EnergyUnit unit) {
        return convert(amount, unit, EnergyUnit.SE);
    }

    /** 把 SE 值换算为 unit 单位。 */
    public static BigInteger fromSE(BigInteger seAmount, EnergyUnit unit) {
        return convert(seAmount, EnergyUnit.SE, unit);
    }
}
