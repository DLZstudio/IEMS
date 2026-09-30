package com.iems.webpanel;

import com.iems.adapter.IAdapterNode;
import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyConsumer;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.IEnergyProducer;
import com.iems.core.node.StorageDevice;

/**
 * 枚举/设备类型 → JSON 字符串映射（M10 §6.3，纯函数可单测）。
 */
public final class WebPanelMapping {

    /** 设备类型 JSON 值。 */
    public static final String TYPE_CORE = "CORE";
    public static final String TYPE_SE_DEVICE = "SE_DEVICE";
    public static final String TYPE_ADAPTER_PRODUCER = "ADAPTER_PRODUCER";
    public static final String TYPE_ADAPTER_CONSUMER = "ADAPTER_CONSUMER";
    public static final String TYPE_ADAPTER_BUFFER = "ADAPTER_BUFFER";

    /** 关停原因 JSON 值。 */
    public static final String CAUSE_NONE = "NONE";
    public static final String CAUSE_MANUAL = "MANUAL";
    public static final String CAUSE_PROTOCOL = "PROTOCOL";

    private WebPanelMapping() {
    }

    /**
     * 设备类型分类（判定顺序固定，见 M10 设计 §6.3）：
     * 核心 > 双向适配（StorageDevice + IAdapterNode）> 适配生产者 > 适配消费者 > 原生 SE 设备。
     */
    public static String deviceType(IEnergyNode node) {
        if (node instanceof CoreDevice) {
            return TYPE_CORE;
        }
        if (node instanceof StorageDevice && node instanceof IAdapterNode) {
            return TYPE_ADAPTER_BUFFER;
        }
        if (node instanceof IAdapterNode && node instanceof IEnergyProducer) {
            return TYPE_ADAPTER_PRODUCER;
        }
        if (node instanceof IAdapterNode && node instanceof IEnergyConsumer) {
            return TYPE_ADAPTER_CONSUMER;
        }
        return TYPE_SE_DEVICE;
    }

    /**
     * 关停原因：电网运行 → NONE；协议超限自动关停 → PROTOCOL；其余手动关停 → MANUAL。
     */
    public static String shutdownCause(boolean gridShutdown, boolean protocolShutdown) {
        if (!gridShutdown) {
            return CAUSE_NONE;
        }
        return protocolShutdown ? CAUSE_PROTOCOL : CAUSE_MANUAL;
    }
}
