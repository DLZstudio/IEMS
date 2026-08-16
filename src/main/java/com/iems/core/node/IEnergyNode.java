package com.iems.core.node;

import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;

/**
 * 能量节点：接入 IEMS 电网的统一抽象。
 * <p>
 * 外部模组通过 new 具体实现（CoreDevice / TransferDevice / StorageDevice / DimensionGate）
 * 并调用 {@code IEMSAPI.registerDevice} 完成接入。
 * 节点只暴露参数与状态，不持有对电网或其它节点的对象引用（实例自治）。
 * </p>
 */
public interface IEnergyNode {

    /** 设备名称。 */
    String getDeviceName();

    /** 协议容量占用（统一为 BigInteger，核心为 0）。 */
    BigInteger getProtocolCost();

    /** 序列化设备参数与状态，供外部模组持久化到 NBT。 */
    CompoundTag serializeState();

    /** 从 NBT 恢复设备参数与状态。 */
    void restoreState(CompoundTag tag);
}
