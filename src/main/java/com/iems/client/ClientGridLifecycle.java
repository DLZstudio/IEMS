package com.iems.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * 客户端电网缓存生命周期（V-02 客户端侧）。
 * <p>
 * 退出服务器/世界（单机退出存档、多人断开）时清空 {@link ClientGridCache}
 * 的全部静态状态与 {@link EnergyOverlayRenderer} 的拉线态，防止上一个世界的
 * 连接/核心/拉线起点数据残留到下一个世界（幽灵激光、错位核心位置、幽灵拉线）。
 * </p>
 * <p>
 * 仅在客户端加载（Dist.CLIENT），专用服务器不会触碰此类。
 * </p>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ClientGridLifecycle {

    private ClientGridLifecycle() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientGridCache.clearAll();
        EnergyOverlayRenderer.resetSmoothing();
        // 拉线态同样必须在退世界时清空：resetSmoothing 只归一四象限 EMA，
        // 残留的 isConnectingMode/startPos 会在重进同一存档（维度与坐标都可能命中）时
        // "幽灵复活"拉线模式，重进别的存档则会弹出莫名其妙的"距离太远"提示。
        EnergyOverlayRenderer.setConnectingMode(false, null, 0);
        // 清空渲染器配置缓存，使下次进入世界重读 config/DLZstudio/IEMS/Renderer.toml
        RendererConfig.invalidate();
    }
}
