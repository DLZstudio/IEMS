package com.iems.core.node;

import java.math.BigInteger;

/**
 * 能量消费者接口。
 * <p>
 * 实现此接口的设备会从电网消耗能量（如机械臂、处理器、激光发射器）。
 * 调度器（{@link EnergyDispatcher}）采用两阶段契约：
 * <ol>
 *   <li>先调用 {@link #queryDemand()} <b>纯查询</b>本 Tick 的需求（不得有副作用）；</li>
 *   <li>再调用 {@link #consumePerTick(BigInteger)} <b>执行</b>消费（副作用在此发生）。</li>
 * </ol>
 * V-03 修复说明：旧契约只有一个 consumePerTick，调度器在一个 Tick 内
 * 先用它算总需求、再用它执行分配，副作用型实现会被扣减两次。
 * 拆分后两阶段语义明确，请务必遵守「query 无副作用」的约定。
 * </p>
 *
 * @see EnergyDispatcher
 * @see IEnergyProducer
 */
public interface IEnergyConsumer extends IEnergyNode {

    /**
     * 查询本 Tick 期望消耗的能量（纯查询，<b>不得产生任何副作用</b>）。
     * <p>
     * 调度器用它计算电网总需求，随后的实际扣减通过
     * {@link #consumePerTick(BigInteger)} 执行。
     * </p>
     *
     * @return 期望消耗的 SE；≥ 0
     */
    BigInteger queryDemand();

    /**
     * 执行本 Tick 的能量消费，上限为 {@code budget}（副作用应在此发生）。
     * <p>
     * 返回值表示：<b>实际消耗</b>的 SE（供调度器扣除核心能量池时使用）。
     * 若电网完全无法满足（budget 为 0），应返回 {@link BigInteger#ZERO}。
     * 返回值不应超过 {@code budget} 与 {@link #queryDemand()}。
     * </p>
     *
     * @param budget 当前可分配的能量预算（由调度器传入）
     * @return 实际消耗的 SE；≥ 0，≤ budget
     */
    BigInteger consumePerTick(BigInteger budget);

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
