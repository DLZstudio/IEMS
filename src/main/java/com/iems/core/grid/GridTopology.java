package com.iems.core.grid;

import com.iems.core.node.CoreDevice;
import com.iems.core.node.DimensionGate;
import com.iems.core.node.IEnergyNode;
import com.iems.diagnostics.GridDiagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 电网拓扑（GridTopology）。
 * <p>
 * 内部单例服务，负责基于设备池与连接集合执行完整的全局 BFS 遍历，
 * 生成 {@link GridSnapshot} 快照。电网状态变更（核心/设备注册注销、连接增删、
 * 协议容量变更）后调用 {@link #rebuild()} 重建快照。
 * </p>
 * <p>
 * BFS 两阶段：
 * 第一阶段从核心出发遍历所有可达设备，得到 mainNetwork，并记录边界连接；
 * 第二阶段在剩余设备中 BFS 分组，得到 orphanNetworks（维度隔离的图通过
 * DimensionGate 节点联通，跨维度跳转由 peers 邻接提供）。
 * </p>
 */
public class GridTopology {

    private static final GridTopology INSTANCE = new GridTopology(DeviceRegistry.instance());

    private final DeviceRegistry registry;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private volatile GridSnapshot snapshot = GridSnapshot.empty();

    /**
     * 拓扑版本号：每次 {@link #rebuild()}（连接增删/幽灵清理/强制重扫）递增。
     * <p>
     * 模组层（IEMSEvents）比对版本号在变更后<b>立即</b>推送电网快照，
     * 不等 40 tick 周期帧——手动拉线建连/拆设备断连的激光反馈延迟
     * 从最长 2 秒降到 ≤1 tick（旧版即时增量推送的等效实现，
     * 复用单一全量帧协议，不引入增量 payload）。
     * </p>
     */
    private volatile int topologyVersion;

    /**
     * 连接变更回调（由模组层注册，指向 {@link GridSavedData} 的标记脏操作）。
     * <p>
     * 逻辑层不直接依赖持久化设施，通过此回调在连接增删时通知存档层落盘。
     * </p>
     */
    private static volatile Runnable connectionsDirtyCallback;

    private GridTopology(DeviceRegistry registry) {
        this.registry = registry;
    }

    public static GridTopology instance() {
        return INSTANCE;
    }

    /** 当前拓扑版本号（模组层比对用，见 {@link #topologyVersion} 注释）。 */
    public int topologyVersion() {
        return topologyVersion;
    }

    /**
     * 标记拓扑语义已变化（不触发 BFS 重扫）。
     * <p>核心注册/注销不走连接表变更路径，但核心存在与否改变全部激光的
     * 通电语义（hasCore），需同样驱动即时推送。</p>
     */
    public void touchVersion() {
        topologyVersion++;
    }

    // ---------- 连接管理 ----------

    /** 添加一条连接（若已存在相同连接则忽略）。 */
    public void addConnection(Connection connection) {
        if (connections.add(connection)) {
            GridDiagnostics.event("+conn %s <-> %s [%s]",
                    connection.start(), connection.end(), connection.type());
            markConnectionsDirty();
            rebuild();
            // 接线改变「已接入电网」设备集合 → 重新裁定协议容量
            registry.recheckProtocolLimit();
        }
    }

    /** 移除一条连接。 */
    public void removeConnection(Connection connection) {
        if (connections.remove(connection)) {
            GridDiagnostics.event("-conn %s <-> %s [%s]",
                    connection.start(), connection.end(), connection.type());
            markConnectionsDirty();
            rebuild();
            // 断线可能释放协议容量 → 重新裁定（可能自动复电）
            registry.recheckProtocolLimit();
        }
    }

    /**
     * 移除所有触及 pos 的连接（设备/核心注销时调用），单次重建（M9）。
     * <p>
     * 幽灵连接修复：设备被破坏后其连接若不清理，拓扑与渲染将永久残留
     * 已注销端点（findPendingConnections 永久挂账、激光指向不存在的方块）。
     * </p>
     */
    public void removeConnectionsAt(GlobalPos pos) {
        List<Connection> toRemove = new ArrayList<>();
        for (Connection c : connections) {
            if (c.start().equals(pos) || c.end().equals(pos)) {
                toRemove.add(c);
            }
        }
        if (!toRemove.isEmpty()) {
            connections.removeAll(toRemove);
            GridDiagnostics.event("-conn×%d (device removed) @ %s", toRemove.size(), pos);
            markConnectionsDirty();
            rebuild();
            // 设备注销断线 → 释放协议容量 → 重新裁定
            registry.recheckProtocolLimit();
        }
    }

    /** 当前全部连接（只读）。 */
    public Set<Connection> getConnections() {
        return connections;
    }

    /**
     * 指定两端点与类型的连接是否已存在。
     * <p>身份判定不含锚点（见 {@link Connection}），默认锚点构造即可匹配
     * 已存的带锚点连接。</p>
     */
    public boolean hasConnection(GlobalPos a, GlobalPos b, ConnectionType type) {
        return connections.contains(Connection.of(a, b, type));
    }

    /**
     * 从存档恢复连接（服务器启动时由 IEMSEvents 调用）。
     * <p>
     * 存档数据是唯一事实源：启动时以存档内容<b>覆盖</b>内存中的连接集合
     * （level 加载期间可能产生的内存连接一律以存档为准）。
     * </p>
     */
    public void loadConnections(Collection<Connection> loaded) {
        connections.clear();
        connections.addAll(loaded);
        rebuild();
        registry.recheckProtocolLimit();
    }

    /** 清空全部运行时状态（连接/快照）。仅在服务器完全停止后由生命周期钩子调用，不标记存档脏。 */
    public void clearAll() {
        connections.clear();
        snapshot = GridSnapshot.empty();
    }

    /** 注册/注销连接变更回调（连接增删时触发，供存档层标记脏）。 */
    public static void setConnectionsDirtyCallback(Runnable callback) {
        connectionsDirtyCallback = callback;
    }

    private static void markConnectionsDirty() {
        Runnable callback = connectionsDirtyCallback;
        if (callback != null) {
            callback.run();
        }
    }

    // ---------- 拓扑重建（BFS 两阶段） ----------

    /** 触发完整 BFS 重扫，更新快照。 */
    public synchronized void rebuild() {
        topologyVersion++;
        CoreDevice core = registry.getCore();
        GlobalPos corePos = registry.getCorePos();

        if (core == null || corePos == null) {
            this.snapshot = GridSnapshot.empty();
            GridDiagnostics.event("rebuild: no core (dev=%d conn=%d)",
                    registry.size(), connections.size());
            return;
        }

        // 先刷新所有设备的协议成本（支持动态计算）
        for (IEnergyNode node : registry.getAll()) {
            node.refreshProtocolCost();
        }

        Map<GlobalPos, Set<GlobalPos>> adjacency = buildAdjacency();
        Set<Connection> pending = findPendingConnections();

        // 第一阶段：从核心出发 BFS，得到 mainNetwork 与逐设备深度（M9 波接力）
        Map<GlobalPos, Integer> depths = bfsWithDepth(corePos, adjacency, registry);
        Set<GlobalPos> mainNetwork = depths.keySet();
        int maxDepth = 0;
        for (int d : depths.values()) {
            maxDepth = Math.max(maxDepth, d);
        }

        // 第二阶段：识别孤岛网络
        List<Set<GlobalPos>> orphanNetworks = findOrphans(mainNetwork, adjacency);

        boolean shutdown = !core.isGridActive();

        this.snapshot = new GridSnapshot(mainNetwork, orphanNetworks, pending, shutdown, corePos,
                depths, maxDepth);
        GridDiagnostics.event("rebuild: main=%d orphan=%d pending=%d shutdown=%s depth=%d",
                mainNetwork.size(), orphanNetworks.size(), pending.size(), shutdown, maxDepth);
    }

    /** 构建邻接表：显式连接 + DimensionGate 同 PID 对端（跨维度桥接）。 */
    private Map<GlobalPos, Set<GlobalPos>> buildAdjacency() {
        Map<GlobalPos, Set<GlobalPos>> adjacency = new HashMap<>();
        for (Connection connection : connections) {
            addEdge(adjacency, connection.start(), connection.end());
        }
        for (GlobalPos pos : registry.getAllPositions()) {
            IEnergyNode node = registry.get(pos);
            if (node instanceof DimensionGate gate) {
                for (GlobalPos peer : gate.getPeers()) {
                    addEdge(adjacency, pos, peer);
                }
            }
        }
        return adjacency;
    }

    private void addEdge(Map<GlobalPos, Set<GlobalPos>> adjacency, GlobalPos a, GlobalPos b) {
        adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

    /**
     * 边界连接：连接存在但任一端点设备未注册（区块未加载/设备未放置）。
     * <p>
     * 两类豁免（P0 修复 + M9）：
     * 核心端点不在设备池（由 corePos 单独索引），视同已注册——否则
     * 核心↔中继的手动连接被永久挂为边界连接，激光不渲染；
     * {@link ConnectionType#ADAPTER_BRIDGE} 连接由 DA 适配器自行维护生命周期，
     * 端点（伪装节点）注册/注销与连接增删经 FEDA.sync 同帧落定，不挂边界。
     * </p>
     */
    private Set<Connection> findPendingConnections() {
        Set<Connection> pending = new HashSet<>();
        GlobalPos corePos = registry.getCorePos();
        for (Connection connection : connections) {
            if (connection.type() == ConnectionType.ADAPTER_BRIDGE) {
                continue;
            }
            boolean startValid = registry.get(connection.start()) != null
                    || connection.start().equals(corePos);
            boolean endValid = registry.get(connection.end()) != null
                    || connection.end().equals(corePos);
            if (!startValid || !endValid) {
                pending.add(connection);
            }
        }
        return pending;
    }

    /**
     * BFS 从 start 出发，仅穿越已注册设备节点，返回逐设备深度（start = 0）。
     * <p>深度 = 跨越的连接数（BFS 层序天然按层递增），驱动客户端波接力动画。</p>
     */
    private Map<GlobalPos, Integer> bfsWithDepth(GlobalPos start,
                                                 Map<GlobalPos, Set<GlobalPos>> adjacency,
                                                 DeviceRegistry registry) {
        Map<GlobalPos, Integer> visited = new HashMap<>();
        Deque<GlobalPos> queue = new ArrayDeque<>();
        visited.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            GlobalPos current = queue.poll();
            int nextDepth = visited.get(current) + 1;
            for (GlobalPos next : adjacency.getOrDefault(current, Set.of())) {
                if (!visited.containsKey(next) && registry.get(next) != null) {
                    visited.put(next, nextDepth);
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    /** 第二阶段：在 mainNetwork 之外的设备中 BFS 分组，得到孤岛集群。 */
    private List<Set<GlobalPos>> findOrphans(Set<GlobalPos> mainNetwork, Map<GlobalPos, Set<GlobalPos>> adjacency) {
        Set<GlobalPos> remaining = new HashSet<>(registry.getAllPositions());
        remaining.removeAll(mainNetwork);
        List<Set<GlobalPos>> orphans = new ArrayList<>();
        while (!remaining.isEmpty()) {
            GlobalPos start = remaining.iterator().next();
            Set<GlobalPos> cluster = bfsWithDepth(start, adjacency, registry).keySet();
            cluster.retainAll(remaining);
            // 过滤空集群（理论上不应出现，但防御性处理）
            if (!cluster.isEmpty()) {
                orphans.add(cluster);
                remaining.removeAll(cluster);
            } else {
                // 孤立节点：直接从 remaining 移除
                remaining.remove(start);
            }
        }
        return orphans;
    }

    // ---------- 查询 ----------

    /** 当前快照。 */
    public GridSnapshot getSnapshot() {
        return snapshot;
    }

    /** 指定位置是否可达核心（位于 mainNetwork）。 */
    public boolean isReachable(GlobalPos pos) {
        return snapshot.isReachable(pos);
    }
}
