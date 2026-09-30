package com.iems.example.client;

import com.iems.IEMS;
import com.iems.api.IIemsInteractable;
import com.iems.client.EnergyOverlayRenderer;
import com.iems.core.grid.GlobalPos;
import com.iems.network.ConnectionRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 示例设备的手动拉线交互（客户端全局钩子 + 状态机）。
 *
 * <p><b>手势</b>：Shift 键是「模式键」，普通右键是「动作键」：</p>
 * <ul>
 *   <li>未拉线 + Shift+右键<b>可连接设备</b> → 以该设备为原点进入连接模式（HUD 显示实时距离）</li>
 *   <li>未拉线 + Shift+右键其它位置 → <b>不拦截</b>（放方块等原版行为不受影响）</li>
 *   <li>拉线中 + Shift+右键<b>任何位置</b> → 一律退出连接模式（Shift 只负责进出模式，不建连）</li>
 *   <li>拉线中 + 普通右键<b>另一台设备</b> → 发 C2S 建连请求</li>
 *   <li>拉线中 + 普通右键<b>起点自身</b> → 退出连接模式</li>
 *   <li>拉线中 + 普通右键非设备/空气 → <b>不拦截</b>（原版放置等行为不受影响）</li>
 * </ul>
 *
 * <p>之所以挂在客户端 {@link InputEvent.InteractionKeyMappingTriggered}
 * （使用键按下时、原版交互之前触发）而非某方块的 {@code useWithoutItem}：
 * 建连目标不限于本模组方块，且 Shift 进出模式需在未命中本模组方块时也能收线。</p>
 *
 * <p><b>旧版对照</b>：复刻 {@code com.dlzstudio.iems.blocks.EnergyRelayBlock#useWithoutItem}
 * 的键位分工——Shift 进/出模式，普通右键在模式内命中目标即建连。按新架构三段式改造——
 * 本地只管状态与手势，建连交服务端：</p>
 * <pre>
 * 旧版：方块右键 → ConnectionManager（本地状态 + 本地建连 + 本地持久化）
 * 新版：全局手势 → 本类（本地状态） → ConnectionRequestPayload(C2S)
 *        → IEMSNetworking.handleConnectionRequest（端点/同维度/距离/重复/中继规则复检后建连）
 * </pre>
 *
 * <p><b>为何在外部模组侧</b>：新架构刻意不提供输入触发器——框架只给契约
 * （{@link IIemsInteractable}）、包（{@link ConnectionRequestPayload}）、服务端校验与
 * 渲染（{@link EnergyOverlayRenderer} 拉线 HUD + 超距自动取消），手势由外部模组实现
 * （见 {@code EnergyOverlayRenderer#setConnectingMode} 注释"由外部模组调用"）。</p>
 *
 * <p><b>状态自愈</b>：拉线态的唯一事实源是 {@link EnergyOverlayRenderer}
 * （{@link EnergyOverlayRenderer#isConnectingMode()} 与 {@link EnergyOverlayRenderer#getStartPos()}）
 * ——它会在超距（max+10）或维度改变时自行收线；本类不缓存布尔值与起点，
 * 每次按键都回读，因此框架自动取消后不会残留幽灵起点。</p>
 *
 * <p>本类仅客户端加载（{@code Dist.CLIENT} 订阅者，专用服务端不注册/不加载）。</p>
 */
@EventBusSubscriber(modid = IEMS.MODID, value = Dist.CLIENT)
public final class ExampleConnectionInteraction {

    private ExampleConnectionInteraction() {
    }

    /**
     * 使用键按下时接管拉线手势。
     * <p>仅在"确实处理了本次点击"时 {@code setCanceled(true)}，
     * 阻断原版右键交互（方块放置/物品使用），避免与拉线冲突。</p>
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            return;
        }

        boolean shift = player.isShiftKeyDown();
        boolean connecting = EnergyOverlayRenderer.isConnectingMode();
        // M-06：起点唯一取自框架侧状态，本类不再维护第二份副本，杜绝两处状态不同步
        GlobalPos start = EnergyOverlayRenderer.getStartPos();

        // 维度守卫：起点与当前维度不一致（换维度/跨世界残留）→ 丢弃旧起点
        if (start != null && !start.dimension().equals(level.dimension())) {
            finish();
            connecting = false;
            start = null;
        }

        BlockPos pos = mc.hitResult instanceof BlockHitResult blockHit ? blockHit.getBlockPos() : null;

        // 情况一：未拉线 —— 仅 Shift+右键可连接设备时进入连接模式（以此设备为原点）
        if (!connecting) {
            if (shift && pos != null && isEndpoint(level, pos)) {
                GlobalPos clicked = GlobalPos.of(level.dimension(), pos);
                // 距离上限取起点设备自身声明值（中继器 500m / 广播塔 50m），
                // 与旧版"由起点决定连接距离"的规则一致（起点由 setConnectingMode 记录）
                EnergyOverlayRenderer.setConnectingMode(true, clicked, maxDistance(level, pos));
                player.displayClientMessage(
                        Component.literal("§b已进入连接模式，右键目标设备完成连接（Shift+右键取消）"), true);
                event.setCanceled(true);
            }
            // 非设备/空气/未按 Shift：不拦截，交给原版（放置方块等）
            return;
        }

        // 情况二：拉线中 + Shift+右键任何位置（含目标设备/起点/空气）→ 一律退出，不建连
        if (shift) {
            finish();
            player.displayClientMessage(Component.literal("§c已退出连接模式"), true);
            event.setCanceled(true);
            return;
        }

        // 情况三：拉线中 + 普通右键 —— 起点自身=退出，另一台设备=建连，其它不拦截
        if (pos == null) {
            return;
        }
        GlobalPos clicked = GlobalPos.of(level.dimension(), pos);
        if (clicked.equals(start)) {
            finish();
            player.displayClientMessage(Component.literal("§c已退出连接模式"), true);
            event.setCanceled(true);
            return;
        }
        if (isEndpoint(level, pos)) {
            // C2S 请求：服务端复检后建连，回执"连接已建立"或拒绝原因（拒绝时回推快照）
            PacketDistributor.sendToServer(new ConnectionRequestPayload(start, clicked));
            // 本地立即收线：不做事先预测（新版无本地预测列表，结论以服务端回执为准）
            finish();
            event.setCanceled(true);
        }
        // 非设备方块/空气：不拦截，交给原版
    }

    // ------------------------------------------------------------------
    // 状态与查询
    // ------------------------------------------------------------------

    /** 收线：关框架侧拉线 HUD（起点状态唯一由 {@link EnergyOverlayRenderer} 持有）。 */
    private static void finish() {
        EnergyOverlayRenderer.setConnectingMode(false, null, 0);
    }

    private static boolean isEndpoint(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof IIemsInteractable endpoint
                && endpoint.canBeConnectionEndpoint();
    }

    private static int maxDistance(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof IIemsInteractable endpoint
                ? endpoint.getMaxConnectionDistance()
                : IIemsInteractable.DEFAULT_MAX_DISTANCE;
    }
}