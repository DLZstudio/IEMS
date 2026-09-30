package com.iems.core.grid;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 电网拓扑快照。
 * <p>
 * 由 {@link GridTopology} 在每次电网状态变更后生成，描述当前电网的完整拓扑视角：
 * 核心可达设备（mainNetwork）、孤岛网络（orphanNetworks）、边界连接（pendingConnections）、
 * 关停状态与核心位置。外部模块（如设备查询、渲染）应只读快照，不直接访问设备池。
 * </p>
 */
public class GridSnapshot {

    /** 核心可达设备集合（含核心自身）。 */
    private final Set<GlobalPos> mainNetwork;

    /** 孤岛集群列表：每个集合是一个互不相连的设备集群。 */
    private final List<Set<GlobalPos>> orphanNetworks;

    /** 边界连接：连接存在但远端设备未注册/未加载。 */
    private final Set<Connection> pendingConnections;

    /** 电网关停状态（核心 gridActive == false 时为 true）。 */
    private final boolean gridShutdown;

    /** 核心位置（无核心时为 null）。 */
    private final GlobalPos corePos;

    /**
     * 主网设备的 BFS 深度（核心 = 0，每跨一条连接 +1，M9 波接力）。
     * <p>
     * 驱动客户端通电/断电波沿链路接力推进：激光段的就绪延迟按两端深度
     * 插值——第二跳起点要等第一跳长度的时间才亮起，能量从核心
     * 一跳跳流向全网（旧版逐设备就绪时间表的精简等效实现）。
     * </p>
     */
    private final Map<GlobalPos, Integer> deviceDepths;

    /** 主网最大深度（波动画总时长基准）。无核心时为 0。 */
    private final int maxDepth;

    public GridSnapshot(Set<GlobalPos> mainNetwork,
                        List<Set<GlobalPos>> orphanNetworks,
                        Set<Connection> pendingConnections,
                        boolean gridShutdown,
                        GlobalPos corePos) {
        this(mainNetwork, orphanNetworks, pendingConnections, gridShutdown, corePos,
                Map.of(), 0);
    }

    public GridSnapshot(Set<GlobalPos> mainNetwork,
                        List<Set<GlobalPos>> orphanNetworks,
                        Set<Connection> pendingConnections,
                        boolean gridShutdown,
                        GlobalPos corePos,
                        Map<GlobalPos, Integer> deviceDepths,
                        int maxDepth) {
        this.mainNetwork = Collections.unmodifiableSet(mainNetwork);
        this.orphanNetworks = orphanNetworks.stream().map(Collections::unmodifiableSet).toList();
        this.pendingConnections = Collections.unmodifiableSet(pendingConnections);
        this.gridShutdown = gridShutdown;
        this.corePos = corePos;
        this.deviceDepths = Collections.unmodifiableMap(deviceDepths);
        this.maxDepth = maxDepth;
    }

    /** 空快照（无核心或尚未首次扫描）。 */
    public static GridSnapshot empty() {
        return new GridSnapshot(Set.of(), List.of(), Set.of(), false, null);
    }

    /** 核心可达设备集合。 */
    public Set<GlobalPos> getMainNetwork() {
        return mainNetwork;
    }

    /** 孤岛网络列表。 */
    public List<Set<GlobalPos>> getOrphanNetworks() {
        return orphanNetworks;
    }

    /** 边界连接集合。 */
    public Set<Connection> getPendingConnections() {
        return pendingConnections;
    }

    /** 是否处于关停状态。 */
    public boolean isGridShutdown() {
        return gridShutdown;
    }

    /** 核心位置（无核心时 null）。 */
    public GlobalPos getCorePos() {
        return corePos;
    }

    /** 主网设备 BFS 深度表（核心 = 0；无核心时为空表）。 */
    public Map<GlobalPos, Integer> getDeviceDepths() {
        return deviceDepths;
    }

    /** 主网最大 BFS 深度（无核心时为 0）。 */
    public int getMaxDepth() {
        return maxDepth;
    }

    /** 指定位置是否位于核心可达网络（已接入电网）。 */
    public boolean isReachable(GlobalPos pos) {
        return mainNetwork.contains(pos);
    }

    /** 电网是否为空（无核心）。 */
    public boolean isEmpty() {
        return corePos == null;
    }

    @Override
    public String toString() {
        return "GridSnapshot{main=" + mainNetwork.size()
                + ", orphans=" + orphanNetworks.size()
                + ", pending=" + pendingConnections.size()
                + ", shutdown=" + gridShutdown
                + ", core=" + corePos
                + ", maxDepth=" + maxDepth + '}';
    }
}
