package com.iems.api;

import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyNode;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IEMS API 门面。
 * <p>
 * 以静态方法对外提供设备注册、核心管理、能量/协议查询、功率源注册能力。
 * 外部模组通过 {@code new} 具体设备后调用本类完成接入，无需持有电网引用。
 * </p>
 */
public final class IEMSAPI {

    private static final DeviceRegistry REGISTRY = new DeviceRegistry();
    private static final Map<String, BigInteger> POWER_INPUTS = new ConcurrentHashMap<>();
    private static final Map<String, BigInteger> POWER_OUTPUTS = new ConcurrentHashMap<>();

    private IEMSAPI() {
    }

    /** 注册设备（位置即身份，以 GlobalPos 为键）。 */
    public static void registerDevice(GlobalPos pos, IEnergyNode node) {
        REGISTRY.register(pos, node);
    }

    /** 注销设备。 */
    public static void unregisterDevice(GlobalPos pos) {
        REGISTRY.unregister(pos);
    }

    /** 注册核心（内部保证全局唯一，跨维度仅一个）。 */
    public static void registerCore(GlobalPos pos, CoreDevice core) {
        REGISTRY.registerCore(pos, core);
    }

    /** 注销核心。 */
    public static void unregisterCore() {
        REGISTRY.unregisterCore();
    }

    /** 电网当前总能量 (SE)。 */
    public static BigInteger getCurrentEnergy() {
        CoreDevice core = REGISTRY.getCore();
        return core == null ? BigInteger.ZERO : core.getCurrentEnergy();
    }

    /** 电网总容量 (SE)。 */
    public static BigInteger getTotalCapacity() {
        CoreDevice core = REGISTRY.getCore();
        return core == null ? BigInteger.ZERO : core.getEnergyCapacity();
    }

    /** 已用协议容量。 */
    public static BigInteger getProtocolUsed() {
        return REGISTRY.getProtocolUsed();
    }

    /** 协议容量上限。 */
    public static BigInteger getProtocolTotal() {
        CoreDevice core = REGISTRY.getCore();
        return core == null ? BigInteger.ZERO : core.getProtocolLimit();
    }

    /** 注册外部功率源（输入，速率 SE/tick）。 */
    public static void registerPowerInput(String id, BigInteger rate) {
        POWER_INPUTS.put(id, rate);
    }

    public static void unregisterPowerInput(String id) {
        POWER_INPUTS.remove(id);
    }

    /** 注册外部功率输出（负载，速率 SE/tick）。 */
    public static void registerPowerOutput(String id, BigInteger rate) {
        POWER_OUTPUTS.put(id, rate);
    }

    public static void unregisterPowerOutput(String id) {
        POWER_OUTPUTS.remove(id);
    }

    /** 内部 API：供 GridTopology / EnergyDispatcher 等内部模块访问设备池。 */
    public static DeviceRegistry getRegistry() {
        return REGISTRY;
    }

    /** 内部 API：功率输入表。 */
    public static Map<String, BigInteger> getPowerInputs() {
        return POWER_INPUTS;
    }

    /** 内部 API：功率输出表。 */
    public static Map<String, BigInteger> getPowerOutputs() {
        return POWER_OUTPUTS;
    }
}
