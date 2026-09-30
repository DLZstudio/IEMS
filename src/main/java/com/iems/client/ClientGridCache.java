package com.iems.client;

import com.iems.core.grid.Connection;
import com.iems.core.grid.GlobalPos;
import com.iems.network.GridSyncPayload;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端电网数据缓存（M7 S3）。
 * <p>
 * 旧版（com.dlzstudio.iems.network.ConnectionData）是静态门面：由网络线程写入、
 * 渲染线程读取，但内部 List 直接暴露引用导致 {@link java.util.ConcurrentModificationException}。
 * 本类针对当前版本（com.iems）重构：
 * </p>
 * <ul>
 *   <li><b>线程安全</b>：volatile 引用 + 防御性拷贝（写入时 {@code List.copyOf}，读取返回不可变视图）。</li>
 *   <li><b>位置即身份</b>：连接/进度键使用当前版本的 {@link GlobalPos}（维度 + 坐标），
 *       替代旧版的 {@code BlockPos}（旧版无法区分跨维度设备）。</li>
 *   <li><b>数据源</b>：所有字段由 {@link GridSyncPayload} 处理器通过 {@link #applySync} 在
 *       客户端主线程填充，渲染线程只读。</li>
 * </ul>
 *
 * @see ConnectionLaserRenderer
 * @see com.iems.network.IEMSNetworking
 */
public final class ClientGridCache {

    /** 颜色渐变速度：每格所需的毫秒数（保留旧版语义）。 */
    public static final long GRADIENT_SPEED_MS_PER_BLOCK = 150L;

    // ------------------------------------------------------------------
    // 连接列表
    // ------------------------------------------------------------------

    /** 当前电网全部连接（跨维度）。不可变快照，由网络线程替换引用。 */
    private static volatile List<Connection> connections = List.of();

    /**
     * 孤岛连接（两端均已注册、但未接入核心电网）。不可变快照。
     * <p>此前该列表在客户端被丢弃，导致「没连核心时拉线成功却看不到激光」，
     * 玩家误判连接失败。现按旧 IEMS 语义以恒定红色渲染（没电）。</p>
     */
    private static volatile List<Connection> islandConnections = List.of();

    /**
     * 适配器桥接连接（{@code ADAPTER_BRIDGE}）：适配器中继器 ↔ 外部 FE 设备。不可变快照。
     * <p>两端均在设备池内，但不属于能量电网连线（不参与「没电变红」语义），
     * 需独立列表渲染为青色桥接带（见 {@link ConnectionLaserRenderer}）。
     * 此前该列表在客户端被丢弃，导致设计中的青色桥接带始终不可见。</p>
     */
    private static volatile List<Connection> adapterBridges = List.of();

    /** 核心位置（无核心时为 null）。 */
    private static volatile GlobalPos corePos = null;

    /** 电网关停标志。 */
    private static volatile boolean gridShutdown = false;

    /** 已连接设备数量（服务端同步）。 */
    private static volatile int connectedDeviceCount = 0;

    // ------------------------------------------------------------------
    // HUD 数据（M8，由 GridSyncPayload 随快照推送）
    // ------------------------------------------------------------------

    /** 核心当前能量 (SE)。 */
    private static volatile BigInteger currentEnergy = BigInteger.ZERO;

    /** 核心能量总容量 (SE)。 */
    private static volatile BigInteger totalCapacity = BigInteger.ZERO;

    /** 已用协议容量。 */
    private static volatile BigInteger protocolUsed = BigInteger.ZERO;

    /** 协议容量上限。 */
    private static volatile BigInteger protocolTotal = BigInteger.ZERO;

    // ------------------------------------------------------------------
    // 四象限功率（M9，由 GridSyncPayload 随快照推送 → HUD 第二行）
    // ------------------------------------------------------------------

    /** 总输出：全注册表消费者需求 Σ + 外部负载 (SE/tick)。 */
    private static volatile BigInteger totalDemandOut = BigInteger.ZERO;

    /** 实际输出：主网实际消耗 + 外部负载 (SE/tick)。 */
    private static volatile BigInteger actualOut = BigInteger.ZERO;

    /** 总输入：核心自发电 + 外部输入 + 主网产出 (SE/tick)。 */
    private static volatile BigInteger totalIn = BigInteger.ZERO;

    /** 当前输入：主网设备产出 (SE/tick)。 */
    private static volatile BigInteger actualIn = BigInteger.ZERO;

    // ------------------------------------------------------------------
    // 动画状态
    // ------------------------------------------------------------------

    /** 是否处于断电动画中。 */
    private static volatile boolean poweringOff = false;

    /** 核心通电开始时间（毫秒，-1 表示从未通电）。 */
    private static volatile long clientCorePowerStartTime = -1L;

    /** 断电动画开始时间（毫秒）。 */
    private static volatile long clientPowerOffStartTime = 0L;

    /** 各设备通电进度缓存（0.0~1.0）。键 = GlobalPos。 */
    private static final Map<GlobalPos, Float> DEVICE_POWER_PROGRESS = new ConcurrentHashMap<>();

    private ClientGridCache() {
    }

    // ------------------------------------------------------------------
    // 写入（网络线程 / 主线程调用）
    // ------------------------------------------------------------------

    /**
     * 清空全部缓存（客户端退出世界/服务器时调用，见 ClientGridLifecycle）。
     * <p>
     * V-02 修复：防止上一个世界的连接/核心数据残留，
     * 在新世界同坐标渲染出幽灵激光。
     * </p>
     */
    public static void clearAll() {
        connections = List.of();
        islandConnections = List.of();
        adapterBridges = List.of();
        corePos = null;
        gridShutdown = false;
        connectedDeviceCount = 0;
        poweringOff = false;
        clientCorePowerStartTime = -1L;
        clientPowerOffStartTime = 0L;
        DEVICE_POWER_PROGRESS.clear();
        currentEnergy = BigInteger.ZERO;
        totalCapacity = BigInteger.ZERO;
        protocolUsed = BigInteger.ZERO;
        protocolTotal = BigInteger.ZERO;
        totalDemandOut = BigInteger.ZERO;
        actualOut = BigInteger.ZERO;
        totalIn = BigInteger.ZERO;
        actualIn = BigInteger.ZERO;
    }

    /**
     * 应用一帧电网快照（客户端主线程，由 GridSyncPayload 处理器调用）。
     * <p>
     * 职责：全量替换连接/核心/状态；把主网设备通电进度映射为 1.0
     * （关停时 0.0，驱动断电变红）；维护通电/断电动画的开始时间戳。
     * </p>
     */
    public static void applySync(GridSyncPayload payload) {
        long now = System.currentTimeMillis();
        updateCorePos(payload.corePos());
        updateGridState(payload.shutdown(), payload.deviceCount());
        updateConnections(payload.connections());
        updateIslandConnections(payload.islandConnections());
        updateAdapterBridges(payload.adapterBridges());
        updateGridStats(payload.currentEnergy(), payload.totalCapacity(),
                payload.protocolUsed(), payload.protocolTotal());
        updateQuadrantStats(payload.totalDemandOut(), payload.actualOut(),
                payload.totalIn(), payload.actualIn());

        // 进度映射：主网可达设备通电进度 = 1.0；电网关停时 = 0.0（断电变红）
        clearProgress();
        if (payload.corePos() != null) {
            float p = payload.shutdown() ? 0.0f : 1.0f;
            putDevicePowerProgress(payload.corePos(), p);
            for (Connection c : payload.connections()) {
                putDevicePowerProgress(c.start(), p);
                putDevicePowerProgress(c.end(), p);
            }
        }

        // 动画状态机：断电开始时间只在首次断电时记录；恢复通电时刷新通电时间
        if (payload.shutdown()) {
            if (!poweringOff) {
                setPoweringOff(true);
                setClientPowerOffStartTime(now);
            }
        } else {
            if (poweringOff || clientCorePowerStartTime < 0) {
                setClientCorePowerStartTime(now);
            }
            setPoweringOff(false);
        }
    }

    /** 全量替换连接列表（网络线程）。 */
    public static void updateConnections(List<Connection> newConnections) {
        connections = List.copyOf(newConnections);
    }

    /** 全量替换孤岛连接列表（网络线程）。 */
    public static void updateIslandConnections(List<Connection> newConnections) {
        islandConnections = List.copyOf(newConnections);
    }

    /** 全量替换适配器桥接连接列表（网络线程）。 */
    public static void updateAdapterBridges(List<Connection> newBridges) {
        adapterBridges = List.copyOf(newBridges);
    }

    /** 更新核心位置（网络线程）。 */
    public static void updateCorePos(GlobalPos pos) {
        corePos = pos;
    }

    /** 更新电网状态（网络线程）。 */
    public static void updateGridState(boolean shutdown, int deviceCount) {
        gridShutdown = shutdown;
        connectedDeviceCount = deviceCount;
    }

    /** 更新 HUD 数值：能量/协议容量（网络线程，M8）。 */
    public static void updateGridStats(BigInteger energy, BigInteger capacity,
                                        BigInteger used, BigInteger limit) {
        currentEnergy = energy == null ? BigInteger.ZERO : energy;
        totalCapacity = capacity == null ? BigInteger.ZERO : capacity;
        protocolUsed = used == null ? BigInteger.ZERO : used;
        protocolTotal = limit == null ? BigInteger.ZERO : limit;
    }

    /**
     * 更新四象限功率（网络线程，M9 HUD 第二行）。
     * <p>顺序：{@code [总输出, 实际输出, 总输入, 当前输入]} (SE/tick)。</p>
     */
    public static void updateQuadrantStats(BigInteger demandOut, BigInteger realOut,
                                           BigInteger in, BigInteger realIn) {
        totalDemandOut = demandOut == null ? BigInteger.ZERO : demandOut;
        actualOut = realOut == null ? BigInteger.ZERO : realOut;
        totalIn = in == null ? BigInteger.ZERO : in;
        actualIn = realIn == null ? BigInteger.ZERO : realIn;
    }

    /** 设置断电动画状态（网络线程）。 */
    public static void setPoweringOff(boolean off) {
        poweringOff = off;
    }

    /** 记录核心通电开始时间（网络线程）。 */
    public static void setClientCorePowerStartTime(long time) {
        clientCorePowerStartTime = time;
    }

    /** 记录断电动画开始时间（网络线程）。 */
    public static void setClientPowerOffStartTime(long time) {
        clientPowerOffStartTime = time;
    }

    /** 更新单个设备通电进度（网络线程）。 */
    public static void putDevicePowerProgress(GlobalPos pos, float progress) {
        DEVICE_POWER_PROGRESS.put(pos, progress);
    }

    /** 清空进度缓存（网络线程，拓扑重建时调用）。 */
    public static void clearProgress() {
        DEVICE_POWER_PROGRESS.clear();
    }

    // ------------------------------------------------------------------
    // 读取（渲染线程调用，全部返回不可变数据）
    // ------------------------------------------------------------------

    /** 全部连接（不可变快照）。 */
    public static List<Connection> getConnections() {
        return connections;
    }

    /**
     * 按维度过滤连接（防御性拷贝，渲染线程遍历安全）。
     * 返回"至少一端位于该维度"的连接：跨维度桥（DIMENSION_BRIDGE）的一端
     * 在当前维度时也会返回，由渲染器决定如何绘制该端的门光晕。
     */
    public static List<Connection> getConnections(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return filterByDimension(connections, dimension);
    }

    /**
     * 孤岛连接按维度过滤：两端均已注册、但未接入核心电网的连接。
     * <p>调用契约与 {@link #getConnections(net.minecraft.resources.ResourceKey)} 一致。</p>
     */
    public static List<Connection> getIslandConnections(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return filterByDimension(islandConnections, dimension);
    }

    /**
     * 适配器桥接连接按维度过滤（两端均在设备池内，但非电网连线）。
     * <p>调用契约与 {@link #getConnections(net.minecraft.resources.ResourceKey)} 一致。</p>
     */
    public static List<Connection> getAdapterBridges(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return filterByDimension(adapterBridges, dimension);
    }

    /** 按维度过滤：至少一端位于该维度（含跨维度桥的当前维度端）。 */
    private static List<Connection> filterByDimension(
            List<Connection> source,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        List<Connection> result = new ArrayList<>();
        for (Connection c : source) {
            if (c.start().dimension().equals(dimension) || c.end().dimension().equals(dimension)) {
                result.add(c);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** 核心位置（可能为 null）。 */
    public static GlobalPos getCorePos() {
        return corePos;
    }

    /** 是否有核心。 */
    public static boolean hasCore() {
        return corePos != null;
    }

    /** 电网是否关停。 */
    public static boolean isGridShutdown() {
        return gridShutdown;
    }

    /** 已连接设备数量。 */
    public static int getConnectedDeviceCount() {
        return connectedDeviceCount;
    }

    /** 核心当前能量 (SE)（HUD 用，M8）。 */
    public static BigInteger getCurrentEnergy() {
        return currentEnergy;
    }

    /** 核心能量总容量 (SE)（HUD 用，M8）。 */
    public static BigInteger getTotalCapacity() {
        return totalCapacity;
    }

    /** 已用协议容量（HUD 用，M8）。 */
    public static BigInteger getProtocolUsed() {
        return protocolUsed;
    }

    /** 协议容量上限（HUD 用，M8）。 */
    public static BigInteger getProtocolTotal() {
        return protocolTotal;
    }

    /** 总输出：全注册表消费者需求 Σ + 外部负载 (SE/tick)（HUD 第二行，M9）。 */
    public static BigInteger getTotalDemandOut() {
        return totalDemandOut;
    }

    /** 实际输出：主网实际消耗 + 外部负载 (SE/tick)（HUD 第二行，M9）。 */
    public static BigInteger getActualOut() {
        return actualOut;
    }

    /** 总输入：核心自发电 + 外部输入 + 主网产出 (SE/tick)（HUD 第二行，M9）。 */
    public static BigInteger getTotalIn() {
        return totalIn;
    }

    /** 当前输入：主网设备产出 (SE/tick)（HUD 第二行，M9）。 */
    public static BigInteger getActualIn() {
        return actualIn;
    }

    /** 是否处于断电动画中。 */
    public static boolean isPoweringOff() {
        return poweringOff;
    }

    /** 核心通电开始时间（毫秒）。 */
    public static long getClientCorePowerStartTime() {
        return clientCorePowerStartTime;
    }

    /** 断电动画开始时间（毫秒）。 */
    public static long getClientPowerOffStartTime() {
        return clientPowerOffStartTime;
    }

    /** 设备通电进度（0.0~1.0，缺失返回 0）。 */
    public static float getDevicePowerProgress(GlobalPos pos) {
        return DEVICE_POWER_PROGRESS.getOrDefault(pos, 0.0f);
    }
}
