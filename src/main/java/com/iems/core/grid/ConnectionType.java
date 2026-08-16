package com.iems.core.grid;

/**
 * 连接类型。
 */
public enum ConnectionType {
    /** 同维度中继连接（TransferDevice 之间）。 */
    RELAY_TO_RELAY,
    /** 跨维度桥接（DimensionGate 之间）。 */
    DIMENSION_BRIDGE
}
