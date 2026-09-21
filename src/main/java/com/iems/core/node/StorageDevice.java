package com.iems.core.node;

import com.iems.core.energy.EnergyValue;

import java.math.BigInteger;

/**
 * 储能节点接口（StorageDevice）。
 * <p>
 * 电网中的能量缓存节点，负责充放电。调度器（{@code EnergyDispatcher}）在
 * 盈余时调用 {@link #onChargeTick}、短缺时调用 {@link #onDischargeTick}。
 * </p>
 * <p>
 * <b>M9+ 演进</b>：由具体类接口化——原生 SE 储能的标准实现为
 * {@link AbstractStorageDevice}；DA（DeviceAdapter）可对双向外部设备
 * （如 FE 电池）实现本接口伪装接入，与原生储能同权（一个设备一个身份，
 * 占用协议容量），调度器以统一的充放电语义结算，不区分 SE 原生或外部适配。
 * </p>
 */
public interface StorageDevice extends IEnergyNode {

    /**
     * 调度器调用：传入盈余，返回实际充入量。
     * <p>实现方自行按「容量余量 / 每 tick 吞吐上限」双重约束节流，
     * 返回量不应超过传入盈余。</p>
     */
    EnergyValue onChargeTick(EnergyValue surplus);

    /**
     * 调度器调用：传入缺口，返回实际放出量。
     * <p>实现方自行按「当前储量 / 每 tick 吞吐上限」双重约束节流，
     * 返回量不应超过传入缺口。</p>
     */
    EnergyValue onDischargeTick(EnergyValue deficit);

    /** 当前储能（SE）。 */
    BigInteger getStoredEnergy();

    /** 最大储能（SE）。 */
    BigInteger getMaxEnergy();

    /** 每 tick 最大吞吐（SE）。 */
    BigInteger getIoRatePerTick();
}
