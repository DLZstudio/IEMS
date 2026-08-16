package com.iems.core.grid;

/**
 * 一条电网连接。
 * <p>
 * 端点以 {@link GlobalPos}（维度 + 坐标）表示，与实例对象解耦，
 * 因此区块卸载、实例重建均不影响连接的持久化与有效性。
 * </p>
 */
public record Connection(GlobalPos start, GlobalPos end, ConnectionType type) {
}
