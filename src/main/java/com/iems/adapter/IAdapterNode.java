package com.iems.adapter;

/**
 * 适配伪装节点标记。
 * <p>
 * 由 DeviceAdapter 管理、注册进电网的外部设备节点均实现本接口，用途有二：
 * </p>
 * <ul>
 *   <li><b>自动连接排除</b>：{@code IemsAutoConnector} 对伪装节点跳过
 *       RELAY_TO_DEVICE 自动建连（其接入只走 ADAPTER_BRIDGE，避免双连接冲突）；</li>
 *   <li><b>孤儿清扫</b>：宿主中继器注销后，伪装节点成为无连接的孤儿——
 *       {@code FeBridgeTicker} 每 tick 检测并注销（{@link #isOrphaned()}）。</li>
 * </ul>
 */
public interface IAdapterNode {

    /** 所属适配器（宿主中继器 → 逐设备节点 的归属链）。 */
    FEDA owner();

    /** 宿主是否已注销（注册表中该宿主位置不再是原实例 → 名下节点应被清扫）。 */
    default boolean isOrphaned() {
        return owner().isOrphaned();
    }
}
