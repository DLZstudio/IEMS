package com.iems.core.dispatcher;

import com.iems.api.IEMSAPI;
import com.iems.core.energy.EnergyValue;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridSnapshot;
import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyConsumer;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.IEnergyProducer;
import com.iems.core.node.StorageDevice;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 每 Tick 能量调度器。
 * <p>
 * 在电网快照（{@link GridSnapshot}）的主网络内，按优先级平衡供给与需求：
 * <ul>
 *   <li>若总有能量 ≥ 总需求：消费者全额满足；盈余依次充入储能，再溢出存入核心。</li>
 *   <li>若总有能量 < 总需求：储能先放电补缺，再按优先级从高到低逐次满足，直到能量耗尽。</li>
 *   <li>电网关停（协议超限）时不做任何分配，所有设备断电。</li>
 * </ul>
 * 驱动入口为 {@link #dispatchAtTick(int)}（由 IEMSEvents 每 Tick 调用，
 * 同一 tick 幂等）。外部模组不应再自行驱动，重复结算会导致能量凭空翻倍。
 * </p>
 *
 * @see com.iems.core.node.IEnergyProducer
 * @see com.iems.core.node.IEnergyConsumer
 * @see com.iems.core.node.StorageDevice
 */
public final class EnergyDispatcher {

    /** 单帧最多处理设备数（超出则延迟到下一帧）。防止一次调用过长阻塞服务器线程。 */
    private static final int FRAME_SIZE_RATIO = 10;

    /** 上一次结算的服务器 tick（同一 tick 重复调用直接跳过，防止双重结算）。 */
    private static int lastDispatchTick = Integer.MIN_VALUE;

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
     * 服务器 Tick 驱动入口（IEMSEvents 每 Tick 调用）。
     * <p>
     * 同一 tick 幂等：重复调用只会结算一次，防止自发电与外部功率源被重复计入预算。
     * </p>
     *
     * @param serverTick 服务器当前 tick 计数
     */
    public static void dispatchAtTick(int serverTick) {
        if (serverTick == lastDispatchTick) {
            return;
        }
        lastDispatchTick = serverTick;
        dispatch();
    }

    /** 重置 tick 守卫（服务器停止后的生命周期清理，见 IEMSEvents.onServerStopped）。 */
    public static void resetTickGuard() {
        lastDispatchTick = Integer.MIN_VALUE;
    }

    /**
     * 直接结算一次（不经过 tick 守卫，仅供调试/测试使用）。
     * <p>
     * 常规驱动已内置：IEMSEvents 每 Tick 调用 {@link #dispatchAtTick(int)}。
     * 外部模组不要再自行调用，同一 Tick 多次结算会导致能量重复计入。
     * </p>
     *
     * @param frameSize 每帧最多处理的设备数；≤ 0 时按设备总数动态计算
     */
    public static void dispatch(int frameSize) {
        CoreDevice core = IEMSAPI.getRegistry().getCore();
        if (core == null) {
            return; // 无核心，跳过
        }
        int limit = frameSize <= 0 ? calculateFrameSize(IEMSAPI.getRegistry().size()) : frameSize;
        dispatch(core, limit);
    }

    /** 无参重载，根据当前设备总数动态计算帧大小。 */
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
        // 电网关停（协议超限）：不分配任何能量，所有设备断电，核心能量冻结
        if (snap.isGridShutdown()) {
            return;
        }

        // 1. 收集主网内的生产者、消费者与储能设备
        List<DeviceEntry> producers = new ArrayList<>();
        List<DeviceEntry> consumers = new ArrayList<>();
        List<DeviceEntry> storages = new ArrayList<>();
        collectDevices(DeviceRegistry.instance(), snap.getMainNetwork(), producers, consumers, storages);

        if (producers.isEmpty() && consumers.isEmpty() && storages.isEmpty()) {
            return; // 空网
        }

        // 2. 计算本 Tick 的总供给（核心自发电 + 外部功率源 + 生产者产出）
        BigInteger selfGen = core.getPowerGenRate();
        BigInteger externalInput = sumExternalInputs();
        BigInteger produced = produceFrom(producers);

        BigInteger totalSupply = selfGen.add(externalInput).add(produced);
        BigInteger currentEnergy = core.getCurrentEnergy();

        // 3. 计算总需求（纯查询 queryDemand，无副作用）
        BigInteger totalDemand = computeTotalDemand(consumers);

        // 4. 预算 & 优先级分配
        BigInteger budget = currentEnergy.add(totalSupply);
        BigInteger shortfall = totalDemand.subtract(budget);

        if (shortfall.signum() <= 0) {
            // 供给充足：盈余先充入储能（避免核心容量溢出丢弃），再全额满足消费者
            BigInteger charged = chargeStorages(storages, budget.subtract(totalDemand), frameSize);
            BigInteger actualConsumed = satisfyAll(consumers, frameSize);
            core.setCurrentEnergy(budget.subtract(actualConsumed).subtract(charged));
        } else {
            // 供给不足：储能先放电补缺，再按优先级从高到低逐次满足，直到耗尽
            BigInteger discharged = dischargeStorages(storages, shortfall, frameSize);
            BigInteger effectiveBudget = budget.add(discharged);
            BigInteger actualConsumed = distributeWithPriority(consumers, effectiveBudget, frameSize);
            core.setCurrentEnergy(effectiveBudget.subtract(actualConsumed).max(BigInteger.ZERO));
        }
    }

    // -------------------------------------------------------------------------
    // 私有辅助
    // -------------------------------------------------------------------------

    /** 从注册表收集主网内所有生产者、消费者和储能设备。 */
    private static void collectDevices(DeviceRegistry registry,
                                       Set<GlobalPos> mainNetwork,
                                       List<DeviceEntry> producers,
                                       List<DeviceEntry> consumers,
                                       List<DeviceEntry> storages) {
        for (GlobalPos pos : mainNetwork) {
            IEnergyNode node = registry.get(pos);
            if (node == null) continue;
            if (node instanceof IEnergyProducer p) {
                producers.add(new DeviceEntry(pos, p, p.getPriority()));
            }
            if (node instanceof IEnergyConsumer c) {
                consumers.add(new DeviceEntry(pos, c, c.getPriority()));
            }
            if (node instanceof StorageDevice s) {
                storages.add(new DeviceEntry(pos, s, 0));
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

    /** 计算总需求（纯查询，不做实际扣减）。 */
    private static BigInteger computeTotalDemand(List<DeviceEntry> consumers) {
        return consumers.stream()
                .map(e -> ((IEnergyConsumer) e.node).queryDemand())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** 供给充足时，逐个执行消费（受 frameSize 限制）。返回实际消耗总量。 */
    private static BigInteger satisfyAll(List<DeviceEntry> consumers, int frameSize) {
        BigInteger consumed = BigInteger.ZERO;
        int count = 0;
        for (DeviceEntry e : consumers) {
            if (count >= frameSize) break;
            IEnergyConsumer c = (IEnergyConsumer) e.node;
            consumed = consumed.add(c.consumePerTick(INFINITE_ENERGY)); // 可无限供给，由消费者按需求自取
            count++;
        }
        return consumed;
    }

    /** 盈余依次充入储能（受 frameSize 限制）。返回实际充入总量。 */
    private static BigInteger chargeStorages(List<DeviceEntry> storages, BigInteger surplus, int frameSize) {
        BigInteger remaining = surplus.max(BigInteger.ZERO);
        BigInteger charged = BigInteger.ZERO;
        int count = 0;
        for (DeviceEntry e : storages) {
            if (count >= frameSize || remaining.signum() <= 0) break;
            StorageDevice s = (StorageDevice) e.node;
            BigInteger actual = s.onChargeTick(EnergyValue.ofSE(remaining)).toSE();
            if (actual.signum() > 0) {
                charged = charged.add(actual);
                remaining = remaining.subtract(actual);
            }
            count++;
        }
        return charged;
    }

    /** 短缺时储能依次放电补缺（受 frameSize 限制）。返回实际放出总量。 */
    private static BigInteger dischargeStorages(List<DeviceEntry> storages, BigInteger deficit, int frameSize) {
        BigInteger remaining = deficit.max(BigInteger.ZERO);
        BigInteger discharged = BigInteger.ZERO;
        int count = 0;
        for (DeviceEntry e : storages) {
            if (count >= frameSize || remaining.signum() <= 0) break;
            StorageDevice s = (StorageDevice) e.node;
            BigInteger actual = s.onDischargeTick(EnergyValue.ofSE(remaining)).toSE();
            if (actual.signum() > 0) {
                discharged = discharged.add(actual);
                remaining = remaining.subtract(actual);
            }
            count++;
        }
        return discharged;
    }

    /**
     * 供给不足时，按优先级从高到低分配可用能量（budget）。
     * <p>
     * 优先级（{@code getPriority()} 返回值越小优先级越高，同 priority 按 position 字典序兜底）。
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
            // 执行消费，上限为剩余预算
            BigInteger actual = c.consumePerTick(remaining);
            if (actual.signum() > 0) {
                actualConsumed = actualConsumed.add(actual);
                remaining = remaining.subtract(actual);
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
