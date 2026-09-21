package com.iems.network;

import com.iems.IEMS;
import com.iems.IEMSEvents;
import com.iems.api.IEMSAPI;
import com.iems.api.IIemsInteractable;
import com.iems.client.ClientGridCache;
import com.iems.core.grid.ConnectionType;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridTopology;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.TransferDevice;
import com.iems.diagnostics.GridDiagnostics;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * IEMS 网络层（M7 S1/S2）。
 * <p>
 * 在 {@link RegisterPayloadHandlersEvent} 中注册两个 payload：
 * </p>
 * <ul>
 *   <li>{@link GridSyncPayload}（服务端 → 客户端，playToClient）：电网快照全量推送，
 *       客户端处理器切主线程后调用 {@link ClientGridCache#applySync} 填充渲染缓存；</li>
 *   <li>{@link ConnectionRequestPayload}（客户端 → 服务端，playToServer，M8.1）：
 *       手动拉线连接请求，服务端二次校验（端点注册/同维度/距离）后建立连接。</li>
 * </ul>
 * <p>
 * 注册入口由 IEMS 主类构造器调用 {@link #register(IEventBus)} 完成
 * （mod 总线监听器在此手动挂载，不依赖注解扫描）。
 * </p>
 */
public final class IEMSNetworking {

    private IEMSNetworking() {
    }

    /**
     * 由 IEMS 主类构造器调用，注册网络包（mod 总线事件）。
     */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(IEMSNetworking::onRegisterPayloadHandlers);
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(IEMS.MODID)
                .versioned("6") // M9：GridSyncPayload v6 增加主网设备 BFS 深度表（波接力；v5 桥接列表、v3 孤岛、v4 四象限）
                .optional();
        // 服务端 → 客户端：电网快照
        registrar.playToClient(
                GridSyncPayload.TYPE,
                GridSyncPayload.STREAM_CODEC,
                IEMSNetworking::handleGridSyncOnClient);
        // 客户端 → 服务端：手动拉线连接请求（M8.1）
        registrar.playToServer(
                ConnectionRequestPayload.TYPE,
                ConnectionRequestPayload.STREAM_CODEC,
                IEMSNetworking::handleConnectionRequest);
    }

    /** 客户端处理器：切到主线程填充渲染缓存。 */
    private static void handleGridSyncOnClient(final GridSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientGridCache.applySync(payload));
    }

    /**
     * 服务端处理器：二次校验拉线连接请求后建立连接。
     * <p>
     * 校验项（客户端预检不作为信任依据）：
     * 两端点均已注册设备、起点是可交互端点、同维度、距离不超过起点声明值。
     * 任何一项不满足即拒绝并回执玩家。
     * </p>
     */
    private static void handleConnectionRequest(final ConnectionRequestPayload payload, final IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            DeviceRegistry registry = DeviceRegistry.instance();

            if (payload.start().equals(payload.end())) {
                GridDiagnostics.event("C2S reject: same endpoint %s (by %s)",
                        payload.start(), player.getGameProfile().getName());
                return;
            }
            if (!payload.start().dimension().equals(payload.end().dimension())) {
                GridDiagnostics.event("C2S reject: cross-dimension %s -> %s (by %s)",
                        payload.start(), payload.end(), player.getGameProfile().getName());
                reject(player, "§c跨维度连接需使用维度门");
                return;
            }
            // 端点注册校验：核心不在设备池（由 corePos 单独索引），
            // 核心位置视同已注册端点——否则核心↔中继的手动拉线被误判拒收
            // （P0 修复：连接模式下点击第二个设备无效的根因）
            GlobalPos corePos = registry.getCorePos();
            boolean startRegistered = registry.get(payload.start()) != null
                    || payload.start().equals(corePos);
            boolean endRegistered = registry.get(payload.end()) != null
                    || payload.end().equals(corePos);
            if (!startRegistered || !endRegistered) {
                GridDiagnostics.event("C2S reject: endpoint not registered %s -> %s (by %s)",
                        payload.start(), payload.end(), player.getGameProfile().getName());
                reject(player, "§c目标设备未接入电网");
                return;
            }

            // M9 裁定：不支持自动连接的传输设备不得手动连接用电/发电器。
            // 设备入网通道 = 自动中继器自动连接 / 核心手动直连（核心端点不在
            // 注册表，get 为 null，天然不触发本规则）
            IEnergyNode startNode = registry.get(payload.start());
            IEnergyNode endNode = registry.get(payload.end());
            if (isNonAutoRelayDevicePair(startNode, endNode)) {
                GridDiagnostics.event("C2S reject: non-auto relay -> device %s -> %s (by %s)",
                        payload.start(), payload.end(), player.getGameProfile().getName());
                reject(player, "§c该中继器不支持自动连接，无法手动连接用电/发电器（设备经自动中继器或核心入网）");
                return;
            }

            // 起点方块实体必须声明可交互（距离上限以其声明为准）。
            // 必须按起点自身维度解析：此前误用玩家所在维度，伪造包可用
            // 异维度坐标在玩家维度错误命中可交互设备（P2 修复）
            ServerLevel startLevel = player.serverLevel().getServer().getLevel(payload.start().dimension());
            BlockEntity startBe = startLevel == null ? null : startLevel.getBlockEntity(payload.start().pos());
            if (!(startBe instanceof IIemsInteractable startDevice) || !startDevice.canBeConnectionEndpoint()) {
                GridDiagnostics.event("C2S reject: start not interactable %s (by %s)",
                        payload.start(), player.getGameProfile().getName());
                reject(player, "§c起点设备不支持手动连接");
                return;
            }
            double distance = Math.sqrt(payload.start().pos().distSqr(payload.end().pos()));
            if (distance > startDevice.getMaxConnectionDistance()) {
                GridDiagnostics.event("C2S reject: over distance %.0fm > %dm %s -> %s (by %s)",
                        distance, startDevice.getMaxConnectionDistance(),
                        payload.start(), payload.end(), player.getGameProfile().getName());
                reject(player, "§c超出最大连接距离 " + startDevice.getMaxConnectionDistance() + "m");
                return;
            }

            // 重复拉线查重：身份 = 两端 + 类型（锚点不参与），默认锚点即可匹配
            if (GridTopology.instance().hasConnection(payload.start(), payload.end(), ConnectionType.RELAY_TO_RELAY)) {
                GridDiagnostics.event("C2S reject: duplicate connection %s -> %s (by %s)",
                        payload.start(), payload.end(), player.getGameProfile().getName());
                reject(player, "§c该连接已存在");
                return;
            }

            IEMSAPI.addConnection(payload.start(), payload.end(), ConnectionType.RELAY_TO_RELAY);
            GridDiagnostics.event("C2S accept: manual-conn %s -> %s (%.0fm, by %s)",
                    payload.start(), payload.end(), distance, player.getGameProfile().getName());
            reply(player, "§a连接已建立 (" + (int) distance + "m)");
        });
    }

    private static void reply(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    /**
     * 拒绝回执（M9）：actionbar 提示 + 向请求者回推当前电网快照。
     * <p>
     * 客户端发 C2S 请求的瞬间已渲染本地预测激光（0ms 反馈），被拒的连接
     * 不会出现在快照中——回推快照使 {@code ClientGridCache.applySync}
     * 清空预测列表，黄色预测激光随拒绝即时消失，不等客户端超时兜底。
     * </p>
     */
    private static void reject(ServerPlayer player, String message) {
        reply(player, message);
        PacketDistributor.sendToPlayer(player, IEMSEvents.buildGridSyncPayload());
    }

    /**
     * M9：两端是否为「非自动连接中继器 ↔ 用电/发电器」组合。
     * <p>用电/发电器 = 注册表中的非传输节点（发电/用电/储能设备）。
     * 核心不在注册表（节点为 null），不参与本判定——核心直连设备合法。
     * 适配伪装节点（DeviceAdapter 名下外部设备）不可手动连接，排除。</p>
     */
    private static boolean isNonAutoRelayDevicePair(IEnergyNode a, IEnergyNode b) {
        return isNonAutoRelay(a) && isPlainDevice(b)
                || isPlainDevice(a) && isNonAutoRelay(b);
    }

    private static boolean isNonAutoRelay(IEnergyNode node) {
        return node instanceof TransferDevice relay && !relay.isAutoConnect();
    }

    private static boolean isPlainDevice(IEnergyNode node) {
        return node != null && !(node instanceof TransferDevice)
                && !(node instanceof com.iems.adapter.IAdapterNode);
    }
}
