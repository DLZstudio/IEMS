package com.iems.core.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 全局位置：维度 + 坐标。
 * <p>
 * 这是 IEMS「位置即身份」的核心键，也是跨维度连接的数据基础。
 * 连接数据、设备注册一律以 GlobalPos 为键，不持有对象引用。
 * </p>
 */
public record GlobalPos(ResourceKey<Level> dimension, BlockPos pos) implements Comparable<GlobalPos> {

    public static GlobalPos of(ResourceKey<Level> dimension, BlockPos pos) {
        return new GlobalPos(dimension, pos);
    }

    /**
     * 字典序比较：先按维度完整标识（namespace + path），再按 X/Z 坐标（Y 优先级低于 X/Z）。
     * <p>
     * V-11 修复：比较必须包含 namespace——否则不同模组注册的同 path 自定义维度
     * 排序歧义，会影响 Connection 端点规范化的稳定性。
     * </p>
     */
    @Override
    public int compareTo(GlobalPos other) {
        int nsCmp = this.dimension().location().getNamespace()
                .compareTo(other.dimension().location().getNamespace());
        if (nsCmp != 0) return nsCmp;
        int dimCmp = this.dimension().location().getPath()
                .compareTo(other.dimension().location().getPath());
        if (dimCmp != 0) return dimCmp;
        // 同维度：按 X、Z、Y 依次比较
        int xCmp = Integer.compare(this.pos().getX(), other.pos().getX());
        if (xCmp != 0) return xCmp;
        int zCmp = Integer.compare(this.pos().getZ(), other.pos().getZ());
        if (zCmp != 0) return zCmp;
        return Integer.compare(this.pos().getY(), other.pos().getY());
    }
}
