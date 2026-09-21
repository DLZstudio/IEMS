package com.iems.adapter;

import com.iems.core.energy.EnergyConverter;
import com.iems.core.energy.EnergyUnit;
import com.iems.core.energy.EnergyValue;
import com.iems.core.node.StorageDevice;
import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;

/**
 * 外部双向 FE 设备伪装节点（逐设备，StorageDevice）。
 * <p>
 * 每台 {@code canExtract && canReceive} 的外部设备对应一个独立节点，
 * 以统一储能契约接入调度器（与原生 {@code AbstractStorageDevice} 同权）：
 * </p>
 * <ul>
 *   <li><b>充电（SE → FE）</b>：{@link #onChargeTick} 折算 FE 入推送池，
 *       由 {@link FEDA} 每 tick 送抵外部（同消费节点推送语义）；</li>
 *   <li><b>放电（FE → SE）</b>：{@link #onDischargeTick} 把抽取侧 FE 尘埃
 *       折算整 SE。SE 量级巨大（1 SE = 9×10²⁶ FE），真实 FE 设备存量折算后
 *       通常为 0——调度器如实看到「空电池」，本路径在 SE 级储能设备出现前
 *       实际不生效（语义正确：FE 级电池确实无法支撑 SE 级电网缺口）；</li>
 *   <li><b>储量汇报</b>：{@link #getStoredEnergy}/{@link #getMaxEnergy}/
 *       {@link #getIoRatePerTick} 实时代理外部能力（由 FEDA 每 tick 探测刷新，
 *       不本地缓存——外部设备自身在耗/充）。</li>
 * </ul>
 */
public class FeBufferAdapter implements StorageDevice, IAdapterNode {

    private final String deviceName;
    private final FeConversionBuffer buffer;
    private final FEDA owner;

    /** 实时代理的外部能力（FEDA 每 tick 探测刷新；默认 0 = 未探测）。 */
    private volatile BigInteger liveStoredFe = BigInteger.ZERO;
    private volatile BigInteger liveMaxFe = BigInteger.ZERO;
    private volatile int liveIoRateFe = 0;

    public FeBufferAdapter(String deviceName, FEDA owner) {
        this.deviceName = deviceName;
        this.buffer = new FeConversionBuffer();
        this.owner = owner;
    }

    /** FEDA 探测后刷新外部实时能力（stored/max/ioRate，FE）。 */
    void setLiveState(BigInteger storedFe, BigInteger maxFe, int ioRateFe) {
        liveStoredFe = storedFe == null ? BigInteger.ZERO : storedFe.max(BigInteger.ZERO);
        liveMaxFe = maxFe == null ? BigInteger.ZERO : maxFe.max(BigInteger.ZERO);
        liveIoRateFe = Math.max(0, ioRateFe);
    }

    @Override
    public BigInteger getStoredEnergy() {
        // 适配器当前可对外提供量 = 外部设备实时存量 + 已抽取的 FE 尘埃（SE 折算）
        return seOf(liveStoredFe.add(buffer.getExtractedFe()));
    }

    @Override
    public BigInteger getMaxEnergy() {
        return seOf(liveMaxFe);
    }

    @Override
    public BigInteger getIoRatePerTick() {
        return seOf(BigInteger.valueOf(liveIoRateFe)).max(BigInteger.ONE);
    }

    @Override
    public EnergyValue onChargeTick(EnergyValue surplus) {
        // SE → FE 入推送池（同 FeConsumerAdapter 的消费语义），由 FEDA 送抵外部
        return EnergyValue.ofSE(buffer.consumeSE(surplus.toSE()));
    }

    @Override
    public EnergyValue onDischargeTick(EnergyValue deficit) {
        // FE 尘埃 → 整 SE（上限 = 缺口）；真实 FE 设备通常为 0
        return EnergyValue.ofSE(buffer.produceSE(deficit.toSE()));
    }

    private static BigInteger seOf(BigInteger fe) {
        return EnergyConverter.toSE(fe, EnergyUnit.FE);
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
