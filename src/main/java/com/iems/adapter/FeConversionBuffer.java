package com.iems.adapter;

import com.iems.core.energy.EnergyConverter;
import com.iems.core.energy.EnergyUnit;

import java.math.BigInteger;

/**
 * FE↔SE 换算缓冲（逐设备粒度）。
 * <p>
 * 原 {@code FeBridge} 的两侧缓冲逻辑下沉为单设备：伪装节点不持 Level，
 * 只维护数值状态；外部能力读写由 {@link FEDA} 每 tick 驱动。
 * </p>
 * <ul>
 *   <li><b>抽取侧</b> {@code feBuffer}：从外部抽取的 FE 尘埃累积。单 tick 抽取量
 *       折算 SE 后往往不足 1（取决于 {@code Energy.toml} 配置的汇率），余数跨 tick
 *       累积，攒满 1 SE 整体产出（{@link #produceSE}，守恒不丢）；</li>
 *   <li><b>推送侧</b> {@code pushBuffer}：电网分配的整 SE 折算 FE 入池（上限 =
 *       需求申报），由 DA 以 maxFePerTick 速率逐步送抵外部（平滑推送）；
 *       余量留池结转，不凭空蒸发。</li>
 *   <li><b>需求申报</b>：外部有接收余量且推送池空时申报一次，申报量按
 *       「覆盖一 tick 送抵上限」折算为 SE（见 {@link #updateExternalFree}）。</li>
 * </ul>
 */
public final class FeConversionBuffer {

    /** 抽取侧缓冲：已从外部抽取、尚未折算为 SE 产出的 FE。 */
    private BigInteger feBuffer = BigInteger.ZERO;

    /** 推送侧缓冲池：电网分配的整 SE 折算 FE 入池，由 DA 逐步送抵外部。 */
    private BigInteger pushBuffer = BigInteger.ZERO;

    /** 本 tick 需求申报（SE 粒度，≥0；外部有接收余量且推送池空时按一 tick 送抵量折算）。 */
    private volatile BigInteger cachedDemand = BigInteger.ZERO;

    public FeConversionBuffer() {
    }

    // ------------------------------------------------------------------
    // 抽取侧（FEDA 抽取后登记；生产/放电节点折算）
    // ------------------------------------------------------------------

    /** 登记本 tick 从外部设备实际抽取的 FE 总量。 */
    public void addExtracted(BigInteger fe) {
        if (fe != null && fe.signum() > 0) {
            feBuffer = feBuffer.add(fe);
        }
    }

    /** 抽取侧当前累积 FE（储能节点汇报可对外提供量时叠加）。 */
    public BigInteger getExtractedFe() {
        return feBuffer;
    }

    /**
     * 把抽取缓冲折算为整 SE 产出（上限 {@code cap}），FE 余数留在缓冲跨 tick
     * 累积（守恒，不丢失）。
     */
    public BigInteger produceSE(BigInteger cap) {
        BigInteger perSe = fePerSe();
        BigInteger whole = feBuffer.divide(perSe);
        if (whole.signum() > 0) {
            feBuffer = feBuffer.subtract(whole.multiply(perSe));
        }
        BigInteger upper = cap == null ? whole : cap.max(BigInteger.ZERO);
        return whole.min(upper);
    }

    // ------------------------------------------------------------------
    // 推送侧（FEDA 测余量后申报；消费节点接分配）
    // ------------------------------------------------------------------

    /**
     * 登记外部接收端的剩余容量（FE）并计算需求申报。
     * <p>平滑推送：外部有接收余量且推送池空时申报一次 SE 需求，推送池未清空前
     * 不再申报。申报量按「一 tick 可送抵的 FE」折算，即至少覆盖
     * {@code maxFePerTick}，否则汇率越低推送池越浅、吞吐会被压到
     * {@code fePerSe} FE/tick（旧实现固定申报 1 SE，仅在 1 SE = 9×10²⁶ FE
     * 时恰好等价于「一 tick 用不完」）。</p>
     *
     * @param feFreeTotal  外部接收端剩余容量（FE）
     * @param maxFePerTick 本适配器单 tick 送抵上限（FE），用于确定申报粒度
     */
    public void updateExternalFree(BigInteger feFreeTotal, int maxFePerTick) {
        BigInteger free = feFreeTotal == null ? BigInteger.ZERO : feFreeTotal.max(BigInteger.ZERO);
        if (free.signum() <= 0 || pushBuffer.signum() != 0) {
            cachedDemand = BigInteger.ZERO;
            return;
        }
        BigInteger perSe = fePerSe();
        BigInteger tickBudget = BigInteger.valueOf(Math.max(1, maxFePerTick));
        BigInteger feWanted = free.min(tickBudget);
        // 向上取整：申报的 SE 折算回 FE 后必须 ≥ 一 tick 的送抵上限
        BigInteger se = feWanted.add(perSe).subtract(BigInteger.ONE).divide(perSe);
        cachedDemand = se.max(BigInteger.ONE);
    }

    /** 当前需求申报（SE 粒度，纯查询，≥0）。 */
    public BigInteger queryDemand() {
        return cachedDemand.max(BigInteger.ZERO);
    }

    /**
     * 接受电网分配的 SE（上限为需求申报）折算 FE 进入推送池。
     * <p>实际送抵由 DA 在本 tick 结算后以 maxFePerTick 速率逐步执行。</p>
     */
    public BigInteger consumeSE(BigInteger budget) {
        if (budget == null || budget.signum() <= 0
                || cachedDemand.signum() <= 0 || pushBuffer.signum() != 0) {
            return BigInteger.ZERO;
        }
        BigInteger allocated = budget.min(cachedDemand);
        pushBuffer = pushBuffer.add(allocated.multiply(fePerSe()));
        return allocated;
    }

    /** 推送侧当前缓冲池（FE 记账）。 */
    public BigInteger getPushBuffer() {
        return pushBuffer;
    }

    /** 扣减已实际送抵外部的 FE。 */
    public void consumePushBuffer(BigInteger feDelivered) {
        if (feDelivered != null && feDelivered.signum() > 0) {
            pushBuffer = pushBuffer.subtract(feDelivered).max(BigInteger.ZERO);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 1 SE 折算多少 FE（经 EnergyConverter，随运行时配置覆盖生效）。 */
    private static BigInteger fePerSe() {
        return EnergyConverter.getFeFactor(EnergyUnit.SE);
    }
}
