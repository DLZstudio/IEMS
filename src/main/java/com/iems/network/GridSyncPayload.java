package com.iems.network;

import com.iems.core.grid.Connection;
import com.iems.core.grid.ConnectionType;
import com.iems.core.grid.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 电网快照同步包（M7 S1）。
 * <p>
 * 服务端 → 客户端全量推送：核心位置、关停状态、已连接设备数与主网连接列表。
 * 客户端收到后由 {@code ClientGridCache.applySync} 填充渲染缓存。
 * </p>
 * <p>
 * 只推送 {@code mainNetwork} 可达连接（两端均接入核心电网），孤岛/边界连接
 * 无电源语义，渲染端不需要。推送间隔由服务端 {@code IEMSEvents} 控制（每 40 tick）。
 * </p>
 *
 * @param corePos     核心位置（无核心时为 null）
 * @param shutdown    电网是否关停（核心 gridActive == false）
 * @param deviceCount 主网设备数（含核心），供客户端判断电网是否为空
 * @param connections 主网可达连接列表（不可变，含锚点偏移）
 */
public record GridSyncPayload(GlobalPos corePos, boolean shutdown, int deviceCount,
                              List<Connection> connections) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GridSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("iems", "grid_sync"));

    public static final StreamCodec<FriendlyByteBuf, GridSyncPayload> STREAM_CODEC = StreamCodec.of(
            GridSyncPayload::encode, GridSyncPayload::decode);

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
        // 连接列表（可空防御：服务端始终传非空列表）
        List<Connection> conns = payload.connections == null ? List.of() : payload.connections;
        buf.writeInt(conns.size());
        for (Connection c : conns) {
            writeConnection(buf, c);
        }
    }

    private static GridSyncPayload decode(FriendlyByteBuf buf) {
        GlobalPos corePos = null;
        if (buf.readBoolean()) {
            corePos = readGlobalPos(buf);
        }
        boolean shutdown = buf.readBoolean();
        int deviceCount = buf.readInt();
        int size = buf.readInt();
        List<Connection> conns = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            conns.add(readConnection(buf));
        }
        return new GridSyncPayload(corePos, shutdown, deviceCount, List.copyOf(conns));
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
