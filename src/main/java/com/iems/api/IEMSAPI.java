package com.iems.api;

import com.iems.core.grid.Connection;
import com.iems.core.grid.ConnectionType;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridSnapshot;
import com.iems.core.grid.GridTopology;
import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyNode;
import net.minecraft.world.phys.Vec3;

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

    private static final DeviceRegistry REGISTRY = DeviceRegistry.instance();
    private static final GridTopology TOPOLOGY = GridTopology.instance();
    private static final Map<String, BigInteger> POWER_INPUTS = new ConcurrentHashMap<>();
    private static final Map<String, BigInteger> POWER_OUTPUTS = new ConcurrentHashMap<>();

    private IEMSAPI() {
    }

    /** 注册设备（位置即身份，以 GlobalPos 为键），并触发拓扑重扫。 */
    public static void registerDevice(GlobalPos pos, IEnergyNode node) {
        REGISTRY.register(pos, node);
        TOPOLOGY.rebuild();
    }

    /** 注销设备，并触发拓扑重扫。 */
    public static void unregisterDevice(GlobalPos pos) {
        REGISTRY.unregister(pos);
        TOPOLOGY.rebuild();
    }

    /** 注册核心（内部保证全局唯一，跨维度仅一个），并触发拓扑重扫。 */
    public static void registerCore(GlobalPos pos, CoreDevice core) {
        REGISTRY.registerCore(pos, core);
        TOPOLOGY.rebuild();
    }

    /** 注销核心，并触发拓扑重扫。 */
    public static void unregisterCore() {
        REGISTRY.unregisterCore();
        TOPOLOGY.rebuild();
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

    // ---------- 连接管理（M2 拓扑） ----------

    /**
     * 添加一条连接（同维度中继 RELAY_TO_RELAY / 跨维度桥接 DIMENSION_BRIDGE），触发拓扑重扫。
     * <p>
     * 锚点自动取自注册表设备 {@link IEnergyNode#getAnchorOffset()}；
     * 设备未注册时回退方块中心 {@code (0.5, 0.5, 0.5)}。
     * </p>
     */
    public static void addConnection(GlobalPos a, GlobalPos b, ConnectionType type) {
        Vec3 anchorA = anchorOf(a);
        Vec3 anchorB = anchorOf(b);
        TOPOLOGY.addConnection(Connection.of(a, b, type,
                (float) anchorA.x, (float) anchorA.y, (float) anchorA.z,
                (float) anchorB.x, (float) anchorB.y, (float) anchorB.z));
    }

    /**
     * 添加一条连接并显式指定两端锚点（覆盖注册表设备锚点）。
     */
    public static void addConnection(GlobalPos a, GlobalPos b, ConnectionType type,
                                     Vec3 anchorA, Vec3 anchorB) {
        TOPOLOGY.addConnection(Connection.of(a, b, type,
                (float) anchorA.x, (float) anchorA.y, (float) anchorA.z,
                (float) anchorB.x, (float) anchorB.y, (float) anchorB.z));
    }

    /** 移除一条连接（身份判定忽略锚点），触发拓扑重扫。 */
    public static void removeConnection(GlobalPos a, GlobalPos b, ConnectionType type) {
        TOPOLOGY.removeConnection(Connection.of(a, b, type));
    }

    /** 查询设备锚点；未注册回退方块中心。 */
    private static Vec3 anchorOf(GlobalPos pos) {
        IEnergyNode node = REGISTRY.get(pos);
        return node != null ? node.getAnchorOffset() : new Vec3(0.5, 0.5, 0.5);
    }

    /** 当前电网拓扑快照（只读）。 */
    public static GridSnapshot getSnapshot() {
        return TOPOLOGY.getSnapshot();
    }

    /** 指定位置是否已接入电网（位于核心可达网络 mainNetwork）。 */
    public static boolean isDeviceConnected(GlobalPos pos) {
        return TOPOLOGY.isReachable(pos);
    }

    /** 强制触发完整 BFS 重扫。 */
    public static void forceRescan() {
        TOPOLOGY.rebuild();
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
