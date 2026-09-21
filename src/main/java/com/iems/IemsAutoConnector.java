package com.iems;

import com.iems.api.IEMSAPI;
import com.iems.core.grid.ConnectionType;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.TransferDevice;
import com.iems.diagnostics.GridDiagnostics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * 自动连接扫描器（M8.2，模组层）。
 * <p>
 * 消费 {@link DeviceRegistry#pollPendingAutoScan()} 的扫描队列：
 * 设备注册后入队，本类在每 tick 出队执行
 * 「自动中继器 ↔ 非中继设备」的配对扫描并建连。
 * </p>
 * <p>
 * 配对规则（自动发现语义）：
 * </p>
 * <ul>
 *   <li><b>结构排除</b>：目标必须不是 {@link TransferDevice}——自动中继器
 *       只接入 FE/SE 设备（生产者/消费者/储能），中继器之间、以及
 *       中继器与核心之间的连线一律由玩家手动拉线或维度门桥接（M9 裁定：
 *       核心连接一律手动）；</li>
 *   <li><b>距离</b>：以中继器声明的 {@code maxConnectionDistance} 为准；</li>
 *   <li><b>黑白名单</b>：中继器声明的方块 ID 列表过滤目标端方块
 *       （黑名单排除连接设备方块是测试模组的用法示例）；</li>
 *   <li><b>幂等</b>：{@code addConnection} 对已存在连接静默忽略，
 *       区块重载后重扫不会产生重复连接。</li>
 * </ul>
 */
public final class IemsAutoConnector {

    /** 每 tick 最多处理的扫描任务数（限制单 tick 开销，积压顺延）。 */
    private static final int SCANS_PER_TICK = 8;

    private IemsAutoConnector() {
    }

    /** 每 tick 由 IEMSEvents.onServerTick 调用。 */
    public static void tick(MinecraftServer server) {
        DeviceRegistry registry = DeviceRegistry.instance();
        for (int i = 0; i < SCANS_PER_TICK; i++) {
            GlobalPos pos = registry.pollPendingAutoScan();
            if (pos == null) {
                return;
            }
            IEnergyNode node = registry.get(pos);
            if (node instanceof TransferDevice relay && relay.isAutoConnect()) {
                // 自动中继器注册 → 扫描附近可接入的非中继设备
                scanFromRelay(server, registry, pos, relay);
            } else if (node != null) {
                // 非中继设备注册 → 扫描附近可接入它的自动中继器
                scanFromDevice(server, registry, pos);
            }
            // node == null：设备已注销（区块卸载竞争），丢弃扫描任务。
            // 核心不入队（M9 裁定：核心连接一律手动拉线，见 attachOrRegisterCore）
        }
    }

    /** 自动中继器注册：对范围内所有非中继设备建连（不含核心）。 */
    private static void scanFromRelay(MinecraftServer server, DeviceRegistry registry,
                                      GlobalPos relayPos, TransferDevice relay) {
        int connected = 0;

        for (GlobalPos target : registry.getAllPositions()) {
            if (target.equals(relayPos) || !target.dimension().equals(relayPos.dimension())) {
                continue;
            }
            IEnergyNode targetNode = registry.get(target);
            // 结构排除：只连接 FE/SE 设备，不连接任何中继器；
            // 伪装节点（DeviceAdapter 名下外部设备）只走 ADAPTER_BRIDGE，跳过
            if (targetNode == null || targetNode instanceof TransferDevice
                    || targetNode instanceof com.iems.adapter.IAdapterNode) {
                continue;
            }
            connected += tryConnect(server, relay, relayPos, target);
        }

        if (connected == 0) {
            GridDiagnostics.event("auto-scan(relay) %s: no candidate within %dm",
                    relayPos, relay.getMaxConnectionDistance());
        }
    }

    /** 非中继设备注册：扫描范围内可接入它的自动中继器。 */
    private static void scanFromDevice(MinecraftServer server, DeviceRegistry registry,
                                       GlobalPos devicePos) {
        for (GlobalPos relayPos : registry.getAllPositions()) {
            if (relayPos.equals(devicePos) || !relayPos.dimension().equals(devicePos.dimension())) {
                continue;
            }
            IEnergyNode node = registry.get(relayPos);
            if (!(node instanceof TransferDevice relay) || !relay.isAutoConnect()) {
                continue;
            }
            tryConnect(server, relay, relayPos, devicePos);
        }
    }

    /** 距离 + 黑白名单校验后建连。返回 1 表示新建连接，0 表示未建。 */
    private static int tryConnect(MinecraftServer server, TransferDevice relay,
                                  GlobalPos relayPos, GlobalPos target) {
        double distance = Math.sqrt(relayPos.pos().distSqr(target.pos()));
        if (distance > relay.getMaxConnectionDistance()) {
            return 0;
        }
        if (!passesFilters(server, relay, target)) {
            return 0;
        }
        IEMSAPI.addConnection(relayPos, target, ConnectionType.RELAY_TO_DEVICE);
        GridDiagnostics.event("auto+conn %s <-> %s (%.0fm)", relayPos, target, distance);
        return 1;
    }

    /**
     * 黑白名单过滤：按目标端方块 ID 判定（列表为空 = 不限制）。
     * <p>
     * 名单条目为方块注册 ID（如 {@code iemstest:auto_relay}）。
     * 维度未加载无法解析方块时保守拒绝；区块未加载时同样保守拒绝——
     * 对未加载区块调 {@code getBlockState} 会触发同步区块加载（M9 修复，
     * 模拟化后休眠设备所在区块常处于未加载状态）。
     * </p>
     */
    private static boolean passesFilters(MinecraftServer server, TransferDevice relay, GlobalPos target) {
        List<String> whitelist = relay.getWhitelist();
        List<String> blacklist = relay.getBlacklist();
        if (whitelist.isEmpty() && blacklist.isEmpty()) {
            return true;
        }
        ServerLevel level = server.getLevel(target.dimension());
        if (level == null) {
            return false;
        }
        if (!level.hasChunkAt(target.pos())) {
            return false;
        }
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(target.pos()).getBlock());
        String id = blockId.toString();
        if (!whitelist.isEmpty() && !whitelist.contains(id)) {
            return false;
        }
        return !blacklist.contains(id);
    }
}
