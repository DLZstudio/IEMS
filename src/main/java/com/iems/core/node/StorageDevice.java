package com.iems.core.node;

import com.iems.core.energy.EnergyValue;
import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;

/**
 * 储能节点（StorageDevice）。
 * <p>
 * 电网中的能量缓存节点，负责充放电。
 * </p>
 */
public class StorageDevice implements IEnergyNode {

    private final String deviceName;
    private final BigInteger protocolCost;
    private final BigInteger maxEnergy;
    private final BigInteger ioRatePerTick;

    private BigInteger storedEnergy = BigInteger.ZERO;

    public StorageDevice(String deviceName, BigInteger protocolCost, BigInteger maxEnergy, BigInteger ioRatePerTick) {
        this.deviceName = deviceName;
        this.protocolCost = protocolCost;
        this.maxEnergy = maxEnergy;
        this.ioRatePerTick = ioRatePerTick;
    }

    @Override
    public String getDeviceName() {
        return deviceName;
    }

    @Override
    public BigInteger getProtocolCost() {
        return protocolCost;
    }

    public BigInteger getMaxEnergy() {
        return maxEnergy;
    }

    public BigInteger getIoRatePerTick() {
        return ioRatePerTick;
    }

    public BigInteger getStoredEnergy() {
        return storedEnergy;
    }

    /**
     * 调度器调用：传入盈余，返回实际充入量。
     */
    public EnergyValue onChargeTick(EnergyValue surplus) {
        EnergyValue space = EnergyValue.ofSE(maxEnergy.subtract(storedEnergy));
        EnergyValue rateCap = EnergyValue.ofSE(ioRatePerTick);
        EnergyValue actual = surplus.min(space).min(rateCap).max(EnergyValue.ZERO);
        storedEnergy = storedEnergy.add(actual.toSE());
        return actual;
    }

    /**
     * 调度器调用：传入缺口，返回实际放出量。
     */
    public EnergyValue onDischargeTick(EnergyValue deficit) {
        EnergyValue available = EnergyValue.ofSE(storedEnergy);
        EnergyValue rateCap = EnergyValue.ofSE(ioRatePerTick);
        EnergyValue actual = deficit.min(available).min(rateCap).max(EnergyValue.ZERO);
        storedEnergy = storedEnergy.subtract(actual.toSE());
        return actual;
    }

    @Override
    public CompoundTag serializeState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("deviceName", deviceName);
        tag.putString("protocolCost", protocolCost.toString());
        tag.putString("maxEnergy", maxEnergy.toString());
        tag.putString("ioRatePerTick", ioRatePerTick.toString());
        tag.putString("storedEnergy", storedEnergy.toString());
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        this.storedEnergy = new BigInteger(tag.getString("storedEnergy"));
    }
}
