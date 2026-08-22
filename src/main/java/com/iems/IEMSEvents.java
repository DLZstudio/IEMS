package com.iems;

import com.iems.api.IEMSAPI;
import com.iems.core.dispatcher.EnergyDispatcher;
import com.iems.core.grid.Connection;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridSavedData;
import com.iems.core.grid.GridSnapshot;
import com.iems.core.grid.GridTopology;
import com.iems.network.GridSyncPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * IEMS 服务端事件监听（game 总线）。
 * <p>
 * 统一挂载在 {@link NeoForge#EVENT_BUS} 上的服务端逻辑：
 * </p>
 * <ul>
 *   <li><b>M6</b>：{@link ServerStartedEvent} 时把核心区块常加载回调注册进
 *       {@link DeviceRegistry}（实现层此前仅预留了回调机制，此处落实现实逻辑）。</li>
 *   <li><b>V-04 持久化</b>：服务器启动时从 {@link GridSavedData}（iems_grid.dat）
 *       恢复电网连接；连接增删经脏回调自动落盘。</li>
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
    // M6：核心区块常加载（回调注册）+ V-04：连接持久化加载
    // ------------------------------------------------------------------

    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        DeviceRegistry.setChunkLoadCallback(pos -> forceChunk(server, pos, true));
        DeviceRegistry.setChunkUnloadCallback(pos -> forceChunk(server, pos, false));
        loadPersistedGrid(server);
        IEMS.LOGGER.info("IEMS: 核心区块常加载回调已注册，电网连接已从存档恢复");
    }

    /** 挂载 GridSavedData 并把存档连接注入拓扑（V-04）。 */
    private static void loadPersistedGrid(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        GridSavedData data = GridSavedData.attach(overworld);
        GridTopology.instance().loadConnections(data.getConnections());
        // 连接增删 → 标记存档脏；保存时实时拉取拓扑当前连接
        GridTopology.setConnectionsDirtyCallback(data::markDirty);
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        // 存档保存发生在本事件之后，此处只清理回调；
        // 严禁在此清空电网数据，否则保存时会把空数据写盘。
        DeviceRegistry.setChunkLoadCallback(null);
        DeviceRegistry.setChunkUnloadCallback(null);
    }

    // ------------------------------------------------------------------
    // V-02：生命周期清理（服务器完全停止，存档已保存）
    // ------------------------------------------------------------------

    public static void onServerStopped(ServerStoppedEvent event) {
        GridTopology.setConnectionsDirtyCallback(null);
        GridTopology.instance().clearAll();
        DeviceRegistry.instance().clearAll();
        IEMSAPI.getPowerInputs().clear();
        IEMSAPI.getPowerOutputs().clear();
        EnergyDispatcher.resetTickGuard();
        lastSyncTick = Integer.MIN_VALUE;
    }

    private static void forceChunk(MinecraftServer server, GlobalPos pos, boolean force) {
        ServerLevel level = server.getLevel(pos.dimension());
        if (level == null) {
            return;
        }
        ChunkPos chunk = new ChunkPos(pos.pos());
        level.setChunkForced(chunk.x, chunk.z, force);
    }

    // ------------------------------------------------------------------
    // V-05：每 Tick 调度驱动 + M7 S2：电网快照推送
    // ------------------------------------------------------------------

    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();

        // 内置能量调度（同一 tick 幂等，外部重复调用不会重复结算）
        EnergyDispatcher.dispatchAtTick(tick);

        // 快照推送：按服务器 tick 计数取模，守卫去重
        if (tick != lastSyncTick && tick % GRID_SYNC_INTERVAL_TICKS == 0) {
            lastSyncTick = tick;
            pushGridSync(server);
        }
    }

    /** 组装并推送全量电网快照到所有在线玩家。 */
    private static void pushGridSync(MinecraftServer server) {
        GridSnapshot snap = GridTopology.instance().getSnapshot();

        // 只推送主网可达连接（两端均已接入核心电网）；孤岛/边界连接无电源语义
        List<Connection> reachable = GridTopology.instance().getConnections().stream()
                .filter(c -> snap.isReachable(c.start()) && snap.isReachable(c.end()))
                .toList();

        // HUD 数据（M8）：能量与协议容量随快照一并推送（无核心时为 0）
        GridSyncPayload payload = new GridSyncPayload(
                snap.getCorePos(),
                snap.isGridShutdown(),
                snap.getMainNetwork().size(),
                reachable,
                IEMSAPI.getCurrentEnergy(),
                IEMSAPI.getTotalCapacity(),
                IEMSAPI.getProtocolUsed(),
                IEMSAPI.getProtocolTotal());

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
