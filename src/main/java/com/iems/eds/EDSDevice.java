package com.iems.eds;

import com.iems.core.energy.EnergyUnit;
import com.iems.core.grid.GlobalPos;

import java.math.BigInteger;
import java.util.Set;
import net.minecraft.core.Direction;

/**
 * 单台外部设备的发现记录（EDS v1，不可变）。
 * <p>
 * EDS 只负责「找到并分类」——本记录是扫描时刻的快照，不是实时状态：
 * stored/capacity 取自发现时刻的能力探测，后续变化需重新扫描。
 * </p>
 *
 * @param pos           设备位置
 * @param blockId       方块注册 ID，如 {@code draconicevolution:energy_pylon}
 * @param namespace     模组命名空间（blockId 的前缀），身份轴依据
 * @param unit          能量单位（FE capability 探测默认 FE；可被 flavor 覆盖如 AE）
 * @param flavorName    身份显示名，如 "Draconic Evolution"
 * @param canExtract    7 方向能力并集：可输出能量（生产者）
 * @param canReceive    7 方向能力并集：可接收能量（消费者）
 * @param stored        发现时刻存量（快照，单位为 {@code unit}）
 * @param capacity      最大容量（单位为 {@code unit}）
 * @param exposedSides  暴露能力的面（供 EDA 决定从哪面取能；null 表示无方向上下文）
 */
public record EDSDevice(GlobalPos pos,
                        String blockId,
                        String namespace,
                        EnergyUnit unit,
                        String flavorName,
                        boolean canExtract,
                        boolean canReceive,
                        BigInteger stored,
                        BigInteger capacity,
                        Set<Direction> exposedSides) {

    /** 可输出能量（生产者）。 */
    public boolean isProducer() {
        return canExtract;
    }

    /** 可接收能量（消费者）。 */
    public boolean isConsumer() {
        return canReceive;
    }

    /** 双向且带容量（储能型设备）。 */
    public boolean isStorage() {
        return canExtract && canReceive && capacity.signum() > 0;
    }
}