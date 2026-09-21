package com.iems.adapter;

import com.iems.core.node.IEnergyNode;
import com.iems.core.node.IEnergyProducer;
import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;

/**
 * 外部 FE 纯生产者伪装节点（逐设备）。
 * <p>
 * 每台 {@code canExtract && !canReceive} 的外部设备对应一个独立节点，
 * 协议容量 = 1（与原生 SE 设备同权）。外部能力读写由 {@link FEDA} 每 tick
 * 驱动（抽取 FE → {@link FeConversionBuffer}），调度器经
 * {@link #producePerTick()} 读取折算的整 SE 产出（抽取侧缓冲，守恒）。
 * </p>
 */
public class FeProducerAdapter implements IEnergyNode, IEnergyProducer, IAdapterNode {

    private final String deviceName;
    private final FeConversionBuffer buffer;
    private final FEDA owner;

    public FeProducerAdapter(String deviceName, FEDA owner) {
        this.deviceName = deviceName;
        this.buffer = new FeConversionBuffer();
        this.owner = owner;
    }

    /** 抽取缓冲折算整 SE 产出（由调度器每 tick 采样，脉冲契约）。 */
    @Override
    public BigInteger producePerTick() {
        return buffer.produceSE(BigInteger.valueOf(Long.MAX_VALUE));
    }

    @Override
    public String getDeviceName() {
        return deviceName;
    }

    /** 伪装节点不持久化（无工厂 ID，重启后由 FEDA 重扫重建），恒占 1 协议容量。 */
    @Override
    public BigInteger getProtocolCost() {
        return BigInteger.ONE;
    }

    @Override
    public CompoundTag serializeState() {
        return new CompoundTag();
    }

    @Override
    public void restoreState(CompoundTag tag) {
    }

    @Override
    public FEDA owner() {
        return owner;
    }

    FeConversionBuffer buffer() {
        return buffer;
    }
}
