package com.iems.core.node;

import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 传输节点（TransferDevice）。
 * <p>
 * 电网中的连接节点，负责激光连接与路由。每个实例持有独立的连接参数。
 * </p>
 */
public class TransferDevice implements IEnergyNode {

    private final String deviceName;
    private final BigInteger protocolCost;
    private final int maxConnectionDistance;
    private final boolean autoConnect;
    private final List<String> whitelist;
    private final List<String> blacklist;

    private IConnectionStrategy connectionStrategy;

    public TransferDevice(String deviceName,
                          BigInteger protocolCost,
                          int maxConnectionDistance,
                          boolean autoConnect,
                          List<String> whitelist,
                          List<String> blacklist) {
        this.deviceName = deviceName;
        this.protocolCost = protocolCost;
        this.maxConnectionDistance = maxConnectionDistance;
        this.autoConnect = autoConnect;
        this.whitelist = whitelist == null ? List.of() : new ArrayList<>(whitelist);
        this.blacklist = blacklist == null ? List.of() : new ArrayList<>(blacklist);
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

    public List<String> getWhitelist() {
        return Collections.unmodifiableList(whitelist);
    }

    public List<String> getBlacklist() {
        return Collections.unmodifiableList(blacklist);
    }

    /** 运行时替换连接策略。 */
    public void updateStrategy(IConnectionStrategy strategy) {
        this.connectionStrategy = strategy;
    }

    public IConnectionStrategy getConnectionStrategy() {
        return connectionStrategy;
    }

    /** 查询是否已接入电网（可达核心）。M2 GridTopology 快照实现。 */
    public boolean isConnectedToCore() {
        // TODO(M2): 通过电网快照查询本设备是否位于 mainNetwork
        return false;
    }

    @Override
    public CompoundTag serializeState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("deviceName", deviceName);
        tag.putString("protocolCost", protocolCost.toString());
        tag.putInt("maxConnectionDistance", maxConnectionDistance);
        tag.putBoolean("autoConnect", autoConnect);
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        // 构造参数为 final，restore 仅用于校验/读取，重建实例由外部模组负责。
        // 这里不修改 final 字段；外部模组读取 NBT 后用参数重新 new 实例。
    }
}
