package com.iems;

import com.iems.core.grid.GlobalPos;
import com.iems.network.IEMSNetworking;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * IEMS (Integrated Energy Management System) 模组入口。
 *
 * <p>IEMS 是一个能源管理框架模组：不直接提供发电/储能核心方块，
 * 而是通过统一的电网抽象层，将 FE/AE/GE 等能量统一转换为 SE（Standard Energy）管理。</p>
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
     * 在此注册网络层（M7）与服务端事件（M6 区块常加载 + M7 快照推送）。
     */
    public IEMS(IEventBus modEventBus, ModContainer modContainer) {
        // M7: 注册网络包（GridSyncPayload 服务端 → 客户端）
        IEMSNetworking.register(modEventBus);
        // M7: 挂载服务端事件（区块常加载回调 + 每 40 tick 电网快照推送）
        IEMSEvents.register();
        LOGGER.info("IEMS {} 已加载", modContainer.getModInfo().getVersion());
    }

    /**
     * 辅助方法：将 GlobalPos 转换为 ChunkPos。
     */
    public static ChunkPos toChunkPos(GlobalPos pos) {
        return new ChunkPos(pos.pos());
    }
}
