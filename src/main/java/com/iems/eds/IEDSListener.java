package com.iems.eds;

/**
 * EDS 发现监听器——DA 侧的消费契约。
 * <p>
 * 心智模型：EDS 只告诉 DA「这个、那个都是 FE 设备，这个是 AE 设备，
 * 你自己看着办吧」。实现方在 {@link #onDiscovery} 中自行决定
 * 桥接与否、桥接谁、怎么换算——EDS 不做任何桥接决策。
 * </p>
 * <p>
 * 监听器由 {@link EDSScanner} 在每次扫描完成后按注册序通知；
 * 单个监听器抛出异常会被捕获并告警，不影响其余监听器与扫描结果。
 * 监听器运行在服务器主线程（扫描调用线程），请勿做耗时操作。
 * </p>
 */
@FunctionalInterface
public interface IEDSListener {

    /** 收到一份完整的发现报告（快照语义，见 {@link EDSReport}）。 */
    void onDiscovery(EDSReport report);
}