package com.iems.adapter;

import com.iems.core.node.IEnergyConsumer;
import com.iems.core.node.IEnergyNode;
import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;

/**
 * 外部 FE 纯消费者伪装节点（逐设备）。
 * <p>
 * 每台 {@code !canExtract && canReceive} 的外部设备对应一个独立节点，
 * 协议容量 = 1。需求申报（0/1 SE）由 {@link FEDA} 每 tick 测余量后写入
 * {@link FeConversionBuffer}；调度器分配后经 {@link #consumePerTick} 折算
 * FE 入推送池，由 FEDA 以 maxFePerTick 速率送抵外部。
 * </p>
 */
public class FeConsumerAdapter implements IEnergyNode, IEnergyConsumer, IAdapterNode {

    private final String deviceName;
    private final FeConversionBuffer buffer;
    private final FEDA owner;

    public FeConsumerAdapter(String deviceName, FEDA owner) {
        this.deviceName = deviceName;
        this.buffer = new FeConversionBuffer();
        this.owner = owner;
    }

    /** 纯查询：当前需求申报（0 或 1 SE，由外部接收余量决定）。 */
    @Override
    public BigInteger queryDemand() {
        return buffer.queryDemand();
    }

    /** 接受电网分配的 SE，折算 FE 入推送池（上限 = 需求申报）。 */
    @Override
    public BigInteger consumePerTick(BigInteger budget) {
        return buffer.consumeSE(budget);
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
