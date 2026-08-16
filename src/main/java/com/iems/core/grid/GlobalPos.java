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
public record GlobalPos(ResourceKey<Level> dimension, BlockPos pos) {

    public static GlobalPos of(ResourceKey<Level> dimension, BlockPos pos) {
        return new GlobalPos(dimension, pos);
    }
}
