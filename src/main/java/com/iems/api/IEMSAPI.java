package com.iems.api;

import com.iems.core.grid.Connection;
import com.iems.core.grid.ConnectionType;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridSnapshot;
import com.iems.core.grid.GridTopology;
import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyNode;
import com.iems.diagnostics.GridDiagnostics;
import com.iems.discovery.DiscoveryReport;
import com.iems.discovery.DiscoveryScanner;
import com.iems.discovery.EnergyFlavor;
import com.iems.discovery.IDiscoveryListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.math.BigInteger;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * IEMS API 门面。
 * <p>
 * 以静态方法对外提供设备注册、核心管理、能量/协议查询、功率源注册能力。
 * 外部模组通过 {@code new} 具体设备后调用本类完成接入，无需持有电网引用。
 * </p>
 * <p>
 * <b>M9 模拟化推荐路径</b>：模组加载期 {@link #registerDeviceFactory} 注册工厂，
 * 设备接入时 {@link #attachOrRegisterDevice} / {@link #attachOrRegisterCore}
 * 传入工厂 ID——设备随之持久化到 iems_grid.dat，重启后无区块加载也由工厂重建，
 * 持续模拟运行（BlockEntity 只是外观/视图）。
 * </p>
 */
public final class IEMSAPI {

    private static final DeviceRegistry REGISTRY = DeviceRegistry.instance();
    private static final GridTopology TOPOLOGY = GridTopology.instance();
    private static final Map<String, BigInteger> POWER_INPUTS = new ConcurrentHashMap<>();
    private static final Map<String, BigInteger> POWER_OUTPUTS = new ConcurrentHashMap<>();

    /** 设备工厂注册表（M9 重启重建用）。 */
    private static final Map<String, Supplier<? extends IEnergyNode>> DEVICE_FACTORIES = new ConcurrentHashMap<>();

    /**
     * 服务端主线程任务队列（H-01）。
     * <p>
     * 电网全部状态由服务端 tick 线程独占——外部线程（如 Web 面板 HTTP 线程）
     * 不得直接调用 {@link #setGridActive} 等状态变更方法，否则与 tick 结算并发
     * 读写造成数据竞争。外部线程把变更封装为任务入队，由
     * {@link #drainServerTasks()} 在 tick 线程排空执行。
     * </p>
     */
    private static final Queue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<>();

    private IEMSAPI() {
    }

    // ---------- 服务端主线程任务队列（H-01） ----------

    /**
     * 提交任务到服务端主线程（下一个 tick 排空执行），不等待结果。
     * <p>供外部线程（如 Web 面板 HTTP 线程）安全变更电网状态。</p>
     */
    public static void runOnServerThread(Runnable task) {
        SERVER_TASKS.add(task);
    }

    /**
     * 在服务端主线程执行任务并同步等待结果（外部线程调用）。
     * <p>
     * 任务排队到下一个服务端 tick 执行；调用线程最多阻塞 {@code timeoutMs}。
     * 超时、被中断或执行抛异常时返回 {@code null}（调用方按失败处理）。
     * 严禁在服务端 tick 线程自身调用（会自锁直至超时）。
     * </p>
     */
    public static <T> T callOnServerThread(Supplier<T> action, long timeoutMs) {
        CompletableFuture<T> future = new CompletableFuture<>();
        SERVER_TASKS.add(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException | ExecutionException e) {
            return null;
        }
    }

    /** 排空服务端任务队列（仅服务端 tick 线程调用）。 */
    public static void drainServerTasks() {
        Runnable task;
        while ((task = SERVER_TASKS.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                GridDiagnostics.event("!! server task failed: %s", e.getMessage());
            }
        }
    }

    /** 清空服务端任务队列（服务器停止时调用，防跨世界任务残留）。 */
    public static void clearServerTasks() {
        SERVER_TASKS.clear();
    }

    /**
     * 注册设备工厂（M9，重启重建用）。
     * <p>
     * id 建议 {@code "modid:device_name"}；模组加载期注册一次。
     * 有工厂 ID 的设备才会持久化到 iems_grid.dat 并在重启后重建——
     * 未注册工厂的设备退化为「区块加载时由 BlockEntity 注册」（优雅降级）。
     * </p>
     */
    public static void registerDeviceFactory(String id, Supplier<? extends IEnergyNode> factory) {
        DEVICE_FACTORIES.put(id, factory);
    }

    /** 获取设备工厂（内部存档重建用）。 */
    public static Supplier<? extends IEnergyNode> getDeviceFactory(String id) {
        return DEVICE_FACTORIES.get(id);
    }

    /** 注册设备（兼容入口，无工厂 ID——该设备不持久化，重启后走 BE 回归路径），并触发拓扑重扫。 */
    public static void registerDevice(GlobalPos pos, IEnergyNode node) {
        REGISTRY.register(pos, node);
        TOPOLOGY.rebuild();
    }

    /**
     * 注册或采纳设备（M9 attach 语义，推荐入口）。
     * <p>
     * 注册表已有同位置节点（休眠模拟中/存档已重建）→ <b>采纳既有节点并返回之</b>：
     * 模拟态比 BlockEntity 的构造期实例新，调用方（BE）应以返回值为准持有引用。
     * 否则正常注册 node 并返回 node。带 factoryId 的设备持久化到 iems_grid.dat。
     * </p>
     */
    public static IEnergyNode attachOrRegisterDevice(GlobalPos pos, IEnergyNode node, String factoryId) {
        IEnergyNode result = REGISTRY.attachOrRegister(pos, node, factoryId);
        if (result == node) {
            TOPOLOGY.rebuild(); // 仅新注册改变拓扑；采纳既有节点时拓扑不变
        }
        return result;
    }

    /**
     * 注销设备，清除触及该位置的全部连接（M9 幽灵连接修复），并触发拓扑重扫。
     */
    public static void unregisterDevice(GlobalPos pos) {
        REGISTRY.unregister(pos);
        TOPOLOGY.removeConnectionsAt(pos);
        TOPOLOGY.rebuild();
    }

    /** 注册核心（兼容入口，无工厂 ID，内部保证全局唯一，跨维度仅一个），并触发拓扑重扫。 */
    public static void registerCore(GlobalPos pos, CoreDevice core) {
        REGISTRY.registerCore(pos, core);
        TOPOLOGY.rebuild();
    }

    /**
     * 注册或采纳核心（M9 attach 语义，推荐入口）。
     * <p>
     * 同位置已有核心（存档重建/区块重载）→ 采纳既有核心并返回之（模拟态优先）；
     * 异位置已存在核心 → 拒绝注册并告警（全局唯一语义，返回传入实例）。
     * </p>
     */
    public static CoreDevice attachOrRegisterCore(GlobalPos pos, CoreDevice core, String factoryId) {
        CoreDevice result = REGISTRY.attachOrRegisterCore(pos, core, factoryId);
        if (result == core) {
            // 新注册或被拒（被拒时拓扑不变，多一次幂等 rebuild 无害）
            TOPOLOGY.rebuild();
        }
        return result;
    }

    /**
     * 注销核心（仅当 {@code pos} 与当前注册核心一致时生效——重复放置的
     * 被拒核心方块其注销为无害空操作），清除触及该位置的全部连接
     * （M9 幽灵连接修复），并触发拓扑重扫。
     */
    public static void unregisterCore(GlobalPos pos) {
        REGISTRY.unregisterCore(pos);
        TOPOLOGY.removeConnectionsAt(pos);
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

    /**
     * 手动开启/关闭电网（M8，供指令或外部管理面板调用）。
     * <p>
     * 手动操作优先于协议容量自动关停：清除协议关停标志后，
     * 下一次容量检查会按当前用量重新裁定（如手动重启但仍然超限，会再次自动关停）。
     * </p>
     */
    public static void setGridActive(boolean active) {
        CoreDevice core = REGISTRY.getCore();
        if (core == null) {
            return;
        }
        GridDiagnostics.event("manual grid %s", active ? "RESTART" : "SHUTDOWN");
        core.setGridActive(active);
        REGISTRY.clearProtocolShutdownFlag();
        TOPOLOGY.rebuild();
    }

    /** 当前关停是否由协议容量超限引起（手动关停返回 false）。 */
    public static boolean isProtocolShutdown() {
        return REGISTRY.isProtocolShutdown();
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

    // ---------- NFDS 设备发现（M9，白皮书 §6.3） ----------

    /**
     * NFDS：扫描已加载区块内的外部能量设备（同步一次性调用）。
     * <p>
     * 只发现与分类（不注册/不建连/不换算）——发现的设备不进注册表，
     * 桥接与否由 NFDA（监听器）自行决策。半径钳制 [1,128]，
     * 未加载区块不可见。返回报告同时推送给全部发现监听器。
     * </p>
     */
    public static DiscoveryReport scanForDevices(ServerLevel level, BlockPos center, int radius) {
        return DiscoveryScanner.scan(level, center, radius);
    }

    /** NFDS：注册发现监听器（NFDA / 外部模组接入点，每次扫描后收到完整报告）。 */
    public static void addDiscoveryListener(IDiscoveryListener listener) {
        DiscoveryScanner.addDiscoveryListener(listener);
    }

    /** NFDS：注销发现监听器。 */
    public static void removeDiscoveryListener(IDiscoveryListener listener) {
        DiscoveryScanner.removeDiscoveryListener(listener);
    }

    /**
     * NFDS：注册模组能量体系身份（namespace → 单位/显示名）。
     * 未注册的 namespace 默认 FE、以 namespace 自身为显示名。
     */
    public static void registerEnergyFlavor(EnergyFlavor flavor) {
        DiscoveryScanner.registerEnergyFlavor(flavor);
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
