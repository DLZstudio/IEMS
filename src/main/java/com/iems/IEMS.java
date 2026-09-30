package com.iems;

import com.iems.core.energy.EnergyConfig;
import com.iems.core.grid.GlobalPos;
import com.iems.example.IEMSExampleRegistry;
import com.iems.network.IEMSNetworking;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * IEMS (Integrated Energy Management System) 模组入口。
 *
 * <p>IEMS 是一个能源管理框架模组：不直接提供发电/储能核心方块，
 * 而是通过统一的电网抽象层，将 FE/AE 等能量统一转换为 SE（Standard Energy）管理。</p>
 *
 * <p>本入口类仅负责模组加载与事件总线注册。核心逻辑（CoreDevice / TransferDevice /
 * StorageDevice / IEMSAPI 等）按白皮书里程碑逐步落地。</p>
 */
@Mod(IEMS.MODID)
public class IEMS {

    /** 模组唯一标识，必须与 neoforge.mods.toml 中的 modId 一致。 */
    public static final String MODID = "iems";

    /** 模组日志器。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 模组构造器：FML 会自动注入 IEventBus 与 ModContainer。
     * 在此注册网络层（M7）、服务端事件（区块常加载、连接持久化、每 Tick 调度 + 快照推送）
     * 与内置示例设备（方块/物品/方块实体/创造标签页 + 设备工厂）。
     */
    public IEMS(IEventBus modEventBus, ModContainer modContainer) {
        // 能量汇率：SE 是电网内部标准单位，其 FE 汇率全局唯一且影响所有换算，
        // 故在加载期读取配置并注入 EnergyConverter；此后由 /iems reload 手动重载（见 IEMSCommands）
        EnergyConfig energyConfig = EnergyConfig.load(FMLPaths.CONFIGDIR.get());
        EnergyConfig.apply(energyConfig);
        // M7: 注册网络包（GridSyncPayload 服务端 → 客户端）
        IEMSNetworking.register(modEventBus);
        // 服务端事件：区块常加载回调、连接持久化加载/落盘、
        // 每 Tick 能量调度驱动 + 每 40 tick 电网快照推送、生命周期清理
        IEMSEvents.register();
        // 内置示例设备：能源传输中继器（500m 手动）/ 能源广播塔（50m 自动+手动）
        IEMSExampleRegistry.register(modEventBus);
        IEMSExampleRegistry.registerDeviceFactories();
        LOGGER.info("IEMS {} 已加载（1 SE = {} FE）",
                modContainer.getModInfo().getVersion(), energyConfig.fePerSe());
    }

    /**
     * 辅助方法：将 GlobalPos 转换为 ChunkPos。
     */
    public static ChunkPos toChunkPos(GlobalPos pos) {
        return new ChunkPos(pos.pos());
    }
}
