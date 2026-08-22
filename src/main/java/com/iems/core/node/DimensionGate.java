package com.iems.core.node;

import com.iems.core.grid.GlobalPos;
import net.minecraft.advancements.critereon.BlockPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 维度门（DimensionGate）。
 * <p>
 * 跨维度桥接设备：每个门持有一个 pairId，相同 pairId 的门自动组成一个桥接网络，
 * 能量从任意门进入都广播到同 PID 的所有门（分布式节点思维，非路由器多网口）。
 * 玩家体验：shift+右键接入电网（与 TransferDevice 一致），消耗大量协议容量。
 * </p>
 */
public class DimensionGate implements IEnergyNode {

    private static final Pattern PAIR_ID_PATTERN = Pattern.compile("[0-9A-F]{16}");

    private final String deviceName;
    private final BigInteger protocolCost;

    private final String pairId;

    private final boolean autoBroadcast;
    private final int maxPeers;

    private final BlockPos structureSize;
    private final List<BlockPredicate> frameBlocks;
    private final BlockPos coreBlock;

    private final double dimensionScale;
    private final boolean useCustomScale;

    /** 同 PID 的其他门位置（运行时由注册表维护）。 */
    private final Set<GlobalPos> peers = ConcurrentHashMap.newKeySet();

    /**
     * 便捷构造器：单方块门，自动广播、无对端上限，默认距离比例。
     */
    public DimensionGate(String deviceName, BigInteger protocolCost, String pairId) {
        this(deviceName, protocolCost, pairId, true, 0, null, null, null, 1.0, false);
    }

    /**
     * 完整构造器。
     *
     * @param deviceName    设备名称
     * @param protocolCost  协议容量占用（跨维度成本更高，建议较大）
     * @param pairId        配对 ID（16 位十六进制大写）
     * @param autoBroadcast 是否自动广播能量到同 PID 的其他门
     * @param maxPeers      最大可连接对端数量（0 = 无限制）
     * @param structureSize 多方块结构尺寸（null = 单方块）
     * @param frameBlocks   框架方块类型列表（null = 无）
     * @param coreBlock     核心方块相对坐标（null = 无）
     * @param dimensionScale 当前维度→目标维度的距离换算比例
     * @param useCustomScale 是否使用自定义比例（false = 从预置表读取）
     */
    public DimensionGate(String deviceName,
                         BigInteger protocolCost,
                         String pairId,
                         boolean autoBroadcast,
                         int maxPeers,
                         BlockPos structureSize,
                         List<BlockPredicate> frameBlocks,
                         BlockPos coreBlock,
                         double dimensionScale,
                         boolean useCustomScale) {
        if (!PAIR_ID_PATTERN.matcher(pairId).matches()) {
            throw new IllegalArgumentException("pairId 必须是 16 位十六进制大写字符: " + pairId);
        }
        this.deviceName = deviceName;
        this.protocolCost = protocolCost;
        this.pairId = pairId;
        this.autoBroadcast = autoBroadcast;
        this.maxPeers = maxPeers;
        this.structureSize = structureSize;
        this.frameBlocks = frameBlocks == null ? List.of() : new ArrayList<>(frameBlocks);
        this.coreBlock = coreBlock;
        this.dimensionScale = dimensionScale;
        this.useCustomScale = useCustomScale;
    }

    @Override
    public String getDeviceName() {
        return deviceName;
    }

    @Override
    public BigInteger getProtocolCost() {
        return protocolCost;
    }

    public String getPairId() {
        return pairId;
    }

    /** 是否已完成配对（是否存在至少一个同 PID 的其他门）。 */
    public boolean isPaired() {
        return !peers.isEmpty();
    }

    public boolean isAutoBroadcast() {
        return autoBroadcast;
    }

    public int getMaxPeers() {
        return maxPeers;
    }

    public BlockPos getStructureSize() {
        return structureSize;
    }

    public List<BlockPredicate> getFrameBlocks() {
        return Collections.unmodifiableList(frameBlocks);
    }

    public BlockPos getCoreBlock() {
        return coreBlock;
    }

    public double getDimensionScale() {
        return dimensionScale;
    }

    public boolean isUseCustomScale() {
        return useCustomScale;
    }

    public Set<GlobalPos> getPeers() {
        return Collections.unmodifiableSet(peers);
    }

    /** 是否还能接受新对端（maxPeers=0 表示无限制）。 */
    public boolean canAcceptPeer() {
        return maxPeers <= 0 || peers.size() < maxPeers;
    }

    /**
     * 注册表维护：加入一个同 PID 对端。
     * <p>
     * V-07 修复：超出 maxPeers 时返回 false 拒绝配对（不再抛异常，
     * 避免异常冒泡炸掉外部模组的放置逻辑）。
     * </p>
     *
     * @return true 表示配对成功；false 表示被 maxPeers 拒绝或已存在
     */
    public boolean addPeer(GlobalPos pos) {
        if (!canAcceptPeer()) {
            return false;
        }
        return peers.add(pos);
    }

    /** 注册表维护：移除一个同 PID 对端。 */
    public void removePeer(GlobalPos pos) {
        peers.remove(pos);
    }

    @Override
    public CompoundTag serializeState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("deviceName", deviceName);
        tag.putString("protocolCost", protocolCost.toString());
        tag.putString("pairId", pairId);
        tag.putBoolean("autoBroadcast", autoBroadcast);
        tag.putInt("maxPeers", maxPeers);
        tag.putDouble("dimensionScale", dimensionScale);
        tag.putBoolean("useCustomScale", useCustomScale);
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        // 构造参数为 final；外部模组读取 NBT 后用参数重新 new 实例。
        // peers 由注册表在 register 时重建。
    }
}
