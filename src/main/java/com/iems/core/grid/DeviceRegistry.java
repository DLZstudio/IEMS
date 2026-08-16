package com.iems.core.grid;

import com.iems.core.node.CoreDevice;
import com.iems.core.node.DimensionGate;
import com.iems.core.node.IEnergyNode;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备池（Registry）。
 * <p>
 * 按 {@link GlobalPos} 索引所有已接入电网的设备；维护全局唯一核心；
 * 并维护 DimensionGate 的 pairId 配对关系（同 PID 门互加对端）。
 * </p>
 */
public class DeviceRegistry {

    private final Map<GlobalPos, IEnergyNode> devices = new ConcurrentHashMap<>();

    private volatile CoreDevice core;
    private volatile GlobalPos corePos;

    /** 注册一个设备。若为 DimensionGate，则与同 PID 的其他门建立对端关系。 */
    public void register(GlobalPos pos, IEnergyNode node) {
        devices.put(pos, node);
        if (node instanceof DimensionGate gate) {
            linkDimensionGate(pos, gate);
        }
    }

    /** 注销一个设备。若为 DimensionGate，则与同 PID 的对端解除关系。 */
    public void unregister(GlobalPos pos) {
        IEnergyNode removed = devices.remove(pos);
        if (removed instanceof DimensionGate gate) {
            unlinkDimensionGate(pos, gate);
        }
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
        // TODO(M6): 核心区块常加载 Level.setChunkForced(pos, true)
    }

    /** 注销核心。 */
    public void unregisterCore() {
        this.core = null;
        this.corePos = null;
        // TODO(M6): 解除核心区块常加载
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

    private void linkDimensionGate(GlobalPos pos, DimensionGate gate) {
        for (Map.Entry<GlobalPos, IEnergyNode> entry : devices.entrySet()) {
            if (entry.getKey().equals(pos)) {
                continue;
            }
            if (entry.getValue() instanceof DimensionGate other && other.getPairId().equals(gate.getPairId())) {
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
