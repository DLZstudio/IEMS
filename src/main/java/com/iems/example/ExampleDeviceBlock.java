package com.iems.example;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.BiFunction;

/**
 * IEMS 示例设备方块基类。
 *
 * <p><b>必须实现 {@link EntityBlock}</b>：MC 引擎仅在
 * {@code block instanceof EntityBlock} 时才为放置的方块创建 BlockEntity，
 * 普通 Block 放置后 {@code getBlockEntity()} 恒为 null，
 * BE 的 onLoad / setRemoved / onChunkUnloaded / NBT 全部不生效。</p>
 *
 * <p>本类属于 IEMS 的「示例设备」层（{@code com.iems.example}）：
 * 只依赖 {@code com.iems.api} 公开门面，不触碰 {@code core} 内部实现，
 * 与 {@code adapter} / {@code webpanel} 等模块平级，不破坏模块化设计。</p>
 *
 * <p><b>手动拉线不在本类</b>：Shift+右键"任意位置"由客户端全局钩子
 * {@code com.iems.example.client.ExampleConnectionInteraction}
 * （{@code InputEvent.InteractionKeyMappingTriggered}）统一接管——
 * 手势不依赖命中本方块，故无需覆写 {@code useWithoutItem}。
 * 服务端建连仍走 {@code IEMSNetworking.handleConnectionRequest} 二次校验。</p>
 */
public class ExampleDeviceBlock extends Block implements EntityBlock {

    /** 方块实体工厂：(pos, state) → BlockEntity。 */
    private final BiFunction<BlockPos, BlockState, BlockEntity> factory;

    public ExampleDeviceBlock(Properties properties, BiFunction<BlockPos, BlockState, BlockEntity> factory) {
        super(properties);
        this.factory = factory;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return factory.apply(pos, state);
    }

    /**
     * 让天空光直穿本方块（{@code getLightBlock() == 0}），设备格由天光柱直射写入光照。
     *
     * <p><b>机制</b>：{@code BlockBehaviour.getLightBlock} 默认为
     * {@code isSolidRender ? 15 : (propagatesSkylightDown ? 0 : 1)}，而
     * {@code propagatesSkylightDown} 默认要求「形状非满方块」。本类保留满方块碰撞箱
     * （防玩家穿模），未覆写 {@code getShape}，于是默认 {@code getLightBlock() == 1}。
     * 光照引擎的天光柱截断判定 {@code ChunkSkyLightSources.isEdgeOccluded} 以
     * {@code getLightBlock() != 0} 为遮挡条件——1 也会截断天光柱，设备格被排除在
     * 直射列之外，格内天空光退化为依赖放置时的增量补光计算。</p>
     *
     * <p><b>为什么必须避开补光路径</b>：实测（超平坦露天，BUILD 108 时代放置的设备）
     * 该补光曾把格内 sky 留在 0 并随存档持久化——中继器整体渲染为纯黑（packedLight
     * 的 sky 分量为 0，仅广播塔靠自带 lightLevel=7 勉强可见），直到重进世界触发
     * 全柱重算才被修正为 14。覆写本方法后 {@code getLightBlock() == 0}，天光柱在
     * 放置瞬间即直穿设备格，不再依赖补光，新放置的设备不会再落入该坑。</p>
     *
     * <p>旧 IEMS 的 {@code EnergyRelayBlock} / {@code EnergyBroadcastTowerBlock}
     * 是通过非满方块的 {@code getShape}（{@code box(4,0,4,12,16,12)} 等）让默认
     * {@code propagatesSkylightDown} 为 true 达到等价效果的；本类以覆写本方法
     * 替代，光照表现与旧设备一致（格内 sky 实测 14 = 柱值 15 −
     * {@code LightEngine.getOpacity} 的 {@code max(1, ·)} 下限）。</p>
     */
    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}