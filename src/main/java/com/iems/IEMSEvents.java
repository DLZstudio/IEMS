package com.iems;

import com.iems.core.grid.Connection;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridSnapshot;
import com.iems.core.grid.GridTopology;
import com.iems.network.GridSyncPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
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
 *   <li><b>M6 补全</b>：{@link ServerStartedEvent} 时把核心区块常加载回调注册进
 *       {@link DeviceRegistry}（实现层此前仅预留了回调机制，此处落实现实逻辑）。</li>
 *   <li><b>M7 S2</b>：{@link ServerTickEvent} 每 40 tick（2 秒）向所有在线玩家全量推送
 *       电网快照 {@link GridSyncPayload}，驱动客户端激光渲染。</li>
 * </ul>
 */
public final class IEMSEvents {

    /** 电网快照推送间隔（tick）。40 tick = 2 秒，数据量小（几十条连接），全量推送无压力。 */
    private static final int GRID_SYNC_INTERVAL_TICKS = 40;

    private static int tickCounter = 0;

    private IEMSEvents() {
    }

    /** 由 IEMS 主类构造器调用，挂载所有 game 总线监听。 */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(IEMSEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(IEMSEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(IEMSEvents::onServerTick);
    }

    // ------------------------------------------------------------------
    // M6：核心区块常加载（回调注册 + 具体实现）
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        DeviceRegistry.setChunkLoadCallback(pos -> forceChunk(server, pos, true));
        DeviceRegistry.setChunkUnloadCallback(pos -> forceChunk(server, pos, false));
        IEMS.LOGGER.info("IEMS: 核心区块常加载回调已注册");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // 服务器关闭时清理回调，避免持有过期 MinecraftServer 引用
        DeviceRegistry.setChunkLoadCallback(null);
        DeviceRegistry.setChunkUnloadCallback(null);
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
    // M7 S2：电网快照推送
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent event) {
        // ServerTickEvent 每 tick 触发一次（START/END 仅相位区分，无需过滤），
        // 按 tick 计数取模推送，避免每 tick 都发包。
        if (++tickCounter % GRID_SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        pushGridSync(event.getServer());
    }

    /** 组装并推送全量电网快照到所有在线玩家。 */
    private static void pushGridSync(MinecraftServer server) {
        GridSnapshot snap = GridTopology.instance().getSnapshot();

        // 只推送主网可达连接（两端均已接入核心电网）；孤岛/边界连接无电源语义
        List<Connection> reachable = GridTopology.instance().getConnections().stream()
                .filter(c -> snap.isReachable(c.start()) && snap.isReachable(c.end()))
                .toList();

        GridSyncPayload payload = new GridSyncPayload(
                snap.getCorePos(),
                snap.isGridShutdown(),
                snap.getMainNetwork().size(),
                reachable);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
