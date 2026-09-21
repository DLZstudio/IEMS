package com.iems.core.grid;

/**
 * 连接类型。
 * <p>
 * 注意：网络包以枚举序号编码（{@code writeEnum}），新类型只能<b>追加在末尾</b>，
 * 不得插入中间或重排——否则旧客户端/存档的序号语义漂移。
 * 存档侧按 {@code name()} 持久化，新增类型天然向前兼容（旧版跳过未知类型）。
 * </p>
 */
public enum ConnectionType {
    /** 手动拉线连接（玩家 Shift+右键建立，中继器/核心端点，M8.1）。 */
    RELAY_TO_RELAY,
    /** 跨维度桥接（DimensionGate 之间）。 */
    DIMENSION_BRIDGE,
    /** 自动中继器 ↔ 普通设备（自动发现，M8.2）。 */
    RELAY_TO_DEVICE,
    /**
     * DA 桥接连接（M9+）：自动中继器 ↔ 外部 FE 设备。
     * <p>
     * 适配器内建于支持自动连接的传输设备（非独立方块）：中继器在自身连接
     * 半径内经 NFDS 发现外部 FE 设备并自动建立本类型连接。M9+ 逐设备化后
     * 外部端点经 DeviceAdapter 伪装成独立节点注册进设备池（协议容量 = 1），
     * 生命周期由 {@code FEDA} 的 diff 同步维护；渲染端以青色桥接带呈现。
     * </p>
     */
    ADAPTER_BRIDGE
}
