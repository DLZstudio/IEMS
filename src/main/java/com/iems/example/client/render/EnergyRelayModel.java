package com.iems.example.client.render;

import com.iems.IEMS;
import com.iems.example.EnergyTransferRelayBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 能源传输中继器 GeoLib 模型（客户端专用）。
 *
 * <p>移植自旧架构 {@code com.dlzstudio.iems.renderer.EnergyRelayModel}，
 * 泛型重定向到新架构的 {@link EnergyTransferRelayBlockEntity}；
 * geo / 贴图资源路径保持与旧架构一致（对应 {@code assets/iems/geo/relay_tower.geo.json}
 * 与 {@code assets/iems/textures/block/relay_tower.png}）。</p>
 *
 * <p>与旧架构一致：无动画资源（返回 {@code null}），中继器模型为静态。</p>
 */
public class EnergyRelayModel extends GeoModel<EnergyTransferRelayBlockEntity> {

    @Override
    public ResourceLocation getModelResource(EnergyTransferRelayBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(IEMS.MODID, "geo/relay_tower.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(EnergyTransferRelayBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(IEMS.MODID, "textures/block/relay_tower.png");
    }

    @Override
    public ResourceLocation getAnimationResource(EnergyTransferRelayBlockEntity animatable) {
        return null; // 暂无动画（与旧架构一致）
    }
}