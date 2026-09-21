package com.iems;

import com.iems.adapter.FeBridgeTicker;
import com.iems.api.IEMSAPI;
import com.iems.core.dispatcher.EnergyDispatcher;
import com.iems.core.grid.Connection;
import com.iems.core.grid.ConnectionType;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridSavedData;
import com.iems.core.grid.GridSnapshot;
import com.iems.core.grid.GridTopology;
import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyNode;
import com.iems.diagnostics.GridDiagnostics;
import com.iems.network.GridSyncPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/**
 * IEMS 服务端事件监听（game 总线）。
 * <p>
 * 统一挂载在 {@link NeoForge#EVENT_BUS} 上的服务端逻辑：
 * </p>
 * <ul>
 *   <li><b>V-04 持久化</b>：服务器启动时从 {@link GridSavedData}（iems_grid.dat）
 *       恢复电网连接；连接增删经脏回调自动落盘。</li>
 *   <li><b>M9 模拟化</b>：服务器启动时经设备工厂从存档重建全部休眠设备与核心
 *       （无区块加载也满血复活，持续被调度器模拟）——核心区块常加载（M6）就此退役。</li>
 *   <li><b>V-05 调度驱动</b>：每 Tick 调用 {@link EnergyDispatcher#dispatchAtTick(int)}
 *       执行能量结算（同一 tick 幂等）。</li>
 *   <li><b>M7 S2</b>：每 40 tick（2 秒）向所有在线玩家全量推送电网快照
 *       {@link GridSyncPayload}，驱动客户端激光渲染。</li>
 *   <li><b>V-02 生命周期</b>：{@link ServerStoppedEvent}（存档已保存完毕）后
 *       清空全部静态单例状态，防止单机切世界数据残留。</li>
 * </ul>
 */
public final class IEMSEvents {

    /** 电网快照推送间隔（tick）。40 tick = 2 秒，数据量小（几十条连接），全量推送无压力。 */
    private static final int GRID_SYNC_INTERVAL_TICKS = 40;

    /** 上次推送快照的服务器 tick（V-10：改用服务器 tick 计数，替代静态累加器）。 */
    private static int lastSyncTick = Integer.MIN_VALUE;

    /**
     * 上次推送快照时的拓扑版本号（M9 即时反馈）。
     * <p>连接增删/核心变更使 GridTopology 版本号递增，本 tick 立即推送——
     * 拉线建连/拆设备断连的激光反馈从最长 2 秒降至 ≤1 tick。</p>
     */
    private static int lastSyncedTopologyVersion = -1;

    private IEMSEvents() {
    }

    /** 由 IEMS 主类构造器调用，挂载所有 game 总线监听。 */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(IEMSEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(IEMSEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(IEMSEvents::onServerStopped);
        NeoForge.EVENT_BUS.addListener(IEMSEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(IEMSCommands::onRegisterCommands);
    }

    // ------------------------------------------------------------------
    // V-04：连接持久化加载 + M9：设备模拟化重建
    // ------------------------------------------------------------------

    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        loadPersistedGrid(server);
        rehydrateDevices();
        GridDiagnostics.open("level=" + server.getWorldData().getLevelName()
                + " players=" + server.getPlayerList().getPlayerCount());
        GridDiagnostics.event("server started: restored %d connections from save",
                GridTopology.instance().getConnections().size());
        IEMS.LOGGER.info("IEMS: 电网连接已从存档恢复，休眠设备重建完成");
    }

    /** 挂载 GridSavedData 并把存档连接注入拓扑（V-04）。 */
    private static void loadPersistedGrid(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        GridSavedData data = GridSavedData.attach(overworld);
        GridTopology.instance().loadConnections(data.getConnections());
        // 连接增删 → 标记存档脏；保存时实时拉取拓扑当前连接
        GridTopology.setConnectionsDirtyCallback(data::markDirty);
    }

    /**
     * M9：从存档重建全部休眠设备与核心（区块未加载也满血复活）。
     * <p>
     * 遍历 iems_grid.dat 的 Devices 列表 → 设备工厂创建 → restoreState →
     * silentRegister（不触发自动重扫，连接已持久化）。核心条目含 Factory
     * （新格式）时同法重建并接管注册表槽位；旧格式核心（无 Factory）不在此
     * 重建，由其 BlockEntity 注册路径 + B-2 tryRestoreCore 恢复。
     * 工厂缺失（模组卸载/降级）→ 跳过该设备并告警，不崩服。
     * </p>
     */
    private static void rehydrateDevices() {
        GridSavedData data = GridSavedData.active();
        if (data == null) {
            return;
        }
        int restored = 0;
        for (CompoundTag deviceTag : data.getSavedDevices()) {
            try {
                GlobalPos pos = GridSavedData.readPos(deviceTag, "Pos");
                String factoryId = deviceTag.getString("Factory");
                Supplier<? extends IEnergyNode> factory = IEMSAPI.getDeviceFactory(factoryId);
                if (factory == null) {
                    GridDiagnostics.event("!! factory missing: %s @ %s", factoryId, pos);
                    IEMS.LOGGER.warn("IEMS: 设备工厂缺失（模组已卸载？），跳过重建 {} @ {}", factoryId, pos);
                    continue;
                }
                IEnergyNode node = factory.get();
                node.restoreState(deviceTag.getCompound("Data"));
                DeviceRegistry.instance().silentRegister(pos, node, factoryId);
                restored++;
            } catch (Exception e) {
                GridDiagnostics.event("!! rehydrate failed: %s", e.getMessage());
                IEMS.LOGGER.warn("IEMS: 设备存档重建失败（{}）", e.getMessage());
            }
        }

        CompoundTag coreTag = data.getSavedCoreState();
        if (coreTag != null && coreTag.contains("Factory")) {
            rehydrateCore(coreTag);
        }
        if (restored > 0 || (coreTag != null && coreTag.contains("Factory"))) {
            GridTopology.instance().rebuild();
        }
        IEMS.LOGGER.info("IEMS: 从存档重建 {} 台休眠设备", restored);
    }

    /** M9：核心的存档重建（新格式条目含 Factory 字段时调用）。 */
    private static void rehydrateCore(CompoundTag coreTag) {
        try {
            GlobalPos pos = GridSavedData.readPos(coreTag, GridSavedData.KEY_CORE_POS);
            String factoryId = coreTag.getString("Factory");
            Supplier<? extends IEnergyNode> factory = IEMSAPI.getDeviceFactory(factoryId);
            if (factory == null) {
                GridDiagnostics.event("!! core factory missing: %s @ %s", factoryId, pos);
                IEMS.LOGGER.warn("IEMS: 核心工厂缺失（{}），核心改由方块实体注册路径恢复", factoryId);
                return;
            }
            CoreDevice core = (CoreDevice) factory.get();
            core.restoreState(coreTag);
            DeviceRegistry.instance().rehydrateCore(pos, core, factoryId);
        } catch (Exception e) {
            GridDiagnostics.event("!! core rehydrate failed: %s", e.getMessage());
            IEMS.LOGGER.warn("IEMS: 核心存档重建失败（{}），核心改由方块实体注册路径恢复", e.getMessage());
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        // 存档保存发生在本事件之后。严禁在此清空电网数据，否则保存时会把空数据写盘。
        // M9：核心区块常加载已退役——设备模拟化后核心无需区块加载即可持续结算，
        // 存档中的 Devices/Core 条目由 save() 实时拉取注册表全量写盘。
    }

    // ------------------------------------------------------------------
    // V-02：生命周期清理（服务器完全停止，存档已保存）
    // ------------------------------------------------------------------

    public static void onServerStopped(ServerStoppedEvent event) {
        GridTopology.setConnectionsDirtyCallback(null);
        GridTopology.instance().clearAll();
        DeviceRegistry.instance().clearAll();
        GridSavedData.clearActive();
        IEMSAPI.getPowerInputs().clear();
        IEMSAPI.getPowerOutputs().clear();
        EnergyDispatcher.resetTickGuard();
        lastSyncTick = Integer.MIN_VALUE;
        lastSyncedTopologyVersion = -1;
        GridDiagnostics.event("server stopped: state cleared");
        GridDiagnostics.close();
    }

    // ------------------------------------------------------------------
    // V-05：每 Tick 调度驱动 + M7 S2：电网快照推送
    // ------------------------------------------------------------------

    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();

        // DA 驱动（M9+，内建于自动中继器）：抽取/测余量/推送 + 孤儿清扫必须在
        // 结算前（数值供本 tick 消费），目标 diff 同步每 40 tick 一次
        FeBridgeTicker.tick(server);
        FeBridgeTicker.tickRescan(server, tick);

        // 内置能量调度（同一 tick 幂等，外部重复调用不会重复结算）
        EnergyDispatcher.dispatchAtTick(tick);

        // 自动连接扫描（M8.2）：消费设备注册时入队的扫描任务
        IemsAutoConnector.tick(server);

        // 诊断：每 tick 状态行（可用 /iems debug 开关）。
        // 先查开关再拼串：状态行含多个 BigInteger.toString，关闭时逐 tick 拼串是纯浪费
        if (GridDiagnostics.isTickLogEnabled()) {
            GridDiagnostics.tick(buildTickLine(tick));
        }

        // 快照推送：周期帧（40 tick 状态对齐）+ 拓扑版本驱动的事件帧
        // （连接增删/核心变更 ≤1 tick 即时反馈，见 lastSyncedTopologyVersion）
        int topologyVersion = GridTopology.instance().topologyVersion();
        if (tick != lastSyncTick
                && (tick % GRID_SYNC_INTERVAL_TICKS == 0 || topologyVersion != lastSyncedTopologyVersion)) {
            lastSyncTick = tick;
            lastSyncedTopologyVersion = topologyVersion;
            pushGridSync(server);
        }
    }

    /**
     * 组装每 tick 状态行（M8.2 诊断）：设备池/核心/能量/协议/拓扑/调度器截面。
     * <p>写入 logs/iems/grid.log 的 [T] 行，供排查连接功能问题。</p>
     */
    private static String buildTickLine(int tick) {
        DeviceRegistry registry = IEMSAPI.getRegistry();
        GridSnapshot snap = IEMSAPI.getSnapshot();
        CoreDevice core = registry.getCore();
        return "t=" + tick
                + " dev=" + registry.size()
                + " core=" + (core == null ? "none" : registry.getCorePos())
                + " E=" + IEMSAPI.getCurrentEnergy() + "/" + IEMSAPI.getTotalCapacity()
                + " proto=" + IEMSAPI.getProtocolUsed() + "/" + IEMSAPI.getProtocolTotal()
                + (snap.isGridShutdown() ? " [SHUTDOWN]" : "")
                + " conn=" + GridTopology.instance().getConnections().size()
                + " main=" + snap.getMainNetwork().size()
                + " orphan=" + snap.getOrphanNetworks().size()
                + " pend=" + snap.getPendingConnections().size()
                + " autoQ=" + registry.getPendingAutoScanCount()
                + " dispatch=" + EnergyDispatcher.getLastTickStats();
    }

    /** 组装并推送全量电网快照到所有在线玩家。 */
    private static void pushGridSync(MinecraftServer server) {
        GridSyncPayload payload = buildGridSyncPayload();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * 组装当前电网的全量快照包（推送与单发共用）。
     * <p>单发场景：C2S 建连请求被拒时向请求者回推（清掉客户端拉线
     * 预测激光的收尾，见 {@code IEMSNetworking.reject}）。</p>
     */
    public static GridSyncPayload buildGridSyncPayload() {
        GridSnapshot snap = GridTopology.instance().getSnapshot();
        DeviceRegistry registry = IEMSAPI.getRegistry();

        // 主网可达连接（两端均已接入核心电网）
        List<Connection> reachable = GridTopology.instance().getConnections().stream()
                .filter(c -> snap.isReachable(c.start()) && snap.isReachable(c.end()))
                .toList();

        // 孤岛连接（M8.2）：两端均已注册但未接入核心电网 → 灰红激光可见。
        // 边界连接（任一端未注册）仍不推送。
        List<Connection> islands = GridTopology.instance().getConnections().stream()
                .filter(c -> registry.get(c.start()) != null && registry.get(c.end()) != null
                        && !(snap.isReachable(c.start()) && snap.isReachable(c.end())))
                .toList();

        // NFDA 桥接连接（M9）：外部端点不在设备池，独立列表推送 → 青色桥接带
        List<Connection> bridges = GridTopology.instance().getConnections().stream()
                .filter(c -> c.type() == ConnectionType.ADAPTER_BRIDGE)
                .toList();

        // HUD 数据（M8）：能量与协议容量随快照一并推送（无核心时为 0）；
        // 四象限功率（M9）：上一 tick 调度器统计（总/实际 × 输入/输出口径）
        java.math.BigInteger[] quadrant = EnergyDispatcher.getQuadrantStats();
        return new GridSyncPayload(
                snap.getCorePos(),
                snap.isGridShutdown(),
                snap.getMainNetwork().size(),
                reachable,
                islands,
                bridges,
                snap.getDeviceDepths(),
                snap.getMaxDepth(),
                IEMSAPI.getCurrentEnergy(),
                IEMSAPI.getTotalCapacity(),
                IEMSAPI.getProtocolUsed(),
                IEMSAPI.getProtocolTotal(),
                quadrant[0], quadrant[1], quadrant[2], quadrant[3]);
    }
}
