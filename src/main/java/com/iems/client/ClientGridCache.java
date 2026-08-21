package com.iems.client;

import com.iems.core.grid.Connection;
import com.iems.core.grid.GlobalPos;
import com.iems.network.GridSyncPayload;

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

    /** 核心位置（无核心时为 null）。 */
    private static volatile GlobalPos corePos = null;

    /** 电网关停标志。 */
    private static volatile boolean gridShutdown = false;

    /** 已连接设备数量（服务端同步）。 */
    private static volatile int connectedDeviceCount = 0;

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

    /** 各设备断电进度缓存（0.0~1.0）。键 = GlobalPos。 */
    private static final Map<GlobalPos, Float> DEVICE_POWER_OFF_PROGRESS = new ConcurrentHashMap<>();

    private ClientGridCache() {
    }

    // ------------------------------------------------------------------
    // 写入（网络线程 / 主线程调用）
    // ------------------------------------------------------------------

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

    /** 更新核心位置（网络线程）。 */
    public static void updateCorePos(GlobalPos pos) {
        corePos = pos;
    }

    /** 更新电网状态（网络线程）。 */
    public static void updateGridState(boolean shutdown, int deviceCount) {
        gridShutdown = shutdown;
        connectedDeviceCount = deviceCount;
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

    /** 更新单个设备断电进度（网络线程）。 */
    public static void putDevicePowerOffProgress(GlobalPos pos, float progress) {
        DEVICE_POWER_OFF_PROGRESS.put(pos, progress);
    }

    /** 清空进度缓存（网络线程，拓扑重建时调用）。 */
    public static void clearProgress() {
        DEVICE_POWER_PROGRESS.clear();
        DEVICE_POWER_OFF_PROGRESS.clear();
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
        List<Connection> result = new ArrayList<>();
        for (Connection c : connections) {
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

    /** 设备断电进度（0.0~1.0，缺失返回 0）。 */
    public static float getDevicePowerOffProgress(GlobalPos pos) {
        return DEVICE_POWER_OFF_PROGRESS.getOrDefault(pos, 0.0f);
    }
}
