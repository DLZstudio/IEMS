package com.iems.core.grid;

/**
 * 一条电网连接。
 * <p>
 * 端点以 {@link GlobalPos}（维度 + 坐标）表示，与实例对象解耦，
 * 因此区块卸载、实例重建均不影响连接的持久化与有效性。
 * </p>
 * <p>
 * 注意：{@link #start()} 和 {@link #end()} 保证字典序规范化（start ≤ end），
 * 因此 Connection(A,B) 与 Connection(B,A) 被视为同一条连接。
 * 请使用 {@link #of(GlobalPos, GlobalPos, ConnectionType)} 工厂方法创建。
 * </p>
 */
public record Connection(GlobalPos start, GlobalPos end, ConnectionType type) {
    /**
     * 工厂方法：规范化端点顺序，使 start ≤ end（字典序）。
     */
    public static Connection of(GlobalPos a, GlobalPos b, ConnectionType type) {
        if (a.compareTo(b) <= 0) {
            return new Connection(a, b, type);
        } else {
            return new Connection(b, a, type);
        }
    }
}
