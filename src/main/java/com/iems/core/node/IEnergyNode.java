package com.iems.core.node;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

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

    /**
     * 协议容量占用（统一为 BigInteger，核心为 0）。
     * <p>
     * 可以是动态值（随负载、连接数量、距离等变化），可以是负数（释放容量）。
     * 由 {@link com.iems.core.grid.GridTopology#rebuild()} 定期刷新。
     * </p>
     */
    BigInteger getProtocolCost();

    /**
     * 激光/连接锚点偏移（相对所在方块坐标原点的世界偏移）。
     * <p>
     * 默认返回方块中心 {@code (0.5, 0.5, 0.5)}。多方块结构、异形模型的设备
     * 应覆写此方法指定实际连接口位置（例如 5 格高模型顶部的 {@code (0.5, 4.5, 0.5)}）。
     * 建立连接时锚点会被固化进 {@link com.iems.core.grid.Connection} 随网络同步到客户端渲染。
     * </p>
     */
    default Vec3 getAnchorOffset() {
        return new Vec3(0.5, 0.5, 0.5);
    }

    /**
     * 在拓扑重建前刷新协议成本。
     * <p>
     * 默认实现不做任何事；动态成本设备可重写此方法，在每次 BFS 前
     * 根据当前状态（负载、环境、对端数量等）重新计算内部缓存的成本值。
     * </p>
     */
    default void refreshProtocolCost() {
    }

    /** 序列化设备参数与状态，供外部模组持久化到 NBT。 */
    CompoundTag serializeState();

    /** 从 NBT 恢复设备参数与状态。 */
    void restoreState(CompoundTag tag);
}
