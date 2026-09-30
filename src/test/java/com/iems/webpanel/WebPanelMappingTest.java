package com.iems.webpanel;

import com.iems.adapter.IAdapterNode;
import com.iems.adapter.FEDA;
import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyConsumer;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.IEnergyProducer;
import com.iems.core.node.StorageDevice;
import com.iems.core.energy.EnergyValue;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M10 V-13：设备类型 / 关停原因枚举映射单测。
 */
class WebPanelMappingTest {

    // ------------------------------------------------------------------
    // 设备类型
    // ------------------------------------------------------------------

    @Test
    void coreMapsToCore() {
        CoreDevice core = new CoreDevice("core", BigInteger.TEN, BigInteger.TEN, BigInteger.ONE);
        assertEquals("CORE", WebPanelMapping.deviceType(core));
    }

    @Test
    void nativeStorageMapsToSeDevice() {
        // StorageDevice 但非 IAdapterNode → 原生 SE 储能
        assertEquals("SE_DEVICE", WebPanelMapping.deviceType(new FakeStoragePlain()));
    }

    @Test
    void adapterBufferMapsToAdapterBuffer() {
        assertEquals("ADAPTER_BUFFER", WebPanelMapping.deviceType(new FakeBufferAdapter()));
    }

    @Test
    void adapterProducerMapsToAdapterProducer() {
        assertEquals("ADAPTER_PRODUCER", WebPanelMapping.deviceType(new FakeProducer()));
    }

    @Test
    void adapterConsumerMapsToAdapterConsumer() {
        assertEquals("ADAPTER_CONSUMER", WebPanelMapping.deviceType(new FakeConsumer()));
    }

    @Test
    void plainNodeMapsToSeDevice() {
        assertEquals("SE_DEVICE", WebPanelMapping.deviceType(new FakeNode()));
    }

    // ------------------------------------------------------------------
    // 关停原因
    // ------------------------------------------------------------------

    @Test
    void runningGridIsNone() {
        assertEquals("NONE", WebPanelMapping.shutdownCause(false, false));
        assertEquals("NONE", WebPanelMapping.shutdownCause(false, true));
    }

    @Test
    void protocolShutdownWins() {
        assertEquals("PROTOCOL", WebPanelMapping.shutdownCause(true, true));
    }

    @Test
    void manualShutdownWhenNotProtocol() {
        assertEquals("MANUAL", WebPanelMapping.shutdownCause(true, false));
    }

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    /** 普通节点。 */
    private static class FakeNode implements IEnergyNode {
        @Override public String getDeviceName() { return "fake"; }
        @Override public BigInteger getProtocolCost() { return BigInteger.ONE; }
        @Override public CompoundTag serializeState() { return null; }
        @Override public void restoreState(CompoundTag tag) { }
    }

    /** 原生 SE 储能（StorageDevice，非适配节点）。 */
    private static class FakeStoragePlain extends FakeNode implements StorageDevice {
        @Override public EnergyValue onChargeTick(EnergyValue surplus) { return null; }
        @Override public EnergyValue onDischargeTick(EnergyValue deficit) { return null; }
        @Override public BigInteger getStoredEnergy() { return BigInteger.ZERO; }
        @Override public BigInteger getMaxEnergy() { return BigInteger.ZERO; }
        @Override public BigInteger getIoRatePerTick() { return BigInteger.ZERO; }
    }

    /** 双向适配节点（StorageDevice + IAdapterNode，如 FeBufferAdapter）。 */
    private static final class FakeBufferAdapter extends FakeStoragePlain implements IAdapterNode {
        @Override public FEDA owner() { return null; }
    }

    /** 适配生产者。 */
    private static final class FakeProducer extends FakeNode implements IEnergyProducer, IAdapterNode {
        @Override public BigInteger producePerTick() { return BigInteger.ZERO; }
        @Override public FEDA owner() { return null; }
    }

    /** 适配消费者。 */
    private static final class FakeConsumer extends FakeNode implements IEnergyConsumer, IAdapterNode {
        @Override public BigInteger queryDemand() { return BigInteger.ZERO; }
        @Override public BigInteger consumePerTick(BigInteger budget) { return BigInteger.ZERO; }
        @Override public FEDA owner() { return null; }
    }
}
