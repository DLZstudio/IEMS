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
 * <p>
 * <b>锚点（Anchor）</b>：每端携带相对所在方块坐标原点的世界偏移
 * （默认方块中心 {@code 0.5,0.5,0.5}）。多方块结构、异形模型的设备
 * 可覆写 {@link com.iems.core.node.IEnergyNode#getAnchorOffset()} 指定实际
 * 激光连接口位置（如 5 格高模型顶部的 {@code 0.5,4.5,0.5}）。锚点在建连时
 * 固化进连接数据，随网络包同步到客户端，渲染端零查询直接使用。
 * </p>
 * <p>
 * <b>身份判定</b>：{@code equals/hashCode} 仅基于两端 GlobalPos + 类型，
 * 锚点不参与身份——因此用默认锚点构建的 Connection 也能匹配已存的连接
 * （如 removeConnection）。
 * </p>
 */
public record Connection(GlobalPos start, GlobalPos end, ConnectionType type,
                         float startDx, float startDy, float startDz,
                         float endDx, float endDy, float endDz) {

    /** 默认锚点偏移：方块中心。 */
    public static final float DEFAULT_ANCHOR = 0.5f;

    /**
     * 工厂方法（默认锚点 = 两端方块中心）：规范化端点顺序，使 start ≤ end（字典序）。
     */
    public static Connection of(GlobalPos a, GlobalPos b, ConnectionType type) {
        return of(a, b, type,
                DEFAULT_ANCHOR, DEFAULT_ANCHOR, DEFAULT_ANCHOR,
                DEFAULT_ANCHOR, DEFAULT_ANCHOR, DEFAULT_ANCHOR);
    }

    /**
     * 工厂方法（显式锚点）：规范化端点顺序时同步交换两端锚点。
     *
     * @param adx/adz  a 端相对方块坐标的锚点偏移
     * @param bdx/bdz  b 端相对方块坐标的锚点偏移
     */
    public static Connection of(GlobalPos a, GlobalPos b, ConnectionType type,
                                float adx, float ady, float adz,
                                float bdx, float bdy, float bdz) {
        if (a.compareTo(b) <= 0) {
            return new Connection(a, b, type, adx, ady, adz, bdx, bdy, bdz);
        }
        return new Connection(b, a, type, bdx, bdy, bdz, adx, ady, adz);
    }

    /** 连接身份 = 两端端点 + 类型（锚点为渲染附属数据，不参与身份判定）。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Connection that)) {
            return false;
        }
        return start.equals(that.start) && end.equals(that.end) && type == that.type;
    }

    @Override
    public int hashCode() {
        int result = start.hashCode();
        result = 31 * result + end.hashCode();
        result = 31 * result + type.hashCode();
        return result;
    }
}
