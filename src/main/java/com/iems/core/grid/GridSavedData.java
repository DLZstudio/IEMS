package com.iems.core.grid;

import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyNode;
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
 * 核心状态（B-2）由 IEMS 主动记忆：核心存在时 {@link #save} 实时拉取
 * 当前核心配置写盘（含运行时能量），核心注销后自然不再写入、旧配置随之清除。
 * </p>
 * <p>
 * <b>M9 设备模拟化</b>：设备列表与核心的工厂 ID 一并持久化——
 * 保存时实时拉取注册表全量设备（有工厂 ID 者才写入）；服务器启动时由
 * {@code IEMSEvents.rehydrateDevices} 经设备工厂重建休眠设备（无区块加载也满血复活）。
 * 无工厂 ID 的设备不写入（优雅降级：重启后由其 BlockEntity 的区块加载路径回归）。
 * DimensionGate 对端关系由注册表按同 PID 自动重建。
 * </p>
 */
public class GridSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String FILE_ID = "iems_grid";

    private static final String KEY_CONNECTIONS = "Connections";
    private static final String KEY_CORE = "Core";
    private static final String KEY_DEVICES = "Devices";
    /** 核心条目内的位置键（存档格式公共契约，rehydrate 路径复用）。 */
    public static final String KEY_CORE_POS = "Pos";

    public static final SavedData.Factory<GridSavedData> FACTORY =
            new SavedData.Factory<>(GridSavedData::new, GridSavedData::load, null);

    /** 当前挂载的存档数据（attach 时设置，服务器停止后清除）。 */
    private static GridSavedData active;

    /** 启动时从 NBT 读出的连接快照（随后拓扑成为唯一事实源）。 */
    private List<Connection> connections = List.of();

    /**
     * 启动时从 NBT 读出的设备快照（M9，含 Pos/Factory/Data 三键），
     * 由 IEMSEvents.rehydrateDevices 消费（随后注册表成为唯一事实源）。
     */
    private List<CompoundTag> savedDevices = List.of();

    /** 启动时从 NBT 读出的核心状态快照（含位置；新格式含 Factory），无则 null。 */
    private CompoundTag savedCoreState;

    /** 挂载到主世界的维度数据存储（不存在则创建空数据）。 */
    public static GridSavedData attach(ServerLevel overworld) {
        GridSavedData data = overworld.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
        active = data;
        return data;
    }

    /** 当前挂载的存档数据（未挂载时为 null，供注册/调度层主动标记核心状态）。 */
    public static GridSavedData active() {
        return active;
    }

    /** 清除挂载引用（服务器完全停止后由生命周期钩子调用，见 IEMSEvents）。 */
    public static void clearActive() {
        active = null;
    }

    private static GridSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        GridSavedData data = new GridSavedData();
        data.connections = readConnections(tag);
        data.savedDevices = readDevices(tag);
        data.savedCoreState = tag.contains(KEY_CORE) ? tag.getCompound(KEY_CORE).copy() : null;
        LOGGER.info("IEMS: 从存档恢复 {} 条电网连接、{} 台休眠设备{}",
                data.connections.size(), data.savedDevices.size(),
                data.savedCoreState != null ? "，含核心状态" : "");
        return data;
    }

    /** 保存时实时拉取拓扑当前连接与注册表设备/核心状态全量写盘（拓扑/注册表为唯一事实源）。 */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        writeConnections(tag, GridTopology.instance().getConnections());
        // B-2：核心存在时实时拉取写盘；核心注销后不再写入，存档中的旧配置自然清除
        CoreDevice core = DeviceRegistry.instance().getCore();
        GlobalPos corePos = DeviceRegistry.instance().getCorePos();
        if (core != null && corePos != null) {
            CompoundTag coreTag = core.serializeState();
            writePos(coreTag, KEY_CORE_POS, corePos);
            // M9：核心工厂 ID（重启后无区块加载也能由工厂重建）
            String coreFactoryId = DeviceRegistry.instance().getCoreFactoryId();
            if (coreFactoryId != null) {
                coreTag.putString("Factory", coreFactoryId);
            }
            tag.put(KEY_CORE, coreTag);
        }
        // M9：设备列表实时拉取注册表全量（有工厂 ID 者才持久化）
        writeDevices(tag);
        return tag;
    }

    public List<Connection> getConnections() {
        return connections;
    }

    /** 启动时读出的设备快照（含 Pos/Factory/Data 三键，供 rehydrateDevices 消费）。 */
    public List<CompoundTag> getSavedDevices() {
        return savedDevices;
    }

    /** 启动时读出的核心状态快照（新格式含 Factory；无核心时为 null）。 */
    public CompoundTag getSavedCoreState() {
        return savedCoreState;
    }

    /** 连接增删时的脏回调目标（由 GridTopology.connectionsDirtyCallback 调用）。 */
    public void markDirty() {
        setDirty();
    }

    /**
     * 尝试从存档恢复同位置核心的持久化配置（B-2）。
     * <p>
     * 仅当存档中核心位置与 {@code pos} 一致时恢复：外部模组用默认参数
     * {@code new} 的核心，在服务器重启后借此保持真实配置（自发电/容量等），
     * 而非回退构造默认值。位置不一致（新核心）则不动，由新实例参数接管。
     * </p>
     *
     * @return 是否成功恢复
     */
    public boolean tryRestoreCore(GlobalPos pos, CoreDevice core) {
        if (savedCoreState == null) {
            return false;
        }
        try {
            GlobalPos savedPos = readPos(savedCoreState, KEY_CORE_POS);
            if (!savedPos.equals(pos)) {
                return false;
            }
            core.restoreState(savedCoreState);
            return true;
        } catch (Exception e) {
            // 单条损坏不拖垮核心注册：回退新实例默认配置
            LOGGER.warn("IEMS: 核心状态恢复失败（{}），使用新实例默认配置", e.getMessage());
            return false;
        }
    }

    /** 标记核心状态脏（核心注册/配置变化时调用，下次自动保存时实时拉取写盘）。 */
    public void markCoreDirty() {
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

    /**
     * M9：设备列表写盘——实时拉取注册表全量设备。
     * <p>
     * 条目格式：{@code {Factory: String, Pos: {Dim,X,Y,Z}, Data: CompoundTag}}。
     * 无工厂 ID 的设备跳过（重启后由其 BE 的区块加载路径回归，优雅降级）。
     * </p>
     */
    private static void writeDevices(CompoundTag tag) {
        ListTag devicesList = new ListTag();
        for (GlobalPos pos : DeviceRegistry.instance().getAllPositions()) {
            String factoryId = DeviceRegistry.instance().getDeviceType(pos);
            if (factoryId == null) {
                continue;
            }
            IEnergyNode node = DeviceRegistry.instance().get(pos);
            if (node == null) {
                continue;
            }
            CompoundTag deviceTag = new CompoundTag();
            writePos(deviceTag, "Pos", pos);
            deviceTag.putString("Factory", factoryId);
            deviceTag.put("Data", node.serializeState());
            devicesList.add(deviceTag);
        }
        tag.put(KEY_DEVICES, devicesList);
    }

    private static List<CompoundTag> readDevices(CompoundTag tag) {
        if (!tag.contains(KEY_DEVICES)) {
            return List.of();
        }
        ListTag list = tag.getList(KEY_DEVICES, Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getCompound(i).copy());
        }
        return List.copyOf(result);
    }

    /** 读取一个 GlobalPos（存档格式公共契约，IEMSEvents.rehydrateDevices 复用）。 */
    public static GlobalPos readPos(CompoundTag tag, String key) {
        CompoundTag t = tag.getCompound(key);
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(t.getString("Dim")));
        return GlobalPos.of(dim, new BlockPos(t.getInt("X"), t.getInt("Y"), t.getInt("Z")));
    }
}
