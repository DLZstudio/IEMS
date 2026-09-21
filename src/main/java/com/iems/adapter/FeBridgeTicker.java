package com.iems.adapter;

import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.TransferDevice;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * DA 驱动器（模组层，原 FeBridgeTicker 三阶段驱动的逐设备化重构）。
 * <p>
 * 原三阶段（tickPull / tickRescan / tickPush）演化为对全部<b>支持自动连接</b>
 * 的传输设备名下可插拔适配器的统一驱动：
 * </p>
 * <ul>
 *   <li><b>每 tick</b> {@link #tick}：{@link DeviceAdapter#tick} 抽取 FE 喂
 *       生产节点缓冲、测余量喂需求申报、把推送池送抵外部（抽取与测余量在
 *       结算前完成，数值供本 tick 调度器消费）；同时清扫孤儿伪装节点
 *       （宿主中继器已注销 → 逐台注销名下节点，含 ADAPTER_BRIDGE 连接自动移除）；</li>
 *   <li><b>每 40 tick</b> {@link #tickRescan}：{@link DeviceAdapter#sync}
 *       对比扫描报告与已注册节点，新设备注册建连、消失设备注销
 *       （存量直查，规避 DiscoveryScanner 跳过已注册位置的问题）。</li>
 * </ul>
 * <p>
 * 区块守卫：目标区块未加载时跳过（不触发同步区块加载），与
 * IemsAutoConnector 的保守策略一致。
 * </p>
 */
public final class FeBridgeTicker {

    /** 目标重扫间隔（tick）：40 tick = 2 秒。 */
    private static final int RESCAN_INTERVAL_TICKS = 40;

    private FeBridgeTicker() {
    }

    /**
     * 每 tick 驱动：须在 {@code EnergyDispatcher.dispatchAtTick} 之前调用——
     * 抽取量与需求申报在本阶段落定，供随后的结算消费。
     */
    public static void tick(MinecraftServer server) {
        DeviceRegistry registry = DeviceRegistry.instance();
        for (GlobalPos pos : registry.getAllPositions()) {
            IEnergyNode node = registry.get(pos);
            if (node instanceof TransferDevice relay && relay.isAutoConnect()) {
                ServerLevel level = server.getLevel(pos.dimension());
                if (level == null) {
                    continue;
                }
                for (DeviceAdapter adapter : relay.getAdapters()) {
                    adapter.tick(level);
                }
            } else if (node instanceof IAdapterNode adapterNode && adapterNode.isOrphaned()) {
                // 宿主中继器已注销 → 清扫其名下伪装节点（无连接，不再被调度）
                adapterNode.owner().dropNode(pos);
            }
        }
    }

    /** 重扫阶段：每 40 tick 驱动全部自动中继器名下适配器的 diff 同步。 */
    public static void tickRescan(MinecraftServer server, int serverTick) {
        if (serverTick % RESCAN_INTERVAL_TICKS != 0) {
            return;
        }
        DeviceRegistry registry = DeviceRegistry.instance();
        for (GlobalPos pos : registry.getAllPositions()) {
            IEnergyNode node = registry.get(pos);
            if (node instanceof TransferDevice relay && relay.isAutoConnect()) {
                ServerLevel level = server.getLevel(pos.dimension());
                if (level == null) {
                    continue;
                }
                for (DeviceAdapter adapter : relay.getAdapters()) {
                    adapter.sync(level);
                }
            }
        }
    }
}
