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
 * 能源广播塔（{@code iems:energy_broadcast_tower}）—— IEMS 官方示例设备之一。
 *
 * <p>定位：<b>近距离自动接入节点</b>。</p>
 * <ul>
 *   <li><b>连接距离</b>：50m（自动与手动共用同一距离声明）；</li>
 *   <li><b>自动连接</b>：<b>支持</b>（{@code autoConnect == true}）——
 *       注册后由 {@code IemsAutoConnector} 在每 tick 出队扫描，对半径内
 *       非传输节点设备自动建连（{@code RELAY_TO_DEVICE}），幂等；</li>
 *   <li><b>手动连接</b>：<b>支持</b>——同样可作为玩家 Shift+右键拉线的端点；</li>
 *   <li><b>适配器</b>：{@code autoConnect == true} 时 TransferDevice 默认装配
 *       {@code FEDA}（FE 桥接组件），因此可在半径内经 EDS 发现外部 FE 设备并桥接进网
 *       （外部 FE 设备入网的唯一通道即自动连接节点）。</li>
 * </ul>
 *
 * <p>M9 模拟化：带工厂 ID 接入，设备持久化到 iems_grid.dat，重启后无区块加载
 * 也由工厂重建、持续模拟（BlockEntity 只是外观/视图）。</p>
 */
public class EnergyBroadcastTowerBlockEntity extends ExampleDeviceBlockEntity<TransferDevice> {

    /** 设备工厂 ID（M9 持久化重建用，模组加载期注册到 IEMSAPI）。 */
    public static final String FACTORY_ID = "iems:energy_broadcast_tower";

    /** 自动/手动连接距离（格）：50m。 */
    private static final int MAX_DISTANCE = 50;

    /**
     * 激光连接锚点（相对方块坐标原点，即方块最小角）。
     *
     * <p>由旧架构 {@code ConnectionData#getConnectionPointHeight} 推导：广播塔模型
     * {@code electric_pylon} 最高点（风扇顶部）Y=128/16=8.0 格，水平方向取方块中心 0.5
     * —— 即旧版 {@code getLaserStart/getLaserEnd} 的 {@code (x+0.5, y+8.0, z+0.5)}，
     * 去掉方块整数坐标后即本偏移。</p>
     */
    private static final Vec3 ANCHOR_OFFSET = new Vec3(0.5, 8.0, 0.5);

    public EnergyBroadcastTowerBlockEntity(BlockPos pos, BlockState state) {
        super(IEMSExampleRegistry.ENERGY_BROADCAST_TOWER_BE.get(), pos, state);
    }

    /** M9 重启重建工厂（模组加载期经 IEMSAPI.registerDeviceFactory 注册）。 */
    public static TransferDevice createLogic() {
        return new TransferDevice(
                "能源广播塔",
                BigInteger.ONE,
                MAX_DISTANCE,
                true,    // 支持自动连接（注册后自动扫描半径内非传输节点设备）
                null,    // 白名单：不限制
                null,    // 黑名单：不限制（传输节点已由框架结构排除）
                ANCHOR_OFFSET); // 连接锚点：模型顶部 (0.5, 8.0, 0.5)
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