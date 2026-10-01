package com.iems.example;

import com.iems.api.IEMSAPI;
import com.iems.core.grid.GlobalPos;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.TransferDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.math.BigInteger;

/**
 * 能源传输中继器（{@code iems:energy_transfer_relay}）—— IEMS 官方示例设备之一。
 *
 * <p>定位：<b>远距离纯手动连接节点</b>。</p>
 * <ul>
 *   <li><b>连接距离</b>：500m（与框架 {@code IIemsInteractable.DEFAULT_MAX_DISTANCE} 一致，
 *       也是手动拉线交互的最大档位）；</li>
 *   <li><b>自动连接</b>：<b>不支持</b>（{@code autoConnect == false}）——
 *       不会自动发现/接入任何设备，连线一律由玩家 Shift+右键手动拉线；</li>
 *   <li><b>纯传输节点</b>：不装配任何设备适配器（EDA），因此不携带外部能量体系桥接能力；
 *       按框架 M9 规则，这类节点<b>不得</b>手动连接用电/发电器，
 *       只能与其它传输设备或核心拉线（跨维度由维度门负责）。</li>
 * </ul>
 *
 * <p>M9 模拟化：带工厂 ID 接入，设备持久化到 iems_grid.dat，重启后无区块加载
 * 也由工厂重建、持续模拟（BlockEntity 只是外观/视图）。</p>
 */
public class EnergyTransferRelayBlockEntity extends ExampleDeviceBlockEntity<TransferDevice> {

    /** 设备工厂 ID（M9 持久化重建用，模组加载期注册到 IEMSAPI）。 */
    public static final String FACTORY_ID = "iems:energy_transfer_relay";

    /** 最大手动连接距离（格）：500m。 */
    private static final int MAX_DISTANCE = 500;

    /**
     * 激光连接锚点（相对方块坐标原点，即方块最小角）。
     *
     * <p>由旧架构 {@code ConnectionData#getConnectionPointHeight} 推导：中继器模型
     * {@code relay_tower} 最高点 Y=175/16≈10.94 格，旧实现下调 0.25 取 10.75，
     * 水平方向取方块中心 0.5 —— 即旧版 {@code getLaserStart/getLaserEnd}
     * 的 {@code (x+0.5, y+10.75, z+0.5)}，去掉方块整数坐标后即本偏移。</p>
     */
    private static final Vec3 ANCHOR_OFFSET = new Vec3(0.5, 10.75, 0.5);

    public EnergyTransferRelayBlockEntity(BlockPos pos, BlockState state) {
        super(IEMSExampleRegistry.ENERGY_TRANSFER_RELAY_BE.get(), pos, state);
    }

    /** M9 重启重建工厂（模组加载期经 IEMSAPI.registerDeviceFactory 注册）。 */
    public static TransferDevice createLogic() {
        return new TransferDevice(
                "能源传输中继器",
                BigInteger.ONE,
                MAX_DISTANCE,
                false,   // 不支持自动连接（纯手动拉线）
                null,    // 白名单：不限制
                null,    // 黑名单：不限制
                ANCHOR_OFFSET); // 连接锚点：模型顶部 (0.5, 10.75, 0.5)
    }

    @Override
    protected TransferDevice createDevice() {
        return createLogic();
    }

    @Override
    protected TransferDevice attachToGrid(TransferDevice device, GlobalPos pos) {
        IEnergyNode attached = IEMSAPI.attachOrRegisterDevice(pos, device, FACTORY_ID);
        // 类型防御：既有节点理论上必为同工厂产物；不匹配时回退自身实例
        return attached instanceof TransferDevice transferDevice ? transferDevice : device;
    }

    @Override
    protected void unregisterFromGrid(GlobalPos pos) {
        IEMSAPI.unregisterDevice(pos);
    }
}