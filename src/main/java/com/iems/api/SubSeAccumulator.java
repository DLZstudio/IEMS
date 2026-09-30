package com.iems.api;

import java.math.BigInteger;

/**
 * 亚 SE 余数累加器：低速率设备的通用工具。
 *
 * <p><b>问题</b>：SE 是整数账本（{@link com.iems.core.energy.EnergyValue} 内部为
 * {@link BigInteger}），调度器单次结算粒度恒为 1 SE；但 1 SE 折算的 FE 量由
 * {@code config/DLZstudio/IEMS/Energy.toml} 配置决定（见
 * {@link com.iems.core.energy.EnergyConfig}），汇率偏高时 FE 量级设备的真实速率
 * 远小于 1 SE/tick。若直接把速率当作需求申报，要么每 tick 白扣 1 SE，
 * 要么在 0/1 之间抖动。</p>
 *
 * <p><b>解法</b>：把「不足 1 SE 的基础单位量」逐 tick 累加，攒满整 SE 才对外
 * 申报/产出，余数跨 tick 结转、不丢失——即「小数匀速累积、整 SE 落账」。
 * 本类将该模式抽成<b>每设备持有 1 个实例</b>的小型状态对象
 * （无状态函数无法跨 tick 保留余数，故不是工具方法而是实例）。</p>
 *
 * <p><b>用法（消费者）</b>：</p>
 * <pre>{@code
 * private final SubSeAccumulator acc = new SubSeAccumulator();
 *
 * // 每 tick 登记本 tick 真实需要的量（基础单位，如 FE）
 * public void tick() { acc.add(neededUnitsThisTick); }
 *
 * public BigInteger queryDemand() {
 *     return acc.pendingSe(unitsPerSe);       // 0 或 1（整 SE 粒度）
 * }
 *
 * public BigInteger consumePerTick(BigInteger budget) {
 *     return acc.claim(unitsPerSe, budget);   // 实际领取的整 SE，余数保留
 * }
 * }</pre>
 *
 * <p><b>用法（生产者/抽取侧）</b>：{@code acc.add(extractedUnits)}，随后
 * {@code producePerTick()} 返回 {@code acc.claim(unitsPerSe, null)}
 * （{@code cap} 传 {@code null} 表示不限量）。</p>
 *
 * <p><b>参考实现</b>：框架内 FE 桥接缓冲已采用同一模式（含抽取/推送双侧，
 * 行为与本类等价）。本类先独立提供；待低功率桥接出现第二个真实使用者后，
 * 再评估是否统一抽象，避免过早收敛出错误形状。</p>
 *
 * <p><b>线程</b>：仅在服务器 tick 单线程使用（与所有 {@code IEnergyNode} 一致），
 * 未做同步。</p>
 *
 * @see com.iems.core.node.IEnergyConsumer
 * @see com.iems.core.node.IEnergyProducer
 */
public final class SubSeAccumulator {

    /** 已累积、尚未折算为整 SE 的基础单位余数。 */
    private BigInteger remainder = BigInteger.ZERO;

    public SubSeAccumulator() {
    }

    /** 累加基础单位量（如 FE）；{@code null} 或非正数忽略。 */
    public void add(BigInteger units) {
        if (units != null && units.signum() > 0) {
            remainder = remainder.add(units);
        }
    }

    /**
     * 当前可申报/可产出的整 SE 数（纯查询，无副作用，可直接用作 {@code queryDemand}）。
     *
     * @param unitsPerSe 1 SE 等于多少基础单位（如 FE；须 &gt; 0，可随运行期换算系数变动传入）
     * @return 可领取的整 SE；≥ 0
     */
    public BigInteger pendingSe(BigInteger unitsPerSe) {
        return remainder.divide(requirePositive(unitsPerSe));
    }

    /**
     * 领取不超过 {@code cap} 的整 SE 并扣减对应基础单位，返回实际领取量；余数保留。
     *
     * @param unitsPerSe 1 SE 等于多少基础单位（须 &gt; 0）
     * @param cap        上限（SE）；{@code null} 表示不限量
     * @return 实际领取的整 SE；≥ 0，≤ {@code cap}
     */
    public BigInteger claim(BigInteger unitsPerSe, BigInteger cap) {
        BigInteger perSe = requirePositive(unitsPerSe);
        BigInteger whole = remainder.divide(perSe);
        BigInteger granted = cap == null ? whole : whole.min(cap.max(BigInteger.ZERO));
        if (granted.signum() > 0) {
            remainder = remainder.subtract(granted.multiply(perSe));
        }
        return granted;
    }

    /** 当前余数（基础单位），用于诊断/持久化。 */
    public BigInteger remainderUnits() {
        return remainder;
    }

    /** 清空余数（设备拆除 / 存档重建时调用）。 */
    public void clear() {
        remainder = BigInteger.ZERO;
    }

    private static BigInteger requirePositive(BigInteger unitsPerSe) {
        if (unitsPerSe == null || unitsPerSe.signum() <= 0) {
            throw new IllegalArgumentException("unitsPerSe must be positive: " + unitsPerSe);
        }
        return unitsPerSe;
    }
}