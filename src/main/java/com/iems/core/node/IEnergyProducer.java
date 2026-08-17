package com.iems.core.node;

import com.iems.core.energy.EnergyValue;

/**
 * 自定义设备生产接口。
 * <p>
 * 外部模组绕过三大基类、直接接入电网时实现本接口：
 * 每 Tick 返回本设备生产的能量（SE）。调度器会调用本方法获取输入。
 * </p>
 */
public interface IEnergyProducer {

    /** 每 Tick 生产的能量（SE）。 */
    EnergyValue producePerTick();
}
