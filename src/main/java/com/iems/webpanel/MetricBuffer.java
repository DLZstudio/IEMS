package com.iems.webpanel;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 时序指标环形缓冲（M10 §5.7）。
 * <p>
 * 固定容量，超过后覆盖最旧样本；快照按时间升序返回（旧 → 新）。
 * 线程安全（服务端 tick 线程写、HTTP 线程读）。
 * </p>
 */
public final class MetricBuffer {

    /** 单条时序样本（能量/容量为 SE 十进制字符串，序列化前保留 BigInteger）。 */
    public record Sample(long t, BigInteger currentEnergy, BigInteger totalCapacity,
                         int protocolUsed, int deviceCount) {
    }

    private final Sample[] ring;
    private final int capacity;
    private int count;
    private int head;

    public MetricBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0: " + capacity);
        }
        this.capacity = capacity;
        this.ring = new Sample[capacity];
    }

    /** 追加样本；满时覆盖最旧（head 前移）。 */
    public synchronized void add(Sample sample) {
        ring[(head + count) % capacity] = sample;
        if (count < capacity) {
            count++;
        } else {
            head = (head + 1) % capacity;
        }
    }

    /** 当前样本数。 */
    public synchronized int size() {
        return count;
    }

    /** 容量上限。 */
    public int capacity() {
        return capacity;
    }

    /** 清空全部样本（跨世界切换时重置，防旧世界指标残留）。 */
    public synchronized void clear() {
        java.util.Arrays.fill(ring, null);
        count = 0;
        head = 0;
    }

    /** 时间升序快照（旧 → 新，副本不可变）。 */
    public synchronized List<Sample> snapshot() {
        List<Sample> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(ring[(head + i) % capacity]);
        }
        return List.copyOf(out);
    }
}
