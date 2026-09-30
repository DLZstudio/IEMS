package com.iems.webpanel;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M10 V-13：时序环形缓冲单测。
 */
class MetricBufferTest {

    private static MetricBuffer.Sample sample(long t, long e) {
        return new MetricBuffer.Sample(t, BigInteger.valueOf(e), BigInteger.valueOf(1000), 1, 2);
    }

    @Test
    void snapshotReturnsChronologicalOrder() {
        MetricBuffer buf = new MetricBuffer(4);
        buf.add(sample(1, 10));
        buf.add(sample(2, 20));
        buf.add(sample(3, 30));
        List<MetricBuffer.Sample> snap = buf.snapshot();
        assertEquals(3, snap.size());
        assertEquals(1, snap.get(0).t());
        assertEquals(3, snap.get(2).t());
        assertEquals(10, snap.get(0).currentEnergy().longValue());
    }

    @Test
    void overwritesOldestWhenFull() {
        MetricBuffer buf = new MetricBuffer(3);
        buf.add(sample(1, 10));
        buf.add(sample(2, 20));
        buf.add(sample(3, 30));
        buf.add(sample(4, 40)); // 覆盖 1
        List<MetricBuffer.Sample> snap = buf.snapshot();
        assertEquals(3, snap.size());
        assertEquals(2, snap.get(0).t());
        assertEquals(4, snap.get(2).t());
    }

    @Test
    void emptyBufferSnapshotIsEmpty() {
        assertEquals(0, new MetricBuffer(8).snapshot().size());
    }

    @Test
    void snapshotIsImmutableCopy() {
        MetricBuffer buf = new MetricBuffer(2);
        buf.add(sample(1, 10));
        List<MetricBuffer.Sample> a = buf.snapshot();
        buf.add(sample(2, 20));
        assertEquals(1, a.size()); // 旧快照不受后续写入影响
        assertEquals(2, buf.snapshot().size());
    }
}
