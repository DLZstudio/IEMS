package com.iems.example;

import com.iems.IEMS;
import com.iems.api.IEMSAPI;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * IEMS 内置示例设备注册中心（{@code com.iems.example}）。
 *
 * <p>IEMS 作为科技模组需要在自身提供可放置方块，本注册中心提供两个功能互异的示例设备，
 * 同时充当白皮书 §4.2「推荐实例创建方式」的官方最小实现模板：</p>
 * <ul>
 *   <li>{@code iems:energy_transfer_relay} 能源传输中继器 — 500m，仅手动拉线，纯传输节点；</li>
 *   <li>{@code iems:energy_broadcast_tower} 能源广播塔 — 50m，自动 + 手动连接，携带 FE 桥接组件。</li>
 * </ul>
 *
 * <p>模块化边界：本包只依赖 {@code com.iems.api} 公开门面（{@code IEMSAPI} /
 * {@code IIemsInteractable}），不引用 {@code core} 内部实现，与 {@code adapter} /
 * {@code webpanel} / {@code discovery} 等模块平级。</p>
 */
public final class IEMSExampleRegistry {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(IEMS.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(IEMS.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, IEMS.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, IEMS.MODID);

    // ---------- 方块 ----------

    /**
     * 能源传输中继器：500m 手动连接，不支持自动连接。
     *
     * <p>{@code noOcclusion()} 必需：方块外观完全由 GeoLib 模型（{@code builtin/entity}）
     * 提供，不走原版烘焙模型；同时它令 {@code isSolidRender} 为假，避免方块自身
     * 按 15 级遮光参与光照传播、连带遮住相邻方块的贴面。旧 IEMS 同类方块同样使用
     * {@code noOcclusion()}。</p>
     *
     * <p><b>但仅凭 {@code noOcclusion()} 不足以让设备格被天光柱直射</b>：本方块未覆写
     * {@code getShape}，形状是满方块，默认 {@code getLightBlock()} 为 1，而光照引擎的
     * 天光柱截断判定以 {@code getLightBlock() != 0} 为遮挡条件——设备格会退化为依赖
     * 放置时的增量补光（该路径实测曾把格内天空光留在 0 并随存档持久化，表现为模型
     * 长期纯黑）。让天光柱直穿的覆写在
     * {@link ExampleDeviceBlock#propagatesSkylightDown}。</p>
     */
    public static final DeferredBlock<ExampleDeviceBlock> ENERGY_TRANSFER_RELAY = BLOCKS.register(
            "energy_transfer_relay",
            () -> new ExampleDeviceBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE).strength(3.0F, 8.0F).noOcclusion(),
                    EnergyTransferRelayBlockEntity::new));

    /** 能源广播塔：50m 自动 + 手动连接（同中继器，需 {@code noOcclusion()}）。 */
    public static final DeferredBlock<ExampleDeviceBlock> ENERGY_BROADCAST_TOWER = BLOCKS.register(
            "energy_broadcast_tower",
            () -> new ExampleDeviceBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(4.0F, 10.0F).lightLevel(s -> 7).noOcclusion(),
                    EnergyBroadcastTowerBlockEntity::new));

    // ---------- 物品 ----------

    public static final DeferredItem<BlockItem> ENERGY_TRANSFER_RELAY_ITEM =
            ITEMS.registerSimpleBlockItem("energy_transfer_relay", ENERGY_TRANSFER_RELAY);
    public static final DeferredItem<BlockItem> ENERGY_BROADCAST_TOWER_ITEM =
            ITEMS.registerSimpleBlockItem("energy_broadcast_tower", ENERGY_BROADCAST_TOWER);

    // ---------- 方块实体 ----------

    public static final Supplier<BlockEntityType<EnergyTransferRelayBlockEntity>> ENERGY_TRANSFER_RELAY_BE =
            BLOCK_ENTITIES.register("energy_transfer_relay",
                    () -> BlockEntityType.Builder.of(
                            EnergyTransferRelayBlockEntity::new, ENERGY_TRANSFER_RELAY.get()).build(null));

    public static final Supplier<BlockEntityType<EnergyBroadcastTowerBlockEntity>> ENERGY_BROADCAST_TOWER_BE =
            BLOCK_ENTITIES.register("energy_broadcast_tower",
                    () -> BlockEntityType.Builder.of(
                            EnergyBroadcastTowerBlockEntity::new, ENERGY_BROADCAST_TOWER.get()).build(null));

    // ---------- 创造标签页 ----------

    public static final Supplier<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.iems"))
                    .icon(() -> new ItemStack(ENERGY_BROADCAST_TOWER_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ENERGY_TRANSFER_RELAY_ITEM.get());
                        output.accept(ENERGY_BROADCAST_TOWER_ITEM.get());
                    })
                    .build());

    private IEMSExampleRegistry() {
    }

    /** 注册各类 DeferredRegister 到 mod 事件总线（由 {@link IEMS} 构造器调用）。 */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        TABS.register(modEventBus);
    }

    /**
     * 注册设备工厂（M9 持久化重建用）：iems_grid.dat 的 Devices 列表按工厂 ID 反查。
     * <p>须在模组加载期完成，早于任何区块加载。</p>
     */
    public static void registerDeviceFactories() {
        IEMSAPI.registerDeviceFactory(
                EnergyTransferRelayBlockEntity.FACTORY_ID, EnergyTransferRelayBlockEntity::createLogic);
        IEMSAPI.registerDeviceFactory(
                EnergyBroadcastTowerBlockEntity.FACTORY_ID, EnergyBroadcastTowerBlockEntity::createLogic);
    }
}