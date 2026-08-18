package com.iems.core.grid;

import com.iems.core.node.CoreDevice;
import com.iems.core.node.DimensionGate;
import com.iems.core.node.IEnergyNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
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

    private GridTopology(DeviceRegistry registry) {
        this.registry = registry;
    }

    public static GridTopology instance() {
        return INSTANCE;
    }

    // ---------- 连接管理 ----------

    /** 添加一条连接（若已存在相同连接则忽略）。 */
    public void addConnection(Connection connection) {
        if (connections.add(connection)) {
            rebuild();
        }
    }

    /** 移除一条连接。 */
    public void removeConnection(Connection connection) {
        if (connections.remove(connection)) {
            rebuild();
        }
    }

    /** 当前全部连接（只读）。 */
    public Set<Connection> getConnections() {
        return connections;
    }

    // ---------- 拓扑重建（BFS 两阶段） ----------

    /** 触发完整 BFS 重扫，更新快照。 */
    public synchronized void rebuild() {
        CoreDevice core = registry.getCore();
        GlobalPos corePos = registry.getCorePos();

        if (core == null || corePos == null) {
            this.snapshot = GridSnapshot.empty();
            return;
        }

        // 先刷新所有设备的协议成本（支持动态计算）
        for (IEnergyNode node : registry.getAll()) {
            node.refreshProtocolCost();
        }

        Map<GlobalPos, Set<GlobalPos>> adjacency = buildAdjacency();
        Set<Connection> pending = findPendingConnections();

        // 第一阶段：从核心出发 BFS，得到 mainNetwork
        Set<GlobalPos> mainNetwork = bfs(corePos, adjacency, registry);

        // 第二阶段：识别孤岛网络
        List<Set<GlobalPos>> orphanNetworks = findOrphans(mainNetwork, adjacency);

        boolean shutdown = !core.isGridActive();

        this.snapshot = new GridSnapshot(mainNetwork, orphanNetworks, pending, shutdown, corePos);
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

    /** 边界连接：连接存在但任一端点设备未注册（区块未加载/设备未放置）。 */
    private Set<Connection> findPendingConnections() {
        Set<Connection> pending = new HashSet<>();
        for (Connection connection : connections) {
            if (registry.get(connection.start()) == null || registry.get(connection.end()) == null) {
                pending.add(connection);
            }
        }
        return pending;
    }

    /** BFS 从 start 出发，仅穿越已注册设备节点。 */
    private Set<GlobalPos> bfs(GlobalPos start, Map<GlobalPos, Set<GlobalPos>> adjacency, DeviceRegistry registry) {
        Set<GlobalPos> visited = new HashSet<>();
        Deque<GlobalPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            GlobalPos current = queue.poll();
            for (GlobalPos next : adjacency.getOrDefault(current, Set.of())) {
                if (!visited.contains(next) && registry.get(next) != null) {
                    visited.add(next);
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
            Set<GlobalPos> cluster = bfs(start, adjacency, registry);
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
