package com.iems.core.grid;

/**
 * 区块加载回调接口。
 * <p>
 * 由 IEMS 模组层实现，用于将核心区块设为常加载。
 * 应在服务器启动时注册到 {@link DeviceRegistry}。
 * </p>
 */
public interface ChunkLoadingCallback {

    /**
     * 加载区块（设为常加载）。
     *
     * @param pos 目标位置（包含维度）
     */
    void loadChunk(GlobalPos pos);

    /**
     * 卸载区块（解除常加载）。
     *
     * @param pos 目标位置（包含维度）
     */
    void unloadChunk(GlobalPos pos);
}
