package com.iems.eds;

import com.iems.core.energy.EnergyUnit;
import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
import com.iems.diagnostics.GridDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * EDS 设备发现扫描器（v1）。
 * <p>
 * 纯「半径球形范围 + 已加载区块」扫描：枚举范围内已加载区块的方块实体，
 * 探测 FE 能力（{@link Capabilities.EnergyStorage#BLOCK}，6 面 + 无方向共 7 个
 * 上下文），按 namespace 分类后产出 {@link EDSReport} 推送监听者。
 * </p>
 * <p>
 * <b>三不原则</b>：不注册（不进 DeviceRegistry）、不建连（不产生 Connection）、
 * 不换算（不做 SE↔FE 数值转换）——三者均为 EDA 的决策域。
 * 未加载区块对本扫描不可见（不报错、不强制加载）；纯方块能力提供者
 * （无方块实体）v1 不可见，属已知限制。
 * </p>
 */
public final class EDSScanner {

    /** 扫描半径钳制上限（格）。 */
    private static final int MAX_RADIUS = 128;

    /** 内置身份轴映射：AE 与 FE 等价（1 AE = 1 FE，量纲无害）。 */
    private static final Map<String, EnergyFlavor> FLAVORS = new ConcurrentHashMap<>();

    /** 发现监听器（代码级注册，无生命周期清理；扫描完成后按注册序通知）。 */
    private static final CopyOnWriteArrayList<IEDSListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** 探测方向：6 面 + 无方向上下文（null）。 */
    private static final Direction[] PROBE_SIDES = {
            Direction.UP, Direction.DOWN, Direction.NORTH,
            Direction.SOUTH, Direction.EAST, Direction.WEST, null};

    static {
        FLAVORS.put("appliedenergistics2",
                new EnergyFlavor("appliedenergistics2", EnergyUnit.AE, "Applied Energistics"));
    }

    private EDSScanner() {
    }

    // ------------------------------------------------------------------
    // 监听器与身份轴注册
    // ------------------------------------------------------------------

    /** 注册发现监听器（EDA / 外部模组接入点，重复注册会收到双份报告）。 */
    public static void addListener(IEDSListener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    /** 注销发现监听器。 */
    public static void removeListener(IEDSListener listener) {
        LISTENERS.remove(listener);
    }

    /** 注册/覆盖模组能量体系身份（namespace → 单位/显示名）；未注册 namespace 默认 FE。 */
    public static void registerFlavor(EnergyFlavor flavor) {
        if (flavor != null && flavor.namespace() != null && !flavor.namespace().isEmpty()) {
            FLAVORS.put(flavor.namespace(), flavor);
        }
    }

    /** 查询 namespace 的身份映射（未注册返回 null，调用方回退 FE 默认）。 */
    public static EnergyFlavor getEnergyFlavor(String namespace) {
        return FLAVORS.get(namespace);
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /**
     * 扫描已加载区块内的外部能量设备（同步一次性调用）。
     * <p>
     * 半径钳制 {@code [1, 128]}；以 center 所在区块为圆心、ceil(radius/16) 为
     * 半径遍历区块坐标，未加载区块跳过；每区块枚举方块实体做廉价预过滤
     * （原点/已注册 IEMS 设备/距离）后再做能力探测。
     * 每次扫描毫秒级（半径 128 ≈ 200 区块），无需 tick 分帧。
     * </p>
     */
    public static EDSReport scan(ServerLevel level, BlockPos center, int radius) {
        int clamped = Math.max(1, Math.min(radius, MAX_RADIUS));
        DeviceRegistry registry = DeviceRegistry.instance();
        GlobalPos origin = GlobalPos.of(level.dimension(), center);

        List<EDSDevice> devices = new ArrayList<>();
        int chunksScanned = 0;
        int probed = 0;
        int skippedIems = 0;

        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int chunkRadius = (clamped + 15) / 16;
        long radiusSqr = (long) clamped * clamped;

        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    continue; // 未加载 = 本次不可见，不报错、不强制加载
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                chunksScanned++;
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    probed++;
                    if (pos.equals(center)) {
                        continue; // 扫描原点自身
                    }
                    if (pos.distSqr(center) > radiusSqr) {
                        continue; // 球形范围之外（区块圈是方形，需精确裁剪）
                    }
                    GlobalPos gp = GlobalPos.of(level.dimension(), pos);
                    if (registry.get(gp) != null || gp.equals(registry.getCorePos())) {
                        skippedIems++; // 已接入 IEMS 电网的设备不是「外部设备」
                        continue;
                    }
                    EDSDevice device = probe(level, gp, entry.getValue());
                    if (device != null) {
                        devices.add(device);
                    }
                }
            }
        }

        devices.sort(Comparator.comparing(EDSDevice::pos));
        EDSReport report = new EDSReport(origin, clamped, level.getGameTime(),
                List.copyOf(devices), chunksScanned, probed, skippedIems);
        GridDiagnostics.event("scan %s r=%d: found=%d probed=%d chunks=%d skippedIems=%d",
                origin, clamped, devices.size(), probed, chunksScanned, skippedIems);
        notifyListeners(report);
        return report;
    }

    /**
     * 单台设备的能力探测与分类。
     * <p>
     * 7 个方向上下文取并集 canExtract/canReceive；stored/capacity 取探测面中
     * 容量最大的一个（快照语义）。全部方向无能力 → 返回 null（非能量设备）。
     * </p>
     */
    private static EDSDevice probe(ServerLevel level, GlobalPos gp, BlockEntity be) {
        boolean canExtract = false;
        boolean canReceive = false;
        Set<Direction> exposed = EnumSet.noneOf(Direction.class);
        BigInteger stored = BigInteger.ZERO;
        BigInteger capacity = BigInteger.ZERO;
        boolean anyCapability = false;

        for (Direction side : PROBE_SIDES) {
            IEnergyStorage storage = level.getCapability(
                    Capabilities.EnergyStorage.BLOCK, gp.pos(), side);
            if (storage == null) {
                continue;
            }
            anyCapability = true;
            if (storage.canExtract()) {
                canExtract = true;
            }
            if (storage.canReceive()) {
                canReceive = true;
            }
            if (side != null) {
                exposed.add(side);
            }
            BigInteger cap = BigInteger.valueOf(storage.getMaxEnergyStored());
            if (cap.compareTo(capacity) > 0) {
                capacity = cap;
                stored = BigInteger.valueOf(storage.getEnergyStored());
            }
        }
        if (!anyCapability) {
            return null;
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(gp.pos()).getBlock()).toString();
        int colon = blockId.indexOf(':');
        String namespace = colon > 0 ? blockId.substring(0, colon) : blockId;
        EnergyFlavor flavor = FLAVORS.get(namespace);
        EnergyUnit unit = flavor != null ? flavor.unit() : EnergyUnit.FE;
        String flavorName = flavor != null ? flavor.displayName() : namespace;

        return new EDSDevice(gp, blockId, namespace, unit, flavorName,
                canExtract, canReceive, stored, capacity, Set.copyOf(exposed));
    }

    /** 按注册序通知监听器；单个监听器异常捕获并告警，不影响其余监听器。 */
    private static void notifyListeners(EDSReport report) {
        for (IEDSListener listener : LISTENERS) {
            try {
                listener.onDiscovery(report);
            } catch (Exception e) {
                GridDiagnostics.event("!! discovery listener %s failed: %s",
                        listener.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}