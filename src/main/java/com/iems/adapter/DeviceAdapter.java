package com.iems.adapter;

import com.iems.core.energy.EnergyUnit;
import com.iems.discovery.DiscoveredDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * 设备适配器接口（DA，可插拔）。
 * <p>
 * 每个适配器绑定一台支持自动连接的 {@link com.iems.core.node.TransferDevice}，
 * 把 DS（DeviceScanner，见 {@link com.iems.discovery.DiscoveryScanner}）发现的
 * 外部能量设备伪装成 {@code IEnergyNode} 注册进电网（逐设备节点），并在
 * 每 tick 执行 SE ↔ 目标单位（FE/AE/GE…）的双向转换读写。
 * </p>
 * <p>
 * 调度器只认节点接口（IEnergyProducer / IEnergyConsumer / StorageDevice），
 * 不区分 SE 原生设备或 DA 适配节点；DA 只是把 SE 指令翻译成外部协议的执行器。
 * 具体能源体系 = 接口的独立实现（本版本实现 {@link FEDA}，AE/EU 为后续扩展点）。
 * </p>
 */
public interface DeviceAdapter {

    /** 适配的能源体系单位（FE/AE/GE…）。 */
    EnergyUnit unit();

    /** 周期扫描：请求 DS 扫描以 {@code center} 为圆心的覆盖范围，返回本适配器关注的设备清单。 */
    List<DiscoveredDevice> scan(ServerLevel level, BlockPos center, int radius);

    /**
     * 同步适配节点：对比扫描报告与已注册节点，新增注册、消失注销。
     * <p>
     * 存量设备<b>直接探测方块能力</b>判定存在性，不依赖扫描报告
     * （DiscoveryScanner 会把已注册位置判为 skippedIems 跳过，重扫必丢）。
     * </p>
     */
    void sync(ServerLevel level);

    /** 每 tick 驱动：对名下节点的外部能力做实际读写（抽取/测余量/送抵）。 */
    void tick(ServerLevel level);

    /** 注销全部名下节点（宿主被移除/失效时）。 */
    void detach();
}
