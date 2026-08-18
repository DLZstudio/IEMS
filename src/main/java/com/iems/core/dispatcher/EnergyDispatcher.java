package com.iems.core.dispatcher;

import com.iems.api.IEMSAPI;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridSnapshot;
import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyConsumer;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.IEnergyProducer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 Tick 能量调度器。
 * <p>
 * 在电网快照（{@link GridSnapshot}）的主网络内，按优先级平衡供给与需求：
 * <ul>
 *   <li>若总有能量 ≥ 总需求：每个消费者按比例（或按优先级顺序）满额满足；溢出存入核心。</li>
 *   <li>若总有能量 < 总需求：按优先级从高到低逐次满足，直到能量耗尽；低优先级消费降级为 0。</li>
 * </ul>
 * 调度本身是纯内存操作，结果写入各设备的瞬时功率状态（由外部存储到 NBT / 配置）。
 * </p>
 *
 * @see com.iems.core.node.IEnergyProducer
 * @see com.iems.core.node.IEnergyConsumer
 */
public final class EnergyDispatcher {

    /** 单帧最多处理设备数（超出则延迟到下一帧）。防止一次调用过长阻塞服务器线程。 */
    private static final int FRAME_SIZE_RATIO = 10;

    /**
     * 计算动态帧大小：取设备总数的 1/FRAME_SIZE_RATIO，至少 8。
     * 白皮书建议：每 Tick 只处理约 1/10 的设备，避免单 Tick 过长。
     */
    private static int calculateFrameSize(int totalDevices) {
        return Math.max(8, totalDevices / FRAME_SIZE_RATIO);
    }

    private EnergyDispatcher() {
    }

    // -------------------------------------------------------------------------
    // 公共 API
    // -------------------------------------------------------------------------

    /**
     * 执行一次完整的 Tick 调度。
     * <p>
     * 读取当前核心能量池、所有主网消费者的需求与供给，按优先级分配后更新核心剩余能量。
     * 此方法为外部模组（如 BlockEntity.onTick）的调用入口。
     * </p>
     *
     * @param frameSize 每帧最多处理的设备数；≤ 0 使用 {@link #DEFAULT_FRAME_SIZE}
     */
    public static void dispatch(int frameSize) {
        CoreDevice core = IEMSAPI.getRegistry().getCore();
        if (core == null) {
            return; // 无核心，跳过
        }
        int limit = frameSize <= 0 ? calculateFrameSize(IEMSAPI.getRegistry().size()) : frameSize;
        dispatch(core, limit);
    }

    /**
     * 无参重载，根据当前设备总数动态计算帧大小。
     */
    public static void dispatch() {
        dispatch(0);
    }

    // -------------------------------------------------------------------------
    // 内部调度逻辑
    // -------------------------------------------------------------------------

    /**
     * 核心调度入口。
     *
     * @param core      核心设备
     * @param frameSize 每帧最大处理设备数
     */
    static void dispatch(CoreDevice core, int frameSize) {
        GridSnapshot snap = IEMSAPI.getSnapshot();
        if (snap == null || snap.isEmpty()) {
            return;
        }

        // 1. 收集主网内的生产者与消费者
        List<DeviceEntry> producers = new ArrayList<>();
        List<DeviceEntry> consumers = new ArrayList<>();
        collectDevices(DeviceRegistry.instance(), snap.getMainNetwork(), producers, consumers);

        if (producers.isEmpty() && consumers.isEmpty()) {
            return; // 空网
        }

        // 2. 计算本 Tick 的总供给（核心自发电 + 外部功率源 + 生产者产出）
        BigInteger selfGen = core.getPowerGenRate();
        BigInteger externalInput = sumExternalInputs();
        BigInteger produced = produceFrom(producers);

        BigInteger totalSupply = selfGen.add(externalInput).add(produced);
        BigInteger currentEnergy = core.getCurrentEnergy();

        // 3. 计算总需求
        BigInteger totalDemand = computeTotalDemand(consumers);

        // 4. 预算 & 优先级分配
        BigInteger budget = currentEnergy.add(totalSupply);
        BigInteger shortfall = totalDemand.subtract(budget);

        if (shortfall.signum() <= 0) {
            // 供给充足：全满足，多余存入核心
            satisfyAll(consumers, frameSize);
            core.setCurrentEnergy(budget.subtract(totalDemand));
        } else {
            // 供给不足：按优先级从高到低逐次满足，直到耗尽
            BigInteger actualConsumed = distributeWithPriority(consumers, budget, frameSize);
            core.setCurrentEnergy(budget.subtract(actualConsumed).max(BigInteger.ZERO));
        }
    }

    // -------------------------------------------------------------------------
    // 私有辅助
    // -------------------------------------------------------------------------

    /** 从注册表收集主网内所有生产者和消费者。 */
    private static void collectDevices(DeviceRegistry registry,
                                       Set<GlobalPos> mainNetwork,
                                       List<DeviceEntry> producers,
                                       List<DeviceEntry> consumers) {
        for (GlobalPos pos : mainNetwork) {
            IEnergyNode node = registry.get(pos);
            if (node == null) continue;
            if (node instanceof IEnergyProducer p) {
                producers.add(new DeviceEntry(pos, p, p.getPriority()));
            }
            if (node instanceof IEnergyConsumer c) {
                consumers.add(new DeviceEntry(pos, c, c.getPriority()));
            }
        }
    }

    /** 累计外部功率源（已注册的 INPUT）。 */
    private static BigInteger sumExternalInputs() {
        return IEMSAPI.getPowerInputs().values().stream()
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** 累计所有生产者的本 Tick 产出。 */
    private static BigInteger produceFrom(List<DeviceEntry> producers) {
        return producers.stream()
                .map(e -> ((IEnergyProducer) e.node).producePerTick())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** 计算总需求。 */
    private static BigInteger computeTotalDemand(List<DeviceEntry> consumers) {
        return consumers.stream()
                .map(e -> ((IEnergyConsumer) e.node).consumePerTick(INFINITE_ENERGY))
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** 供给充足时，逐个满足每个消费者（受 frameSize 限制）。 */
    private static void satisfyAll(List<DeviceEntry> consumers, int frameSize) {
        int count = 0;
        for (DeviceEntry e : consumers) {
            if (count >= frameSize) break;
            IEnergyConsumer c = (IEnergyConsumer) e.node;
            c.consumePerTick(INFINITE_ENERGY); // 告知可无限供给，由消费者自行决定
            count++;
        }
    }

    /**
     * 供给不足时，按优先级从高到低分配可用能量（budget）。
     * <p>
     * 优先级别（{@code getPriority()} 返回值越小优先级越高，同 priority 按 position 字典序兜底）。
     * </p>
     * @return 本 Tick 实际消耗的能量
     */
    private static BigInteger distributeWithPriority(List<DeviceEntry> consumers,
                                                      BigInteger budget,
                                                      int frameSize) {
        // 排序：priority 升序（越小越高），同 priority 按 position
        List<DeviceEntry> sorted = new ArrayList<>(consumers);
        sorted.sort(Comparator
                .comparingInt((DeviceEntry e) -> e.priority)
                .thenComparing(e -> e.pos.toString()));

        BigInteger actualConsumed = BigInteger.ZERO;
        BigInteger remaining = budget;
        int count = 0;
        for (DeviceEntry e : sorted) {
            if (count >= frameSize || remaining.signum() <= 0) break;
            IEnergyConsumer c = (IEnergyConsumer) e.node;
            // 尝试消费，但上限为剩余预算
            BigInteger demand = c.consumePerTick(remaining);
            if (demand.signum() > 0) {
                actualConsumed = actualConsumed.add(demand);
                remaining = remaining.subtract(demand);
            }
            count++;
        }
        return actualConsumed;
    }

    // -------------------------------------------------------------------------
    // 内部数据类
    // -------------------------------------------------------------------------

    /** 设备条目：位置 + 节点 + 优先级。 */
    private static final class DeviceEntry {
        final GlobalPos pos;
        final IEnergyNode node;
        final int priority;

        DeviceEntry(GlobalPos pos, IEnergyNode node, int priority) {
            this.pos = pos;
            this.node = node;
            this.priority = priority;
        }
    }

    /** 用足够大的数表示"无限"供给。 */
    private static final BigInteger INFINITE_ENERGY = BigInteger.TEN.pow(100);
}
