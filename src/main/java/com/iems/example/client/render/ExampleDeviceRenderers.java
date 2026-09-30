package com.iems.example.client.render;

import com.iems.IEMS;
import com.iems.example.IEMSExampleRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 示例设备 GeoLib 渲染器注册（客户端专用）。
 *
 * <p>移植自旧架构 {@code com.dlzstudio.iems.renderer.IEMSRenderer}，绑定新架构的
 * 方块实体类型 {@link IEMSExampleRegistry#ENERGY_TRANSFER_RELAY_BE} /
 * {@link IEMSExampleRegistry#ENERGY_BROADCAST_TOWER_BE}。</p>
 *
 * <p><b>总线说明</b>：{@link EntityRenderersEvent.RegisterRenderers} 属于 mod 事件总线事件，
 * 但 NeoForge 1.21.1 起 {@code @EventBusSubscriber#bus} 已废弃——加载器会按方法参数是否
 * 实现 {@code IModBusEvent} 自动分流注册（混合时分别注册到两条总线），
 * 故此处沿用 {@code ExampleConnectionInteraction} 的写法即可。</p>
 *
 * <p>本类仅在客户端加载（{@code Dist.CLIENT}），专用服务器不加载 GeckoLib 渲染类。</p>
 */
@EventBusSubscriber(modid = IEMS.MODID, value = Dist.CLIENT)
public final class ExampleDeviceRenderers {

    private ExampleDeviceRenderers() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 能源传输中继器（iems:energy_transfer_relay）
        event.registerBlockEntityRenderer(
                IEMSExampleRegistry.ENERGY_TRANSFER_RELAY_BE.get(),
                EnergyRelayRenderer::new);

        // 能源广播塔（iems:energy_broadcast_tower）
        event.registerBlockEntityRenderer(
                IEMSExampleRegistry.ENERGY_BROADCAST_TOWER_BE.get(),
                EnergyBroadcastTowerRenderer::new);

        IEMS.LOGGER.info("注册 IEMS 示例设备 GeoLib 渲染器（中继器/广播塔）");
    }
}