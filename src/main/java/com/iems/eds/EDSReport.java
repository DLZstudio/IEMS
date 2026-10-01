package com.iems.eds;

import com.iems.core.grid.GlobalPos;

import java.util.List;

/**
 * 一次扫描的完整发现报告（EDS v1，不可变）。
 * <p>
 * 由 {@link EDSScanner#scan} 产出并推送给全部
 * {@link IEDSListener}（EDA 等消费者自行决定桥接策略）。
 * </p>
 *
 * @param origin               扫描原点
 * @param radius               扫描半径（格，已钳制 [1,128]）
 * @param scanTick             扫描时刻的游戏时间（level.getGameTime()）
 * @param devices              发现的外部设备列表（已按距离无关的稳定序返回）
 * @param chunksScanned        实际枚举的已加载区块数
 * @param blockEntitiesProbed  探测过的方块实体总数（含未命中能力的）
 * @param skippedIemsDevices   跳过的已注册 IEMS 设备数（含核心）
 */
public record EDSReport(GlobalPos origin,
                        int radius,
                        long scanTick,
                        List<EDSDevice> devices,
                        int chunksScanned,
                        int blockEntitiesProbed,
                        int skippedIemsDevices) {

    /** 生产者子集（可输出能量）。 */
    public List<EDSDevice> producers() {
        return devices.stream().filter(EDSDevice::isProducer).toList();
    }

    /** 消费者子集（可接收能量）。 */
    public List<EDSDevice> consumers() {
        return devices.stream().filter(EDSDevice::isConsumer).toList();
    }

    /** 储能型子集。 */
    public List<EDSDevice> storage() {
        return devices.stream().filter(EDSDevice::isStorage).toList();
    }

    /** 按命名空间过滤（身份轴查询）。 */
    public List<EDSDevice> byNamespace(String namespace) {
        return devices.stream().filter(d -> d.namespace().equals(namespace)).toList();
    }
}