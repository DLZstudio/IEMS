package com.iems.network;

import com.iems.IEMS;
import com.iems.client.ClientGridCache;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * IEMS 网络层（M7 S1/S2）。
 * <p>
 * 在 {@link RegisterPayloadHandlersEvent} 中注册 {@link GridSyncPayload}
 * （服务端 → 客户端，playToClient）。客户端处理器收到后切到主线程，
 * 调用 {@link ClientGridCache#applySync} 填充渲染缓存。
 * </p>
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
                .versioned("2") // M8：GridSyncPayload 扩展能量/协议数值字段
                .optional();
        // 服务端 → 客户端：电网快照
        registrar.playToClient(
                GridSyncPayload.TYPE,
                GridSyncPayload.STREAM_CODEC,
                IEMSNetworking::handleGridSyncOnClient);
    }

    /** 客户端处理器：切到主线程填充渲染缓存。 */
    private static void handleGridSyncOnClient(final GridSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientGridCache.applySync(payload));
    }
}
