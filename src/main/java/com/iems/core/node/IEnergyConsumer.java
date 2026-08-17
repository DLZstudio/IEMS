package com.iems.core.node;

import com.iems.core.energy.EnergyValue;

/**
 * 自定义设备消费接口。
 * <p>
 * 外部模组绕过三大基类、直接接入电网时实现本接口：
 * 调度器传入当前可用能量（SE），设备返回实际消费量（SE）。
 * </p>
 */
public interface IEnergyConsumer {

    /** 消费能量：传入可用能量，返回实际消费量（SE）。 */
    EnergyValue consumePerTick(EnergyValue available);
}
