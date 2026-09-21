package com.iems.core.node;

import com.iems.adapter.DeviceAdapter;
import com.iems.adapter.FEDA;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridTopology;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.phys.Vec3;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 传输节点（TransferDevice）。
 * <p>
 * 电网中的连接节点，负责激光连接与路由。每个实例持有独立的连接参数。
 * </p>
 * <p>
 * <b>DS/DA 集成（M9+）</b>：支持自动连接的传输设备持有可插拔的
 * {@link DeviceAdapter} 列表——内建 {@link FEDA} 在自身连接半径内经 NFDS
 * 发现外部 FE 设备，伪装成独立节点（逐设备）接入电网（外部设备自身
 * 成为注册节点，由调度器直接结算，本节点不再聚合桥接账目）。
 * 不支持自动连接的传输设备为纯传输节点（无适配器，也不得手动连接用电/发电器）。
 * 适配器不是独立方块——外部 FE 设备入网的唯一通道是自动中继器。
 * </p>
 */
public class TransferDevice implements IEnergyNode {

    private final String deviceName;
    private final BigInteger protocolCost;
    private final int maxConnectionDistance;
    private final boolean autoConnect;
    private final List<String> whitelist;
    private final List<String> blacklist;

    /**
     * 可插拔设备适配器列表（M9+）：自动连接实例默认装配 {@link FEDA}，
     * 其余为纯传输节点（无适配器，外部设备不进网）。
     */
    private final List<DeviceAdapter> adapters;

    /** 注册位置（位置即身份，由注册表在 register 时注入）。 */
    private volatile GlobalPos position;

    /** 激光连接锚点偏移（相对方块坐标，默认方块中心）。 */
    private final Vec3 anchorOffset;

    /**
     * 旧签名构造：锚点取默认方块中心 {@code (0.5, 0.5, 0.5)}。
     */
    public TransferDevice(String deviceName,
                          BigInteger protocolCost,
                          int maxConnectionDistance,
                          boolean autoConnect,
                          List<String> whitelist,
                          List<String> blacklist) {
        this(deviceName, protocolCost, maxConnectionDistance, autoConnect, whitelist, blacklist,
                new Vec3(0.5, 0.5, 0.5));
    }

    /**
     * 完整构造：使用方可在此指定激光连接口位置（相对方块坐标原点的世界偏移）。
     * <p>
     * 例如 5 格高多方块模型的连接口在顶部时传 {@code new Vec3(0.5, 4.5, 0.5)}；
     * 传 {@code null} 则回退方块中心。
     * </p>
     */
    public TransferDevice(String deviceName,
                          BigInteger protocolCost,
                          int maxConnectionDistance,
                          boolean autoConnect,
                          List<String> whitelist,
                          List<String> blacklist,
                          Vec3 anchorOffset) {
        this(deviceName, protocolCost, maxConnectionDistance, autoConnect, whitelist, blacklist,
                anchorOffset, List.of());
    }

    /**
     * 扩展构造：可在默认 FE 适配器之外追加额外可插拔适配器（如未来 AE2DA）。
     * <p>
     * autoConnect == true 时默认装配 {@link FEDA}；额外适配器始终追加
     * （非自动中继也可携带自定义适配器，扩展用）。
     * </p>
     */
    public TransferDevice(String deviceName,
                          BigInteger protocolCost,
                          int maxConnectionDistance,
                          boolean autoConnect,
                          List<String> whitelist,
                          List<String> blacklist,
                          Vec3 anchorOffset,
                          List<DeviceAdapter> extraAdapters) {
        this.deviceName = deviceName;
        this.protocolCost = protocolCost;
        this.maxConnectionDistance = maxConnectionDistance;
        this.autoConnect = autoConnect;
        this.whitelist = whitelist == null ? List.of() : new ArrayList<>(whitelist);
        this.blacklist = blacklist == null ? List.of() : new ArrayList<>(blacklist);
        this.anchorOffset = anchorOffset == null ? new Vec3(0.5, 0.5, 0.5) : anchorOffset;
        // M9+：自动连接实例默认装配 FE 适配器（DS/DA 逐设备接入）
        List<DeviceAdapter> combined = new ArrayList<>();
        if (autoConnect) {
            combined.add(new FEDA(this));
        }
        if (extraAdapters != null) {
            combined.addAll(extraAdapters);
        }
        this.adapters = List.copyOf(combined);
    }

    /** 激光连接锚点偏移（相对方块坐标）。 */
    @Override
    public Vec3 getAnchorOffset() {
        return anchorOffset;
    }

    @Override
    public String getDeviceName() {
        return deviceName;
    }

    @Override
    public BigInteger getProtocolCost() {
        return protocolCost;
    }

    public int getMaxConnectionDistance() {
        return maxConnectionDistance;
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    /**
     * 可插拔设备适配器列表（M9+）。
     * <p>FeBridgeTicker 以此识别桥接宿主并驱动各适配器；外部模组可读取
     * 适配器名下节点与状态。</p>
     */
    public List<DeviceAdapter> getAdapters() {
        return adapters;
    }

    public List<String> getWhitelist() {
        return Collections.unmodifiableList(whitelist);
    }

    public List<String> getBlacklist() {
        return Collections.unmodifiableList(blacklist);
    }

    /** 注册表维护：注入本设备位置（位置即身份）。 */
    public void setPosition(GlobalPos pos) {
        this.position = pos;
    }

    /** 本设备注册位置（未注册时 null）。 */
    public GlobalPos getPosition() {
        return position;
    }

    /** 查询是否已接入电网（位于电网快照的 mainNetwork，可达核心）。 */
    public boolean isConnectedToCore() {
        GlobalPos pos = position;
        return pos != null && GridTopology.instance().isReachable(pos);
    }

    @Override
    public CompoundTag serializeState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("deviceName", deviceName);
        tag.putString("protocolCost", protocolCost.toString());
        tag.putInt("maxConnectionDistance", maxConnectionDistance);
        tag.putBoolean("autoConnect", autoConnect);
        // 锚点持久化：外部模组读取 NBT 后用参数重建实例
        tag.putFloat("anchorX", (float) anchorOffset.x);
        tag.putFloat("anchorY", (float) anchorOffset.y);
        tag.putFloat("anchorZ", (float) anchorOffset.z);
        // 黑白名单持久化（P1 修复：此前缺失，数据驱动的外部模组区块重载后过滤规则丢失）
        tag.put("whitelist", toStringList(whitelist));
        tag.put("blacklist", toStringList(blacklist));
        // 适配器账目不持久化：伪装节点由 FEDA 首次重扫重建（无工厂 ID 不落盘）
        return tag;
    }

    private static ListTag toStringList(List<String> values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        return list;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        // 构造参数为 final（含适配器列表，随 autoConnect 装配），实例重建由
        // 外部模组工厂负责；伪装节点不持久化，此处无需恢复桥接账目。
    }
}
