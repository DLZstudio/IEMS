package com.iems.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * 客户端电网缓存生命周期（V-02 客户端侧）。
 * <p>
 * 退出服务器/世界（单机退出存档、多人断开）时清空 {@link ClientGridCache}
 * 的全部静态状态，防止上一个世界的连接/核心数据残留到下一个世界
 * （幽灵激光、错位核心位置）。
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
    }
}
