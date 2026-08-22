package com.iems.core.grid;

import com.iems.core.node.CoreDevice;
import com.iems.core.node.DimensionGate;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.TransferDevice;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 设备池（Registry）。
 * <p>
 * 按 {@link GlobalPos} 索引所有已接入电网的设备；维护全局唯一核心；
 * 并维护 DimensionGate 的 pairId 配对关系（同 PID 门互加对端）。
 * </p>
 */
public class DeviceRegistry {

    private static final DeviceRegistry INSTANCE = new DeviceRegistry();

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<GlobalPos, IEnergyNode> devices = new ConcurrentHashMap<>();

    private volatile CoreDevice core;
    private volatile GlobalPos corePos;

    /**
     * 当前关停是否由协议容量超限引起（M8）。
     * <p>
     * 区分「协议关停」与「手动关停」：只有协议关停才允许在用量回到限值内时
     * 自动恢复供电；手动关停（如 /iems shutdown）不受设备增删影响，
     * 直到管理员显式重启。
     * </p>
     */
    private volatile boolean protocolShutdown = false;

    /**
     * 区块加载/卸载回调。
     * <p>
     * 由 IEMS 模组层（如 IEMS.onServerStarted）设置，实现具体的区块常加载逻辑。
     * </p>
     */
    private static Consumer<GlobalPos> chunkLoadCallback;
    private static Consumer<GlobalPos> chunkUnloadCallback;

    /**
     * 设置区块加载回调（由模组层调用）。
     * @param callback 加载区块时的回调，接收 GlobalPos
     */
    public static void setChunkLoadCallback(Consumer<GlobalPos> callback) {
        chunkLoadCallback = callback;
    }

    /**
     * 设置区块卸载回调（由模组层调用）。
     * @param callback 卸载区块时的回调，接收 GlobalPos
     */
    public static void setChunkUnloadCallback(Consumer<GlobalPos> callback) {
        chunkUnloadCallback = callback;
    }

    private DeviceRegistry() {
    }

    /** 全局单例（内部模块共享同一个设备池）。 */
    public static DeviceRegistry instance() {
        return INSTANCE;
    }

    /** 注册一个设备。若为 DimensionGate，则与同 PID 的其他门建立对端关系。 */
    public void register(GlobalPos pos, IEnergyNode node) {
        devices.put(pos, node);
        if (node instanceof TransferDevice transferDevice) {
            transferDevice.setPosition(pos);
        }
        if (node instanceof DimensionGate gate) {
            linkDimensionGate(pos, gate);
        }
        // 检查协议容量是否超限
        checkProtocolLimit();
    }

    /** 注销一个设备。若为 DimensionGate，则与同 PID 的对端解除关系。 */
    public void unregister(GlobalPos pos) {
        IEnergyNode removed = devices.remove(pos);
        if (removed instanceof DimensionGate gate) {
            unlinkDimensionGate(pos, gate);
        }
        // 检查协议容量是否超限（可能因卸载恢复正常）
        checkProtocolLimit();
    }

    public IEnergyNode get(GlobalPos pos) {
        return devices.get(pos);
    }

    public Collection<IEnergyNode> getAll() {
        return Collections.unmodifiableCollection(devices.values());
    }

    public Collection<GlobalPos> getAllPositions() {
        return Collections.unmodifiableCollection(devices.keySet());
    }

    public int size() {
        return devices.size();
    }

    /** 注册核心（全局唯一）。 */
    public void registerCore(GlobalPos pos, CoreDevice core) {
        if (this.core != null) {
            throw new IllegalStateException("IEMS 电网已存在核心，整个世界（跨维度）仅允许一个核心。现有核心位置: " + corePos);
        }
        this.core = core;
        this.corePos = pos;
        this.protocolShutdown = false;
        // M6: 触发核心区块常加载
        if (chunkLoadCallback != null) {
            chunkLoadCallback.accept(pos);
        }
    }

    /** 注销核心。 */
    public void unregisterCore() {
        if (corePos != null && chunkUnloadCallback != null) {
            chunkUnloadCallback.accept(corePos);
        }
        this.core = null;
        this.corePos = null;
    }

    /** 清空全部运行时状态（设备池/核心）。仅在服务器完全停止后由生命周期钩子调用（见 IEMSEvents）。 */
    public void clearAll() {
        devices.clear();
        core = null;
        corePos = null;
        protocolShutdown = false;
    }

    /** 当前关停是否由协议超限引起（手动关停返回 false）。 */
    public boolean isProtocolShutdown() {
        return protocolShutdown;
    }

    /** 清除协议关停标志（手动 setGridActive 时调用，此后由容量检查重新裁定）。 */
    public void clearProtocolShutdownFlag() {
        protocolShutdown = false;
    }

    public CoreDevice getCore() {
        return core;
    }

    public GlobalPos getCorePos() {
        return corePos;
    }

    /** 当前已用协议容量 = 所有设备协议成本之和。 */
    public BigInteger getProtocolUsed() {
        BigInteger sum = BigInteger.ZERO;
        for (IEnergyNode node : devices.values()) {
            sum = sum.add(node.getProtocolCost());
        }
        return sum;
    }

    /**
     * 检查协议容量并同步电网开关状态（V-01 修复：关停后可自动恢复）。
     * <p>
     * 按白皮书第7.2节：protocolUsed &gt; protocolTotal → 电网关停；
     * 用量回到限值以内 → 自动重新供电。M8 精化：只有「协议关停」才自动恢复，
     * 手动关停（管理员指令）不受设备增删影响。
     * 仅在状态需要翻转时才重建拓扑，避免每次注册/注销都触发无谓的 BFS 重扫。
     * </p>
     */
    private void checkProtocolLimit() {
        if (core == null || corePos == null) {
            return;
        }
        BigInteger used = getProtocolUsed();
        BigInteger limit = core.getProtocolLimit();
        boolean over = used.compareTo(limit) > 0;
        if (over && core.isGridActive()) {
            // 超限运行中 → 关停（标记协议原因，拆除设备回到限内后可自动恢复）
            core.setGridActive(false);
            protocolShutdown = true;
            GridTopology.instance().rebuild();
        } else if (!over && !core.isGridActive() && protocolShutdown) {
            // 协议关停 + 用量已回到限内 → 自动恢复供电
            core.setGridActive(true);
            protocolShutdown = false;
            GridTopology.instance().rebuild();
        }
        // 其余情况（正常/手动关停/已关停）不动作
    }

    private void linkDimensionGate(GlobalPos pos, DimensionGate gate) {
        for (Map.Entry<GlobalPos, IEnergyNode> entry : devices.entrySet()) {
            if (entry.getKey().equals(pos)) {
                continue;
            }
            if (entry.getValue() instanceof DimensionGate other && other.getPairId().equals(gate.getPairId())) {
                // V-07: 超出 maxPeers 时拒绝配对并告警，不再抛异常炸掉放置逻辑
                if (!gate.canAcceptPeer() || !other.canAcceptPeer()) {
                    LOGGER.warn("IEMS: 维度门配对被拒绝（超出 maxPeers 限制），pairId={} 位置={}",
                            gate.getPairId(), pos);
                    continue;
                }
                gate.addPeer(entry.getKey());
                other.addPeer(pos);
            }
        }
    }

    private void unlinkDimensionGate(GlobalPos pos, DimensionGate gate) {
        for (GlobalPos peer : gate.getPeers()) {
            IEnergyNode node = devices.get(peer);
            if (node instanceof DimensionGate other) {
                other.removePeer(pos);
            }
        }
    }
}
