package com.iems.core.node;

import java.math.BigInteger;

/**
 * 能量生产者接口。
 * <p>
 * 实现此接口的设备会向电网提供能量（如发电机、可再生能源模块）。
 * 每 Tick 调用 {@link #producePerTick()} 获取本 Tick 的产出量，
 * 由 {@link EnergyDispatcher} 统一调度并写入核心能量池。
 * </p>
 *
 * @see EnergyDispatcher
 * @see IEnergyConsumer
 */
public interface IEnergyProducer extends IEnergyNode {

    /**
     * 本 Tick 预计产出的能量（SE）。
     * <p>
     * 外部模组可在此读取内部储能状态、环境因素等，返回期望输出量。
     * 实际产出可能受电网约束（如协议容量不足时降级），请通过 {@link IEnergyNode#getProtocolCost()}
     * 了解自身占用成本。
     * </p>
     *
     * @return 本 Tick 产出 SE；返回 0 表示本 Tick 无产出
     */
    BigInteger producePerTick();

    /**
     * 生产者优先级（数值越小优先级越高，在供给不足时优先保活）。
     * <p>
     * 默认为 0（最高优先级）。数值越大越"最后供电"。
     * </p>
     *
     * @return 优先级，≥ 0
     */
    default int getPriority() {
        return 0;
    }
}
