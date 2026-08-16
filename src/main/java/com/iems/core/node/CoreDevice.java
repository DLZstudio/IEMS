package com.iems.core.node;

import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;

/**
 * 核心门面（CoreDevice）。
 * <p>
 * 电网的身份锚点，<b>整个世界（跨维度）有且仅有一个</b>。
 * 注册时自动将所在区块设为常加载；注销时解除。
 * 负责协议容量总量管理、能量池总入口/出口。
 * </p>
 */
public class CoreDevice implements IEnergyNode {

    private final String coreName;
    private BigInteger protocolLimit;
    private final BigInteger energyCapacity;
    private final BigInteger powerGenRate;

    private boolean gridActive = true;
    private BigInteger currentEnergy = BigInteger.ZERO;

    /**
     * @param coreName       核心名称
     * @param protocolLimit  协议容量上限
     * @param energyCapacity 核心能量总容量 (SE)
     * @param powerGenRate   每 Tick 自发电量 (SE/tick)
     */
    public CoreDevice(String coreName, BigInteger protocolLimit, BigInteger energyCapacity, BigInteger powerGenRate) {
        this.coreName = coreName;
        this.protocolLimit = protocolLimit;
        this.energyCapacity = energyCapacity;
        this.powerGenRate = powerGenRate;
    }

    @Override
    public String getDeviceName() {
        return coreName;
    }

    /** 核心自身不占协议容量。 */
    @Override
    public BigInteger getProtocolCost() {
        return BigInteger.ZERO;
    }

    /** 返回格式化电网状态。 */
    public String getFormattedStatus() {
        return "核心[" + coreName + "] 状态=" + (gridActive ? "运行" : "关停")
                + " 能量=" + currentEnergy + "/" + energyCapacity
                + " SE 协议=" + (protocolLimit.toString()) + " 自发电=" + powerGenRate + " SE/tick";
    }

    /** 开启/关闭电网。 */
    public void setGridActive(boolean active) {
        this.gridActive = active;
    }

    public boolean isGridActive() {
        return gridActive;
    }

    /** 强制触发完整 BFS 重扫（M2 GridTopology 实现）。 */
    public void forceRescan() {
        // TODO(M2): 触发 GridTopology 全局 BFS 重扫
    }

    /** 运行时修改协议容量上限。 */
    public void setProtocolLimit(BigInteger limit) {
        this.protocolLimit = limit;
    }

    public BigInteger getProtocolLimit() {
        return protocolLimit;
    }

    /** 获取电网当前总能量 (SE)。 */
    public BigInteger getCurrentEnergy() {
        return currentEnergy;
    }

    public BigInteger getEnergyCapacity() {
        return energyCapacity;
    }

    public BigInteger getPowerGenRate() {
        return powerGenRate;
    }

    /** 供调度器更新当前能量（每 Tick 发电累加、负载扣除）。 */
    public void setCurrentEnergy(BigInteger energy) {
        this.currentEnergy = energy.max(BigInteger.ZERO).min(energyCapacity);
    }

    @Override
    public CompoundTag serializeState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("coreName", coreName);
        tag.putString("protocolLimit", protocolLimit.toString());
        tag.putString("energyCapacity", energyCapacity.toString());
        tag.putString("powerGenRate", powerGenRate.toString());
        tag.putBoolean("gridActive", gridActive);
        tag.putString("currentEnergy", currentEnergy.toString());
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        this.protocolLimit = new BigInteger(tag.getString("protocolLimit"));
        this.gridActive = tag.getBoolean("gridActive");
        this.currentEnergy = new BigInteger(tag.getString("currentEnergy"));
    }
}
