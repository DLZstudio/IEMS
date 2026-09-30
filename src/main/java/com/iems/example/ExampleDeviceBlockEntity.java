package com.iems.example;

import com.iems.api.IIemsInteractable;
import com.iems.core.grid.GlobalPos;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.TransferDevice;
import com.iems.diagnostics.GridDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * IEMS 示例设备方块实体抽象基类 —— 白皮书 §4.2「推荐的实例创建方式」参考实现。
 *
 * <p>与 {@code IemsDeviceBlockEntity}（测试模组版）语义一致，本类内置于 IEMS，
 * 作为官方示例与最小接入模板：</p>
 * <ol>
 *   <li><b>构造期只 new</b>：构造器仅创建纯 POJO 逻辑实例（此时 {@code level == null}，
 *       拿不到维度键，无法构造 GlobalPos），接入统一推迟到 {@link #onLoad()}；</li>
 *   <li><b>attach 语义</b>：{@link #onLoad()} 调用
 *       {@link #attachToGrid(IEnergyNode, GlobalPos)}——注册表已有同位置节点时
 *       <b>采纳既有节点</b>（模拟态比 BE NBT 新），返回值必须写回 {@link #logic}；</li>
 *   <li><b>服务端守卫</b>：所有接入/注销均带 {@code !level.isClientSide} 守卫；
 *       区块卸载（{@link #onChunkUnloaded()}）<b>不注销</b>——节点留在池中持续模拟。</li>
 * </ol>
 *
 * @param <T> IEMS 逻辑设备类型
 */
public abstract class ExampleDeviceBlockEntity<T extends IEnergyNode> extends BlockEntity
        implements IIemsInteractable, GeoBlockEntity {

    /** IEMS 逻辑实例（纯 POJO，与 BlockEntity 生命周期解耦）。 */
    protected T logic;

    /**
     * 区块卸载标记：原版 {@code LevelChunk.clearAllBlockEntities()} 对区块内每个 BE
     * <b>先调 {@link #onChunkUnloaded()} 再调 {@link #setRemoved()}</b>。区块卸载路径
     * 在此打标记，让紧随其后的 setRemoved 跳过注销（否则区块一卸载设备/核心就被
     * 注销、触及连接被 removeConnectionsAt 删除并写盘——重启后连接丢失）。
     * 方块真正消失（破坏/替换）只调 setRemoved、不经过 onChunkUnloaded，标记恒 false。
     */
    private boolean chunkUnloading = false;

    /**
     * GeoLib 动画实例缓存（每个 BE 实例独立）。
     * <p>GeckoLib 4.8 的 {@code GeoBlockRenderer<T extends BlockEntity & GeoAnimatable>}
     * 要求方块实体可动画；本基类实现 {@link GeoBlockEntity} 以满足该约束
     * （与旧架构 {@code EnergyRelayBlockEntity} / {@code EnergyBroadcastTowerBlockEntity}
     * 直接 implements {@code GeoBlockEntity} 等价）。</p>
     */
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    protected ExampleDeviceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.logic = createDevice();
    }

    /** 创建 IEMS 逻辑设备（纯 POJO，禁止在此访问 level 或注册电网）。 */
    protected abstract T createDevice();

    /** 将设备接入电网（attach 语义）：注册或采纳既有节点。 */
    protected abstract T attachToGrid(T device, GlobalPos pos);

    /** 将设备从电网注销。 */
    protected abstract void unregisterFromGrid(GlobalPos pos);

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            // 返回值写回 logic：attach 分支下注册表既有节点与本 BE 构造的实例不是同一对象
            this.logic = attachToGrid(logic, GlobalPos.of(level.dimension(), worldPosition));
        }
    }

    @Override
    public void setRemoved() {
        if (chunkUnloading) {
            // 区块卸载路径（clearAllBlockEntities：onChunkUnloaded → setRemoved 连调）：
            // 设备本体留在注册表继续模拟（M9），不清连接。方块真正消失不会经过
            // onChunkUnloaded，不会走到这个分支。
            super.setRemoved();
            return;
        }
        // 方块真正消失（破坏/替换）→ 注销设备本体并清除触及该位置的连接
        if (level != null && !level.isClientSide) {
            unregisterFromGrid(GlobalPos.of(level.dimension(), worldPosition));
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        // 区块卸载不注销设备——注册表中的 POJO 节点是设备本体，持续被调度器模拟
        if (level != null && !level.isClientSide) {
            // 打卸载标记：原版紧接着会对本 BE 调 setRemoved，见字段注释
            chunkUnloading = true;
            GridDiagnostics.event("detach %s (chunk unloaded, simulation continues)",
                    GlobalPos.of(level.dimension(), worldPosition));
        }
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 降级备份：注册表（iems_grid.dat）为唯一事实源，此 NBT 副本仅在工厂缺失场景消费
        tag.put("iems_logic", logic.serializeState());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("iems_logic")) {
            logic.restoreState(tag.getCompound("iems_logic"));
        }
    }

    // ------------------------------------------------------------------
    // GeoBlockEntity：GeckoLib 可动画方块实体（最小实现）
    // ------------------------------------------------------------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 暂无动画：与旧架构一致（中继器/广播塔均为静态模型，不注册 AnimationController）
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ------------------------------------------------------------------
    // IIemsInteractable：框架 Shift+右键拉线交互
    // ------------------------------------------------------------------

    /**
     * 示例设备均为传输节点，可作手动连接端点。
     * <p>注：{@code autoConnect == false} 的纯传输节点在服务端校验中
     * <b>不得手动连接用电/发电器</b>，只能与其它传输设备/核心拉线（框架规则）。</p>
     */
    @Override
    public boolean canBeConnectionEndpoint() {
        return logic instanceof TransferDevice;
    }

    /** 从本设备起线的最大距离：取逻辑设备声明值。 */
    @Override
    public int getMaxConnectionDistance() {
        return logic instanceof TransferDevice transferDevice
                ? transferDevice.getMaxConnectionDistance()
                : IIemsInteractable.DEFAULT_MAX_DISTANCE;
    }
}