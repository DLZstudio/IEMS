package com.iems.core.node;

import com.iems.core.energy.EnergyValue;
import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;

/**
 * 储能节点标准实现（原生 SE 储能，原 {@code StorageDevice} 具体类的字段实现）。
 * <p>
 * 供原生 IEMS 储能设备及外部模组直接 {@code new} 使用。DA 伪装的双向外部设备
 * 应<b>直接实现</b> {@link StorageDevice} 接口（储量/容量代理外部能力），
 * 而非继承本类——本类的储量是自有字段，不具备外部代理语义。
 * </p>
 */
public class AbstractStorageDevice implements StorageDevice {

    private final String deviceName;
    private final BigInteger protocolCost;
    private final BigInteger maxEnergy;
    private final BigInteger ioRatePerTick;

    private BigInteger storedEnergy = BigInteger.ZERO;

    public AbstractStorageDevice(String deviceName, BigInteger protocolCost,
                                 BigInteger maxEnergy, BigInteger ioRatePerTick) {
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

    @Override
    public BigInteger getMaxEnergy() {
        return maxEnergy;
    }

    @Override
    public BigInteger getIoRatePerTick() {
        return ioRatePerTick;
    }

    @Override
    public BigInteger getStoredEnergy() {
        return storedEnergy;
    }

    /**
     * 调度器调用：传入盈余，返回实际充入量。
     */
    @Override
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
    @Override
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
