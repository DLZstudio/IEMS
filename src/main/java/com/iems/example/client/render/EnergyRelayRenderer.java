package com.iems.example.client.render;

import com.iems.example.EnergyTransferRelayBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 能源传输中继器 GeoLib 渲染器（客户端专用）。
 *
 * <p>移植自旧架构 {@code com.dlzstudio.iems.renderer.EnergyRelayRenderer}，
 * 泛型重定向到新架构的 {@link EnergyTransferRelayBlockEntity}。</p>
 */
public class EnergyRelayRenderer extends GeoBlockRenderer<EnergyTransferRelayBlockEntity> {

    public EnergyRelayRenderer(BlockEntityRendererProvider.Context context) {
        super(new EnergyRelayModel());
    }
}
