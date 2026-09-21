package com.iems.core.grid;

import com.iems.core.node.CoreDevice;
import com.iems.core.node.DimensionGate;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.TransferDevice;
import com.iems.diagnostics.GridDiagnostics;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 设备池（Registry）——模拟化架构的事实源（M9）。
 * <p>
 * 按 {@link GlobalPos} 索引所有已接入电网的设备；维护全局唯一核心；
 * 并维护 DimensionGate 的 pairId 配对关系（同 PID 门互加对端）。
 * </p>
 * <p>
 * <b>M9 模拟化语义</b>：注册表中的 POJO 节点是设备本体，BlockEntity 只是外观/视图。
 * 区块卸载<b>不再</b>注销设备——节点留在池中持续被调度器模拟（休眠模拟）；
 * 仅方块真正消失（setRemoved）时注销。BE 随区块重载时经
 * {@link #attachOrRegister} 采纳既有节点（模拟态比 BE NBT 新），不覆盖。
 * 重启后由 {@link #silentRegister} 从存档重建（设备工厂创建 + restoreState）。
 * </p>
 */
public class DeviceRegistry {

    private static final DeviceRegistry INSTANCE = new DeviceRegistry();

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<GlobalPos, IEnergyNode> devices = new ConcurrentHashMap<>();

    /**
     * 位置 → 设备工厂 ID（M9 持久化用）。
     * <p>
     * 注册时由外部模组显式传入（{@code IEMSAPI.attachOrRegisterDevice(pos, node, factoryId)}）；
     * 无工厂 ID 的设备不持久化——重启后由其 BlockEntity 的区块加载路径回归（优雅降级）。
     * </p>
     */
    private final Map<GlobalPos, String> deviceTypes = new ConcurrentHashMap<>();

    private volatile CoreDevice core;
    private volatile GlobalPos corePos;

    /** 核心的设备工厂 ID（持久化 Core.Factory 字段；null = 无工厂，走 BE 注册路径）。 */
    private volatile String coreFactoryId;

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
     * 待自动连接扫描的位置队列（M8.2）。
     * <p>
     * 设备注册后入队；模组层（IemsAutoConnector）在 tick 中出队执行
     * 「自动中继器 ↔ 非 TransferDevice 设备」的配对扫描。core 层不持有 Level，
     * 实际方块查询（黑名单过滤）由模组层完成。
     * 重建路径（silentRegister）不入队：连接已持久化，重扫会在未加载区块上
     * 触发方块查询。
     * </p>
     */
    private final Queue<GlobalPos> pendingAutoScans = new ConcurrentLinkedQueue<>();

    private DeviceRegistry() {
    }

    /** 全局单例（内部模块共享同一个设备池）。 */
    public static DeviceRegistry instance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // 设备注册（attach 语义）
    // -------------------------------------------------------------------------

    /**
     * 注册一个设备（兼容入口，无工厂 ID —— 该设备不持久化，重启后走 BE 回归路径）。
     * <p>内部转调 {@link #attachOrRegister}：同位置已有节点时采纳既有节点。</p>
     */
    public IEnergyNode register(GlobalPos pos, IEnergyNode node) {
        return attachOrRegister(pos, node, null);
    }

    /**
     * 注册或采纳一个设备（M9 attach 语义）。
     * <p>
     * 同位置已有节点（休眠模拟中/已由存档重建）→ <b>采纳既有节点并返回之</b>：
     * 模拟态比 BE NBT 新，BE 的构造期实例被丢弃；不覆盖、不入扫描队列。
     * 否则正常注册：入池、DimensionGate 配对、入自动扫描队列、协议检查。
     * </p>
     *
     * @param factoryId 设备工厂 ID（可空；非空时该设备持久化到 iems_grid.dat）
     * @return BE 应持有的节点（既有节点或新注册的 node）
     */
    public IEnergyNode attachOrRegister(GlobalPos pos, IEnergyNode node, String factoryId) {
        IEnergyNode existing = devices.get(pos);
        if (existing != null) {
            // 采纳既有节点：区块重载/重复注册场景，模拟态优先
            if (factoryId != null) {
                deviceTypes.put(pos, factoryId);
                markSavedDataDirty();
            }
            GridDiagnostics.event("=attach %s @ %s (simulated state kept)",
                    existing.getClass().getSimpleName(), pos);
            return existing;
        }

        devices.put(pos, node);
        if (node instanceof TransferDevice transferDevice) {
            transferDevice.setPosition(pos);
        }
        if (node instanceof DimensionGate gate) {
            linkDimensionGate(pos, gate);
        }
        if (factoryId != null) {
            deviceTypes.put(pos, factoryId);
        }
        GridDiagnostics.event("+register %s @ %s (proto=%s%s)",
                node.getClass().getSimpleName(), pos, node.getProtocolCost(),
                node instanceof TransferDevice t && t.isAutoConnect() ? ", auto" : "");
        // 新设备注册 → 触发自动连接扫描（自身与附近的自动中继器都可能是配对方）
        pendingAutoScans.add(pos);
        markSavedDataDirty();
        // 检查协议容量是否超限
        checkProtocolLimit();
        return node;
    }

    /**
     * 存档重建专用注册路径（服务器启动时由模组层调用）。
     * <p>
     * 与 {@link #attachOrRegister} 的差异：不入自动扫描队列（连接已持久化）；
     * 同位置已被 BE 抢先注册（启动期区块已加载）时跳过，以 BE 实例为准。
     * </p>
     */
    public void silentRegister(GlobalPos pos, IEnergyNode node, String factoryId) {
        IEnergyNode existing = devices.get(pos);
        if (existing != null) {
            GridDiagnostics.event("=skip rehydrate @ %s (already registered by BE)", pos);
            return;
        }
        devices.put(pos, node);
        if (node instanceof TransferDevice transferDevice) {
            transferDevice.setPosition(pos);
        }
        if (node instanceof DimensionGate gate) {
            linkDimensionGate(pos, gate);
        }
        if (factoryId != null) {
            deviceTypes.put(pos, factoryId);
        }
        GridDiagnostics.event("+rehydrate %s @ %s (factory=%s)",
                node.getClass().getSimpleName(), pos, factoryId);
        // 不标脏：数据与存档一致；不重扫：连接已持久化
        checkProtocolLimit();
    }

    /** 注销一个设备。若为 DimensionGate，则与同 PID 的对端解除关系。 */
    public void unregister(GlobalPos pos) {
        IEnergyNode removed = devices.remove(pos);
        deviceTypes.remove(pos);
        if (removed != null) {
            GridDiagnostics.event("-unregister %s @ %s",
                    removed.getClass().getSimpleName(), pos);
            markSavedDataDirty();
        }
        if (removed instanceof DimensionGate gate) {
            unlinkDimensionGate(pos, gate);
        }
        // 检查协议容量是否超限（可能因卸载恢复正常）
        checkProtocolLimit();
    }

    /** 取出一个待自动扫描的位置（无则返回 null，模组层 tick 驱动）。 */
    public GlobalPos pollPendingAutoScan() {
        return pendingAutoScans.poll();
    }

    /** 待自动扫描任务数（诊断输出用）。 */
    public int getPendingAutoScanCount() {
        return pendingAutoScans.size();
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

    /** 位置对应的设备工厂 ID（无则 null，GridSavedData.save 跳过该设备）。 */
    public String getDeviceType(GlobalPos pos) {
        return deviceTypes.get(pos);
    }

    // -------------------------------------------------------------------------
    // 核心注册（attach 语义）
    // -------------------------------------------------------------------------

    /** 注册核心（兼容入口，无工厂 ID —— 核心不持久化工厂字段，重启后走 BE 路径）。 */
    public CoreDevice registerCore(GlobalPos pos, CoreDevice core) {
        return attachOrRegisterCore(pos, core, null);
    }

    /**
     * 注册或采纳核心（M9 attach 语义，全局唯一）。
     * <p>
     * 同位置已有核心（存档重建/区块重载）→ 采纳既有核心并返回之（BE 的构造期
     * 实例丢弃，模拟态优先）；异位置已存在核心 → 拒绝注册并告警（返回传入实例，
     * 该方块为被拒核心，其后续注销经位置校验为无害空操作）。
     * </p>
     */
    public CoreDevice attachOrRegisterCore(GlobalPos pos, CoreDevice core, String factoryId) {
        if (this.core != null) {
            if (this.corePos != null && this.corePos.equals(pos)) {
                // 区块重载/存档重建后 BE 回归：采纳既有核心（模拟态优先）
                if (factoryId != null) {
                    this.coreFactoryId = factoryId;
                    markSavedDataDirty();
                }
                GridDiagnostics.event("=attach core @ %s (simulated state kept)", pos);
                return this.core;
            }
            LOGGER.warn("IEMS: 忽略重复核心注册（整个世界仅允许一个核心）。现有核心位置={}，被拒绝位置={}",
                    corePos, pos);
            GridDiagnostics.event("!! duplicate core rejected @ %s (existing @ %s)", pos, corePos);
            return core;
        }
        this.core = core;
        this.corePos = pos;
        this.coreFactoryId = factoryId;
        this.protocolShutdown = false;
        // B-2：IEMS 主动记忆核心状态 —— 若存档保存过同一位置的核心配置则恢复，
        // 使外部模组用默认参数 new 的核心在重启后仍保持真实配置（自发电/容量等）；
        // 随后标记存档脏，下次自动保存时由 save() 实时拉取核心状态写盘。
        // （存档重建路径 rehydrateCore 已直接 restoreState，不走此处。）
        GridSavedData saved = GridSavedData.active();
        if (saved != null) {
            if (saved.tryRestoreCore(pos, core)) {
                GridDiagnostics.event("+core restored persisted config @ %s", pos);
            }
            saved.markCoreDirty();
        }
        // 不调用 getPowerGenRate()：脉冲型核心（每 N tick 注入一次）依赖
        // 「每 tick 恰好被采样一次」的调用节拍，诊断日志的额外调用会扰动其相位
        GridDiagnostics.event("+core \"%s\" @ %s (limit=%s cap=%s)",
                core.getDeviceName(), pos, core.getProtocolLimit(),
                core.getEnergyCapacity());
        // 核心不入自动扫描队列（M9 裁定）：核心连接一律由玩家手动拉线，
        // 自动中继器也不把核心当作自动连接目标（见 IemsAutoConnector）
        markSavedDataDirty();
        // 核心就位改变全网通电语义（hasCore）→ 拓扑版本递增，驱动即时快照推送
        GridTopology.instance().touchVersion();
        // 核心就位后立即做一次协议容量裁定：
        // 覆盖「先放设备后放核心」场景（此时设备成本可能已超限）
        checkProtocolLimit();
        return core;
    }

    /**
     * 存档重建核心（服务器启动时由模组层调用，M9）。
     * <p>
     * 节点由设备工厂创建并已 restoreState（含 gridActive/能量），此处只接管注册表
     * 槽位并做协议裁定。不触发 B-2 tryRestoreCore（状态已恢复）与自动扫描。
     * </p>
     */
    public void rehydrateCore(GlobalPos pos, CoreDevice core, String factoryId) {
        if (this.core != null) {
            GridDiagnostics.event("=skip rehydrate core @ %s (core already present @ %s)", pos, corePos);
            return;
        }
        this.core = core;
        this.corePos = pos;
        this.coreFactoryId = factoryId;
        this.protocolShutdown = false;
        GridDiagnostics.event("+core rehydrated \"%s\" @ %s (limit=%s cap=%s)",
                core.getDeviceName(), pos, core.getProtocolLimit(),
                core.getEnergyCapacity());
        checkProtocolLimit();
    }

    /**
     * 注销核心（仅当 {@code pos} 与当前注册核心一致时生效）。
     * <p>
     * P1 修复：重复放置的核心方块注册被拒绝后仍留在世界，其注销
     * （拆除/区块卸载）此前会无条件清空真正在役的核心，导致有核心方块
     * 存在的电网瞬间失去核心。带位置校验后，被拒方块的注销为无害空操作。
     * </p>
     */
    public void unregisterCore(GlobalPos pos) {
        if (corePos == null) {
            return;
        }
        if (!corePos.equals(pos)) {
            GridDiagnostics.event("core unregister ignored @ %s (active core @ %s)", pos, corePos);
            return;
        }
        this.core = null;
        this.corePos = null;
        this.coreFactoryId = null;
        markSavedDataDirty();
        // 核心消失 = 全网断电语义 → 即时推送（激光变红反馈）
        GridTopology.instance().touchVersion();
    }

    /** 清空全部运行时状态（设备池/核心/类型表/扫描队列）。仅在服务器完全停止后由生命周期钩子调用（见 IEMSEvents）。 */
    public void clearAll() {
        devices.clear();
        deviceTypes.clear();
        core = null;
        corePos = null;
        coreFactoryId = null;
        protocolShutdown = false;
        pendingAutoScans.clear();
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

    /** 当前核心的设备工厂 ID（持久化 Core.Factory 字段；null = 无工厂，走 BE 注册路径）。 */
    public String getCoreFactoryId() {
        return coreFactoryId;
    }

    /**
     * 当前已用协议容量 = 已接入电网设备的协议成本之和。
     * <p>
     * 修复：仅<b>已接入电网</b>（存在至少一条连接，或维度门已有配对）的设备
     * 才占用协议容量；摆放在世界上但尚未接线的设备不占容量——协议容量是
     * 「已接线设备数量」的约束（见白皮书第7章），未接线设备待其被自动/
     * 手动接线后（连接增删经 {@link GridTopology} 触发 {@link #recheckProtocolLimit()}）
     * 计入/移出。
     * </p>
     * <p>
     * M9+ 逐设备化：外部 FE 设备经 DeviceAdapter 伪装成独立节点注册进池
     * （ADAPTER_BRIDGE 端点均在池内），其协议成本 = 节点自身的
     * {@code getProtocolCost() = 1}，随上述 wired 统计自然计入——不再需要
     * 额外的 externalBridgeCost 记账（避免双计）。
     * </p>
     */
    public BigInteger getProtocolUsed() {
        Set<GlobalPos> wired = wiredPositions();
        BigInteger sum = BigInteger.ZERO;
        for (Map.Entry<GlobalPos, IEnergyNode> entry : devices.entrySet()) {
            if (wired.contains(entry.getKey())) {
                sum = sum.add(entry.getValue().getProtocolCost());
            }
        }
        return sum;
    }

    /**
     * 已接入电网的设备位置集合 = 显式连接两端点 + 已有配对的维度门。
     * <p>与 {@link GridTopology#buildAdjacency()} 的边集合口径保持一致。</p>
     */
    private Set<GlobalPos> wiredPositions() {
        Set<GlobalPos> wired = new HashSet<>();
        for (Connection c : GridTopology.instance().getConnections()) {
            wired.add(c.start());
            wired.add(c.end());
        }
        for (Map.Entry<GlobalPos, IEnergyNode> entry : devices.entrySet()) {
            if (entry.getValue() instanceof DimensionGate gate && !gate.getPeers().isEmpty()) {
                wired.add(entry.getKey());
            }
        }
        return wired;
    }

    /**
     * 重新裁定协议容量（连接增删后由 {@link GridTopology} 调用）。
     * <p>接线/断线会改变「已接入电网」的设备集合，从而改变已用容量——
     * 必须重新执行容量检查，使超限关停 / 回到限内自动恢复保持正确。</p>
     */
    public void recheckProtocolLimit() {
        checkProtocolLimit();
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
            GridDiagnostics.event("SHUTDOWN (protocol over limit): used=%s > limit=%s", used, limit);
            GridTopology.instance().rebuild();
        } else if (!over && !core.isGridActive() && protocolShutdown) {
            // 协议关停 + 用量已回到限内 → 自动恢复供电
            core.setGridActive(true);
            protocolShutdown = false;
            GridDiagnostics.event("RESUME (protocol back in limit): used=%s <= limit=%s", used, limit);
            GridTopology.instance().rebuild();
        }
        // 其余情况（正常/手动关停/已关停）不动作
    }

    /**
     * 设备/核心槽位变更时标记存档脏（M9）。
     * <p>
     * save() 实时拉取注册表全量写盘，故此处只需标脏、不逐条写入。
     * 未挂载存档（服务器启动前/停止后）时静默跳过。
     * </p>
     */
    private static void markSavedDataDirty() {
        GridSavedData data = GridSavedData.active();
        if (data != null) {
            data.markDirty();
        }
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
