package com.iems.example.client.render;

import com.iems.example.EnergyBroadcastTowerBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 能源广播塔 GeoLib 渲染器（客户端专用）。
 *
 * <p>移植自旧架构 {@code com.dlzstudio.iems.renderer.EnergyBroadcastTowerRenderer}，
 * 泛型重定向到新架构的 {@link EnergyBroadcastTowerBlockEntity}。</p>
 */
public class EnergyBroadcastTowerRenderer extends GeoBlockRenderer<EnergyBroadcastTowerBlockEntity> {

    public EnergyBroadcastTowerRenderer(BlockEntityRendererProvider.Context context) {
        super(new EnergyBroadcastTowerModel());
    }
}