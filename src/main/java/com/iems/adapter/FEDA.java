package com.iems.adapter;

import com.iems.api.IEMSAPI;
import com.iems.core.energy.EnergyUnit;
import com.iems.core.grid.ConnectionType;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridTopology;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.TransferDevice;
import com.iems.diagnostics.GridDiagnostics;
import com.iems.discovery.DiscoveredDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FE 设备适配器（FEDA）——{@link DeviceAdapter} 的 FE 实现（本版本首个实现）。
 * <p>
 * 绑定一台<b>支持自动连接</b>的 {@link TransferDevice}（宿主中继器），在其
 * 连接半径内把 DS 发现的每台外部 FE 设备伪装成独立节点注册进电网
 * （逐设备节点，协议容量 = 设备数量，与原生 SE 设备同权）：
 * </p>
 * <ul>
 *   <li>纯生产者（canExtract 且不可接收）→ {@link FeProducerAdapter}；
 *       {@link #tick} 每 tick 抽取 FE（≤ maxFePerTick）喂入抽取缓冲，调度器
 *       读取折算的整 SE 产出；</li>
 *   <li>纯消费者（canReceive 且不可抽取）→ {@link FeConsumerAdapter}；
 *       {@link #tick} 每 tick 测外部接收余量写入需求申报（0/1 SE），调度器
 *       分配后折算 FE 入推送池，由 {@link #tick} 以 maxFePerTick 送抵外部；</li>
 *   <li>双向设备（canExtract 且 canReceive）→ {@link FeBufferAdapter}
 *       （StorageDevice，充放电统一储能契约）。</li>
 * </ul>
 * <p>
 * <b>存量直查（关键坑规避）</b>：{@link com.iems.discovery.DiscoveryScanner}
 * 会把已注册位置判为 skippedIems 跳过——逐设备节点落地后重扫必丢存量。
 * 本类自行维护 {@code managed} 集合：扫描报告<b>仅用于发现新设备</b>，
 * 存量设备在 {@link #sync} 中直查方块能力判定存在性。
 * </p>
 * <p>
 * <b>生命周期</b>：伪装节点经 {@code silentRegister} 注册（不入自动扫描队列，
 * 避免 IemsAutoConnector 对伪装节点建立 RELAY_TO_DEVICE 非桥接连接；无工厂 ID
 * 不持久化，重启后由首次 sync 重扫重建）；宿主注销后经
 * {@link FeBridgeTicker} 孤儿清扫调用 {@link #dropNode} 逐台注销。
 * </p>
 */
public class FEDA implements DeviceAdapter {

    /** 默认单目标每 tick FE 传输上限（抽取与推送对称）。 */
    public static final int DEFAULT_MAX_FE_PER_TICK = 8_192;

    /** 宿主中继器（位置即身份，注册时注入）。 */
    private final TransferDevice host;

    /** 每 tick 从单台外部设备抽取/推送的 FE 上限。 */
    private final int maxFePerTick;

    /** 名下伪装节点（逐设备；扫描报告只用于发现新设备，存量靠直查）。 */
    private final Map<GlobalPos, IEnergyNode> managed = new ConcurrentHashMap<>();

    public FEDA(TransferDevice host) {
        this(host, DEFAULT_MAX_FE_PER_TICK);
    }

    public FEDA(TransferDevice host, int maxFePerTick) {
        this.host = host;
        this.maxFePerTick = Math.max(1, maxFePerTick);
    }

    @Override
    public EnergyUnit unit() {
        return EnergyUnit.FE;
    }

    /** 宿主是否已注销（注册表中该宿主位置不再是本实例 → 名下节点应被清扫）。 */
    public boolean isOrphaned() {
        GlobalPos p = host.getPosition();
        return p == null || DeviceRegistry.instance().get(p) != host;
    }

    /** 名下伪装节点快照（诊断/测试用）。 */
    public Map<GlobalPos, IEnergyNode> getManaged() {
        return Map.copyOf(managed);
    }

    // ------------------------------------------------------------------
    // DeviceAdapter 契约
    // ------------------------------------------------------------------

    @Override
    public List<DiscoveredDevice> scan(ServerLevel level, BlockPos center, int radius) {
        GlobalPos hostPos = host.getPosition();
        return IEMSAPI.scanForDevices(level, center, radius).devices().stream()
                .filter(d -> hostPos == null || d.pos().dimension().equals(hostPos.dimension()))
                .toList();
    }

    /**
     * 同步适配节点（每 40 tick）：新设备注册建连、消失设备注销、宿主失效清扫。
     */
    @Override
    public void sync(ServerLevel level) {
        GlobalPos hostPos = host.getPosition();
        if (hostPos == null) {
            return;
        }
        if (isOrphaned()) {
            detach();
            return;
        }
        // 1) 扫描报告仅用于发现新设备
        Set<GlobalPos> scanned = new HashSet<>();
        for (DiscoveredDevice device : scan(level, hostPos.pos(), host.getMaxConnectionDistance())) {
            scanned.add(device.pos());
            if (!managed.containsKey(device.pos())) {
                registerDevice(level, hostPos, device);
            }
        }
        // 2) 存量直查方块能力：消失（方块移除/能力丢失）→ 注销（自动清连接）
        for (Iterator<Map.Entry<GlobalPos, IEnergyNode>> it = managed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<GlobalPos, IEnergyNode> entry = it.next();
            GlobalPos pos = entry.getKey();
            if (!level.hasChunkAt(pos.pos())) {
                continue; // 区块未加载：不裁决（模拟化下节点留池休眠）
            }
            if (resolveStorage(level, pos.pos()) == null) {
                it.remove();
                IEMSAPI.unregisterDevice(pos);
                GridDiagnostics.event("adapter-%s -%s @ %s (device gone)",
                        unit().getName(), entry.getValue().getClass().getSimpleName(), pos);
            }
        }
        // 3) 防御：宿主在 sync 中途失效 → 全量注销
        if (isOrphaned()) {
            detach();
        }
    }

    /**
     * 每 tick 驱动（结算前调用）：抽取 FE 喂生产节点缓冲、测余量喂需求申报、
     * 把消费/储能节点推送池按 maxFePerTick 送抵外部。
     * <p>孤岛门控：宿主不可达核心时不抽不推、需求归零（防缓冲无出口囤积）。</p>
     */
    @Override
    public void tick(ServerLevel level) {
        GlobalPos hostPos = host.getPosition();
        if (hostPos == null || isOrphaned()) {
            return;
        }
        boolean reachable = GridTopology.instance().isReachable(hostPos);

        // 阶段 1：抽取 + 测余量（数值供本 tick 调度器结算消费）
        for (Map.Entry<GlobalPos, IEnergyNode> entry : managed.entrySet()) {
            GlobalPos pos = entry.getKey();
            if (!pos.dimension().equals(hostPos.dimension()) || !level.hasChunkAt(pos.pos())) {
                continue;
            }
            IEnergyStorage storage = resolveStorage(level, pos.pos());
            if (storage == null) {
                continue; // 暂不可解析（方块替换中），留待 sync 裁定
            }
            IEnergyNode node = entry.getValue();
            if (node instanceof FeProducerAdapter producer) {
                // 仅从纯生产者（可抽取且不可接收）抽取：双向设备只推送不抽取，
                // 否则抽取-推送抵消导致净流动为 0
                if (reachable && storage.canExtract() && !storage.canReceive()) {
                    int got = storage.extractEnergy(maxFePerTick, false);
                    if (got > 0) {
                        producer.buffer().addExtracted(BigInteger.valueOf(got));
                    }
                }
            } else if (node instanceof FeConsumerAdapter consumer) {
                consumer.buffer().updateExternalFree(reachable ? freeOf(storage) : BigInteger.ZERO);
            } else if (node instanceof FeBufferAdapter bufferNode) {
                // IEnergyStorage 无每 tick 速率查询，以适配器传输上限为吞吐上报
                bufferNode.setLiveState(
                        BigInteger.valueOf(storage.getEnergyStored()),
                        BigInteger.valueOf(storage.getMaxEnergyStored()),
                        maxFePerTick);
                bufferNode.buffer().updateExternalFree(reachable ? freeOf(storage) : BigInteger.ZERO);
            }
        }

        // 阶段 2：推送（上一 tick 结算分配的推送池按 maxFePerTick 送抵外部）
        if (reachable) {
            for (Map.Entry<GlobalPos, IEnergyNode> entry : managed.entrySet()) {
                GlobalPos pos = entry.getKey();
                FeConversionBuffer buffer = pushBufferOf(entry.getValue());
                if (buffer == null || buffer.getPushBuffer().signum() <= 0) {
                    continue;
                }
                if (!pos.dimension().equals(hostPos.dimension()) || !level.hasChunkAt(pos.pos())) {
                    continue;
                }
                IEnergyStorage storage = resolveStorage(level, pos.pos());
                if (storage == null || !storage.canReceive()) {
                    continue;
                }
                int room = storage.getMaxEnergyStored() - storage.getEnergyStored();
                if (room <= 0) {
                    continue;
                }
                // 平滑推送：单目标每 tick ≤ maxFePerTick；缓冲余量不足整额度时按余量送
                int cap = Math.min(room, maxFePerTick);
                int want = BigInteger.valueOf(cap).min(buffer.getPushBuffer()).intValue();
                int pushed = storage.receiveEnergy(want, false);
                if (pushed > 0) {
                    buffer.consumePushBuffer(BigInteger.valueOf(pushed));
                }
            }
        }
    }

    /** 注销全部名下节点（宿主被移除/失效时）。 */
    @Override
    public void detach() {
        for (GlobalPos pos : managed.keySet()) {
            IEMSAPI.unregisterDevice(pos);
        }
        managed.clear();
        GlobalPos p = host.getPosition();
        GridDiagnostics.event("adapter-%s detached (%s)", unit().getName(), p == null ? "?" : p);
    }

    /** 注销单个伪装节点并移出名下集合（宿主失效孤儿清扫，FeBridgeTicker 调用）。 */
    public void dropNode(GlobalPos pos) {
        if (managed.remove(pos) != null) {
            IEMSAPI.unregisterDevice(pos);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 注册单台新设备：按能力分类包装 + silentRegister + 建 ADAPTER_BRIDGE。 */
    private void registerDevice(ServerLevel level, GlobalPos hostPos, DiscoveredDevice device) {
        GlobalPos pos = device.pos();
        IEnergyNode node = classify(device);
        if (node == null) {
            return;
        }
        // silentRegister：不入自动扫描队列（避免 IemsAutoConnector 建非桥接连接）、
        // 不持久化（无工厂 ID → 重启后由首次 sync 重扫重建）
        DeviceRegistry.instance().silentRegister(pos, node, null);
        IEMSAPI.addConnection(hostPos, pos, ConnectionType.ADAPTER_BRIDGE);
        managed.put(pos, node);
        GridDiagnostics.event("adapter+%s %s @ %s (%s)",
                unit().getName(), node.getClass().getSimpleName(), pos, device.blockId());
    }

    /** 按设备能力分类包装成对应伪装节点；无可用分类返回 null。 */
    private IEnergyNode classify(DiscoveredDevice device) {
        String name = unit().getName() + "/" + device.blockId();
        boolean producer = device.isProducer();
        boolean consumer = device.isConsumer();
        if (producer && !consumer) {
            return new FeProducerAdapter(name, this);
        }
        if (!producer && consumer) {
            return new FeConsumerAdapter(name, this);
        }
        if (producer && consumer) {
            return new FeBufferAdapter(name, this);
        }
        return null;
    }

    /** 返回节点的推送缓冲（仅消费/储能节点有推送语义；生产节点为 null）。 */
    private static FeConversionBuffer pushBufferOf(IEnergyNode node) {
        if (node instanceof FeConsumerAdapter consumer) {
            return consumer.buffer();
        }
        if (node instanceof FeBufferAdapter bufferNode) {
            return bufferNode.buffer();
        }
        return null;
    }

    /**
     * 解析目标方块的能力视图：优先无方向上下文（多数 FE 设备全方向同视图），
     * 再按 6 面探测。目标无能力（被替换/破坏）返回 null。
     */
    private static IEnergyStorage resolveStorage(ServerLevel level, BlockPos pos) {
        IEnergyStorage noSide = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        if (noSide != null) {
            return noSide;
        }
        for (Direction side : Direction.values()) {
            IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
            if (storage != null) {
                return storage;
            }
        }
        return null;
    }

    /** 外部接收端剩余容量（FE，≥0）。 */
    private static BigInteger freeOf(IEnergyStorage storage) {
        long room = (long) storage.getMaxEnergyStored() - storage.getEnergyStored();
        return BigInteger.valueOf(Math.max(0L, room));
    }
}
