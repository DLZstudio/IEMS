package com.iems.core.energy;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 能量值：不可变值对象。
 * <p>
 * 内部以 SE（Standard Energy，BigInteger）存储，对外提供任意单位换算。
 * 支持加减、比较、min/max 等运算，供调度器与储能节点使用。
 * </p>
 */
public final class EnergyValue {

    public static final EnergyValue ZERO = new EnergyValue(BigInteger.ZERO);

    private final BigInteger seAmount;

    private EnergyValue(BigInteger seAmount) {
        this.seAmount = seAmount;
    }

    /** 以 SE 值构造。 */
    public static EnergyValue ofSE(BigInteger seAmount) {
        return new EnergyValue(seAmount);
    }

    /** 以指定单位的数值构造，内部换算为 SE。 */
    public static EnergyValue of(BigInteger amount, EnergyUnit unit) {
        return new EnergyValue(EnergyConverter.toSE(amount, unit));
    }

    /** 以 FE 值构造。 */
    public static EnergyValue ofFE(BigInteger feAmount) {
        return of(feAmount, EnergyUnit.FE);
    }

    public BigInteger toSE() {
        return seAmount;
    }

    public BigInteger toFE() {
        return EnergyConverter.fromSE(seAmount, EnergyUnit.FE);
    }

    public BigInteger to(EnergyUnit unit) {
        return EnergyConverter.fromSE(seAmount, unit);
    }

    public EnergyValue add(EnergyValue other) {
        return new EnergyValue(seAmount.add(other.seAmount));
    }

    public EnergyValue subtract(EnergyValue other) {
        return new EnergyValue(seAmount.subtract(other.seAmount));
    }

    public boolean isZero() {
        return seAmount.signum() == 0;
    }

    public boolean isPositive() {
        return seAmount.signum() > 0;
    }

    public boolean isNegative() {
        return seAmount.signum() < 0;
    }

    public EnergyValue min(EnergyValue other) {
        return seAmount.compareTo(other.seAmount) <= 0 ? this : other;
    }

    public EnergyValue max(EnergyValue other) {
        return seAmount.compareTo(other.seAmount) >= 0 ? this : other;
    }

    public int compareTo(EnergyValue other) {
        return seAmount.compareTo(other.seAmount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EnergyValue that)) return false;
        return seAmount.equals(that.seAmount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seAmount);
    }

    @Override
    public String toString() {
        return seAmount + " SE";
    }
}
