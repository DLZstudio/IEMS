package com.iems.core.grid;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 电网连接持久化（V-04 落地）。
 * <p>
 * 以 {@link SavedData} 存储于主世界（overworld）维度数据目录
 * （实际文件：{@code 世界存档/data/iems_grid.dat}，对应白皮书附录 A 的
 * connections.dat 规划）。服务器启动时由 IEMSEvents 读取并注入
 * {@link GridTopology}；连接增删时通过脏回调标记，
 * 保存时实时拉取拓扑中的当前连接全量写盘。
 * </p>
 * <p>
 * 核心与设备实例不在此持久化：核心/设备由各自 BlockEntity 的 NBT
 * 重载时重新注册，DimensionGate 对端关系由注册表按同 PID 自动重建，
 * 因此本文件只需保存显式连接（含锚点）。
 * </p>
 */
public class GridSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String FILE_ID = "iems_grid";

    private static final String KEY_CONNECTIONS = "Connections";

    public static final SavedData.Factory<GridSavedData> FACTORY =
            new SavedData.Factory<>(GridSavedData::new, GridSavedData::load, null);

    /** 启动时从 NBT 读出的连接快照（随后拓扑成为唯一事实源）。 */
    private List<Connection> connections = List.of();

    /** 挂载到主世界的维度数据存储（不存在则创建空数据）。 */
    public static GridSavedData attach(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    private static GridSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        GridSavedData data = new GridSavedData();
        data.connections = readConnections(tag);
        LOGGER.info("IEMS: 从存档恢复 {} 条电网连接", data.connections.size());
        return data;
    }

    /** 保存时实时拉取拓扑当前连接全量写盘（拓扑为唯一事实源）。 */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        writeConnections(tag, GridTopology.instance().getConnections());
        return tag;
    }

    public List<Connection> getConnections() {
        return connections;
    }

    /** 连接增删时的脏回调目标（由 GridTopology.connectionsDirtyCallback 调用）。 */
    public void markDirty() {
        setDirty();
    }

    // ------------------------------------------------------------------
    // NBT 序列化
    // ------------------------------------------------------------------

    private static void writeConnections(CompoundTag tag, Iterable<Connection> connections) {
        ListTag list = new ListTag();
        for (Connection c : connections) {
            CompoundTag ct = new CompoundTag();
            writePos(ct, "Start", c.start());
            writePos(ct, "End", c.end());
            ct.putString("Type", c.type().name());
            ct.putFloat("Sdx", c.startDx());
            ct.putFloat("Sdy", c.startDy());
            ct.putFloat("Sdz", c.startDz());
            ct.putFloat("Edx", c.endDx());
            ct.putFloat("Edy", c.endDy());
            ct.putFloat("Edz", c.endDz());
            list.add(ct);
        }
        tag.put(KEY_CONNECTIONS, list);
    }

    private static List<Connection> readConnections(CompoundTag tag) {
        ListTag list = tag.getList(KEY_CONNECTIONS, Tag.TAG_COMPOUND);
        List<Connection> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag ct = list.getCompound(i);
            try {
                GlobalPos start = readPos(ct, "Start");
                GlobalPos end = readPos(ct, "End");
                ConnectionType type = ConnectionType.valueOf(ct.getString("Type"));
                result.add(Connection.of(start, end, type,
                        ct.getFloat("Sdx"), ct.getFloat("Sdy"), ct.getFloat("Sdz"),
                        ct.getFloat("Edx"), ct.getFloat("Edy"), ct.getFloat("Edz")));
            } catch (Exception e) {
                // 单条损坏不拖垮整个电网数据（未知类型/非法维度 ID 等向前兼容场景）
                LOGGER.warn("IEMS: 跳过无法解析的连接数据 #{}（{}）", i, e.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private static void writePos(CompoundTag tag, String key, GlobalPos pos) {
        CompoundTag t = new CompoundTag();
        t.putString("Dim", pos.dimension().location().toString());
        t.putInt("X", pos.pos().getX());
        t.putInt("Y", pos.pos().getY());
        t.putInt("Z", pos.pos().getZ());
        tag.put(key, t);
    }

    private static GlobalPos readPos(CompoundTag tag, String key) {
        CompoundTag t = tag.getCompound(key);
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(t.getString("Dim")));
        return GlobalPos.of(dim, new BlockPos(t.getInt("X"), t.getInt("Y"), t.getInt("Z")));
    }
}
