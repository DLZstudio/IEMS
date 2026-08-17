package com.iems.core.node;

import java.math.BigInteger;

/**
 * 能量消费者接口。
 * <p>
 * 实现此接口的设备会从电网消耗能量（如机械臂、处理器、激光发射器）。
 * 每 Tick 调用 {@link #consumePerTick(available)} 请求可用能量，
 * 由 {@link EnergyDispatcher} 统一分配（可能因短缺而只分配部分）。
 * </p>
 *
 * @see EnergyDispatcher
 * @see IEnergyProducer
 */
public interface IEnergyConsumer extends IEnergyNode {

    /**
     * 请求本 Tick 的能量，上限为 {@code available}。
     * <p>
     * 返回值表示：<b>实际消耗</b>的 SE（供调度器扣除核心能量池时使用）。
     * 若电网完全无法满足，应返回 {@link BigInteger#ZERO}。
     * 返回的值不应超过 {@code available}。
     * </p>
     *
     * @param available 当前可分配的总能量预算（由调度器传入）
     * @return 实际消耗的 SE；≥ 0，≤ available
     */
    BigInteger consumePerTick(BigInteger available);

    /**
     * 消费者优先级（数值越小优先级越高，在供给不足时优先被满足）。
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
