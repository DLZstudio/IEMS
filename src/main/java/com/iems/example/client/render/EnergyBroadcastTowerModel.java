package com.iems.example.client.render;

import com.iems.IEMS;
import com.iems.example.EnergyBroadcastTowerBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 能源广播塔 GeoLib 模型（客户端专用）。
 *
 * <p>移植自旧架构 {@code com.dlzstudio.iems.renderer.EnergyBroadcastTowerModel}，
 * 泛型重定向到新架构的 {@link EnergyBroadcastTowerBlockEntity}；
 * geo / 贴图资源路径保持与旧架构一致（对应 {@code assets/iems/geo/electric_pylon.geo.json}
 * 与 {@code assets/iems/textures/block/electric_pylon.png}）。</p>
 *
 * <p>与旧架构一致：无动画资源（返回 {@code null}），广播塔模型为静态。</p>
 */
public class EnergyBroadcastTowerModel extends GeoModel<EnergyBroadcastTowerBlockEntity> {

    @Override
    public ResourceLocation getModelResource(EnergyBroadcastTowerBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(IEMS.MODID, "geo/electric_pylon.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(EnergyBroadcastTowerBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(IEMS.MODID, "textures/block/electric_pylon.png");
    }

    @Override
    public ResourceLocation getAnimationResource(EnergyBroadcastTowerBlockEntity animatable) {
        return null; // 暂无动画（与旧架构一致）
    }
}