package com.iems.network;

import com.iems.core.grid.Connection;
import com.iems.core.grid.ConnectionType;
import com.iems.core.grid.GlobalPos;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 电网快照同步包（M7 S1，M8 扩展 HUD 数据，M8.2 扩展孤岛连接，M9 扩展四象限功率）。
 * <p>
 * 服务端 → 客户端全量推送：核心位置、关停状态、已连接设备数、主网连接列表、
 * 孤岛连接列表，以及能量/协议容量数值（M8 HUD 数据源）与四象限功率
 * （M9 HUD 第二行数据源）。
 * 客户端收到后由 {@code ClientGridCache.applySync} 填充渲染缓存与 HUD 缓存。
 * </p>
 * <p>
 * <b>v3（M8.2）</b>：连接拆为两张表——
 * {@code connections}（主网可达连接，两端均在核心可达网络）与
 * {@code islandConnections}（孤岛连接，两端均已注册但未接入核心电网）。
 * 孤岛连接此前不推送，导致自动连接尚未接入核心时激光完全不可见，
 * 玩家误判「连接失败」；v3 起孤岛以独立颜色渲染。
 * 边界连接（任一端未注册/区块未加载）仍不推送——无渲染语义。
 * </p>
 * <p>
 * <b>v4（M9）</b>：+4 个四象限功率字段（总输出/实际输出/总输入/当前输入，
 * SE/tick）。模拟化后「总」口径 = 全注册表（含区块卸载中的休眠设备），
 * 「实际」口径 = 本 tick 主网结算，二者差值即可观察休眠设备占比。
 * 推送间隔由服务端 {@code IEMSEvents} 控制（每 40 tick）。
 * </p>
 * <p>
 * <b>v5（M9）</b>：+{@code adapterBridges} 列表——NFDA 桥接连接
 * （{@code ADAPTER_BRIDGE}，适配器 ↔ 外部 FE 设备）。外部端点不进设备池，
 * 因此既不满足主网过滤（可达性）也不满足孤岛过滤（两端注册），
 * 需独立列表推送到客户端渲染青色桥接带。
 * </p>
 * <p>
 * <b>v6（M9）</b>：+{@code deviceDepths} 深度表与 {@code maxDepth}——
 * 主网设备的 BFS 深度（核心 = 0）。客户端据此让通电/断电波沿链路
 * <b>接力</b>推进：激光段就绪延迟按两端深度插值，第二跳要等第一跳的
 * 时长才亮起（能量从核心逐跳流向全网）。此前的波按单条激光内距离计算，
 * 多跳链上所有激光同时涌动；v6 起与旧版逐设备就绪时间表等效且更精简
 * （只同步深度，就绪时间由客户端按 {@code 深度 × GRADIENT_SPEED} 本地推导）。
 * </p>
 *
 * @param corePos           核心位置（无核心时为 null）
 * @param shutdown          电网是否关停（核心 gridActive == false）
 * @param deviceCount       主网设备数（含核心），供客户端判断电网是否为空
 * @param connections       主网可达连接列表（不可变，含锚点偏移）
 * @param islandConnections 孤岛连接列表（两端已注册但不在主网，M8.2）
 * @param adapterBridges    NFDA 桥接连接列表（外部端点不在设备池，M9）
 * @param deviceDepths      主网设备 BFS 深度表（核心 = 0，M9 波接力）
 * @param maxDepth          主网最大 BFS 深度（M9 波接力）
 * @param currentEnergy     核心当前能量 (SE)，M8 HUD
 * @param totalCapacity     核心能量总容量 (SE)，M8 HUD
 * @param protocolUsed      已用协议容量，M8 HUD
 * @param protocolTotal     协议容量上限，M8 HUD
 * @param totalDemandOut    总输出：全注册表消费者需求 Σ + 外部负载 (SE/tick)，M9 HUD
 * @param actualOut         实际输出：主网实际消耗 + 外部负载 (SE/tick)，M9 HUD
 * @param totalIn           总输入：核心自发电 + 外部输入 + 主网产出 (SE/tick)，M9 HUD
 * @param actualIn          当前输入：主网设备产出 (SE/tick)，M9 HUD
 */
public record GridSyncPayload(GlobalPos corePos, boolean shutdown, int deviceCount,
                              List<Connection> connections,
                              List<Connection> islandConnections,
                              List<Connection> adapterBridges,
                              Map<GlobalPos, Integer> deviceDepths,
                              int maxDepth,
                              BigInteger currentEnergy, BigInteger totalCapacity,
                              BigInteger protocolUsed, BigInteger protocolTotal,
                              BigInteger totalDemandOut, BigInteger actualOut,
                              BigInteger totalIn, BigInteger actualIn) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GridSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("iems", "grid_sync"));

    public static final StreamCodec<FriendlyByteBuf, GridSyncPayload> STREAM_CODEC = StreamCodec.of(
            GridSyncPayload::encode, GridSyncPayload::decode);

    /** v5 兼容构造（深度表缺省空）。 */
    public GridSyncPayload(GlobalPos corePos, boolean shutdown, int deviceCount,
                           List<Connection> connections, List<Connection> islandConnections,
                           List<Connection> adapterBridges,
                           BigInteger currentEnergy, BigInteger totalCapacity,
                           BigInteger protocolUsed, BigInteger protocolTotal,
                           BigInteger totalDemandOut, BigInteger actualOut,
                           BigInteger totalIn, BigInteger actualIn) {
        this(corePos, shutdown, deviceCount, connections, islandConnections, adapterBridges,
                Map.of(), 0,
                currentEnergy, totalCapacity, protocolUsed, protocolTotal,
                totalDemandOut, actualOut, totalIn, actualIn);
    }

    /** v4 兼容构造（桥接连接与深度表缺省空）。 */
    public GridSyncPayload(GlobalPos corePos, boolean shutdown, int deviceCount,
                           List<Connection> connections, List<Connection> islandConnections,
                           BigInteger currentEnergy, BigInteger totalCapacity,
                           BigInteger protocolUsed, BigInteger protocolTotal,
                           BigInteger totalDemandOut, BigInteger actualOut,
                           BigInteger totalIn, BigInteger actualIn) {
        this(corePos, shutdown, deviceCount, connections, islandConnections, List.of(),
                Map.of(), 0,
                currentEnergy, totalCapacity, protocolUsed, protocolTotal,
                totalDemandOut, actualOut, totalIn, actualIn);
    }

    /** v3 兼容构造（四象限、桥接连接与深度表缺省）。 */
    public GridSyncPayload(GlobalPos corePos, boolean shutdown, int deviceCount,
                           List<Connection> connections, List<Connection> islandConnections,
                           BigInteger currentEnergy, BigInteger totalCapacity,
                           BigInteger protocolUsed, BigInteger protocolTotal) {
        this(corePos, shutdown, deviceCount, connections, islandConnections, List.of(),
                Map.of(), 0,
                currentEnergy, totalCapacity, protocolUsed, protocolTotal,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    }

    // ------------------------------------------------------------------
    // 编解码
    // ------------------------------------------------------------------

    private static void encode(FriendlyByteBuf buf, GridSyncPayload payload) {
        // corePos（可空）
        if (payload.corePos == null) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            writeGlobalPos(buf, payload.corePos);
        }
        buf.writeBoolean(payload.shutdown);
        buf.writeInt(payload.deviceCount);
        // 主网连接列表（可空防御：服务端始终传非空列表）
        writeConnectionList(buf, payload.connections);
        // 孤岛连接列表（v3）
        writeConnectionList(buf, payload.islandConnections);
        // NFDA 桥接连接列表（v5）
        writeConnectionList(buf, payload.adapterBridges);
        // 主网设备深度表与最大深度（v6，波接力）
        Map<GlobalPos, Integer> depths = payload.deviceDepths == null ? Map.of() : payload.deviceDepths;
        buf.writeVarInt(depths.size());
        for (Map.Entry<GlobalPos, Integer> entry : depths.entrySet()) {
            writeGlobalPos(buf, entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        buf.writeVarInt(payload.maxDepth);
        // HUD 数值（M8）：任意精度，以十进制字符串编码
        buf.writeUtf(payload.currentEnergy.toString());
        buf.writeUtf(payload.totalCapacity.toString());
        buf.writeUtf(payload.protocolUsed.toString());
        buf.writeUtf(payload.protocolTotal.toString());
        // 四象限功率（M9）
        buf.writeUtf(payload.totalDemandOut.toString());
        buf.writeUtf(payload.actualOut.toString());
        buf.writeUtf(payload.totalIn.toString());
        buf.writeUtf(payload.actualIn.toString());
    }

    private static GridSyncPayload decode(FriendlyByteBuf buf) {
        GlobalPos corePos = null;
        if (buf.readBoolean()) {
            corePos = readGlobalPos(buf);
        }
        boolean shutdown = buf.readBoolean();
        int deviceCount = buf.readInt();
        List<Connection> conns = readConnectionList(buf);
        List<Connection> islandConns = readConnectionList(buf);
        List<Connection> adapterConns = readConnectionList(buf);
        // 主网设备深度表与最大深度（v6，波接力）
        int depthSize = buf.readVarInt();
        // M-03：长度先于内容校验（每项至少 1 字节），避免损坏/恶意包触发超大预分配
        if (depthSize < 0 || depthSize > buf.readableBytes()) {
            throw new DecoderException("invalid device depth size: " + depthSize);
        }
        Map<GlobalPos, Integer> depths = new java.util.HashMap<>(depthSize);
        for (int i = 0; i < depthSize; i++) {
            depths.put(readGlobalPos(buf), buf.readVarInt());
        }
        int maxDepth = buf.readVarInt();
        BigInteger currentEnergy = new BigInteger(buf.readUtf());
        BigInteger totalCapacity = new BigInteger(buf.readUtf());
        BigInteger protocolUsed = new BigInteger(buf.readUtf());
        BigInteger protocolTotal = new BigInteger(buf.readUtf());
        BigInteger totalDemandOut = new BigInteger(buf.readUtf());
        BigInteger actualOut = new BigInteger(buf.readUtf());
        BigInteger totalIn = new BigInteger(buf.readUtf());
        BigInteger actualIn = new BigInteger(buf.readUtf());
        return new GridSyncPayload(corePos, shutdown, deviceCount, conns, islandConns,
                adapterConns, java.util.Collections.unmodifiableMap(depths), maxDepth,
                currentEnergy, totalCapacity, protocolUsed, protocolTotal,
                totalDemandOut, actualOut, totalIn, actualIn);
    }

    private static void writeConnectionList(FriendlyByteBuf buf, List<Connection> conns) {
        List<Connection> list = conns == null ? List.of() : conns;
        buf.writeInt(list.size());
        for (Connection c : list) {
            writeConnection(buf, c);
        }
    }

    private static List<Connection> readConnectionList(FriendlyByteBuf buf) {
        int size = buf.readInt();
        // M-03：长度先于内容校验（每条连接远大于 1 字节，用 readableBytes 作宽松上界），
        // 避免损坏/恶意包触发超大预分配（OOM 风险）
        if (size < 0 || size > buf.readableBytes()) {
            throw new DecoderException("invalid connection list size: " + size);
        }
        List<Connection> conns = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            conns.add(readConnection(buf));
        }
        return List.copyOf(conns);
    }

    private static void writeGlobalPos(FriendlyByteBuf buf, GlobalPos pos) {
        buf.writeResourceKey(pos.dimension());
        BlockPos p = pos.pos();
        buf.writeInt(p.getX());
        buf.writeInt(p.getY());
        buf.writeInt(p.getZ());
    }

    private static GlobalPos readGlobalPos(FriendlyByteBuf buf) {
        ResourceKey<Level> dim = buf.readResourceKey(Registries.DIMENSION);
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        return GlobalPos.of(dim, new BlockPos(x, y, z));
    }

    private static void writeConnection(FriendlyByteBuf buf, Connection c) {
        writeGlobalPos(buf, c.start());
        writeGlobalPos(buf, c.end());
        buf.writeEnum(c.type());
        buf.writeFloat(c.startDx());
        buf.writeFloat(c.startDy());
        buf.writeFloat(c.startDz());
        buf.writeFloat(c.endDx());
        buf.writeFloat(c.endDy());
        buf.writeFloat(c.endDz());
    }

    private static Connection readConnection(FriendlyByteBuf buf) {
        GlobalPos start = readGlobalPos(buf);
        GlobalPos end = readGlobalPos(buf);
        ConnectionType type = buf.readEnum(ConnectionType.class);
        float sdx = buf.readFloat();
        float sdy = buf.readFloat();
        float sdz = buf.readFloat();
        float edx = buf.readFloat();
        float edy = buf.readFloat();
        float edz = buf.readFloat();
        return new Connection(start, end, type, sdx, sdy, sdz, edx, edy, edz);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
