package com.iems.network;

import com.iems.core.grid.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 手动拉线连接请求包（M8.1，客户端 → 服务端）。
 * <p>
 * 客户端拉线交互（Shift+右键两台设备）完成后发送，由服务端
 * {@code IEMSNetworking#handleConnectionRequest} 二次校验后建立连接：
 * 端点设备已注册、同维度、距离不超过起点设备声明值（防伪造包）。
 * </p>
 *
 * @param start 拉线起点（拉线模式开始时记录的设备位置）
 * @param end   拉线终点（玩家第二次 Shift+右键的设备位置）
 */
public record ConnectionRequestPayload(GlobalPos start, GlobalPos end) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ConnectionRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("iems", "connection_request"));

    public static final StreamCodec<FriendlyByteBuf, ConnectionRequestPayload> STREAM_CODEC = StreamCodec.of(
            ConnectionRequestPayload::encode, ConnectionRequestPayload::decode);

    private static void encode(FriendlyByteBuf buf, ConnectionRequestPayload payload) {
        writeGlobalPos(buf, payload.start);
        writeGlobalPos(buf, payload.end);
    }

    private static ConnectionRequestPayload decode(FriendlyByteBuf buf) {
        return new ConnectionRequestPayload(readGlobalPos(buf), readGlobalPos(buf));
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

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
