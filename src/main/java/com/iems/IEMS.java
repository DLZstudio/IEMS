package com.iems;

import com.iems.core.grid.DeviceRegistry;
import com.iems.core.grid.GlobalPos;
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
     * 注意：事件监听器的注册应使用 modEventBus.addListener()，而非在构造器内直接捕获事件参数。
     */
    public IEMS(IEventBus modEventBus, ModContainer modContainer) {
        // M6 的区块常加载逻辑需由外部（如 IEMSEvents）在 ServerStartedEvent 中注册回调。
        // 此处仅记录模组加载信息，具体实现见后续 IEMSEvents 类。
        LOGGER.info("IEMS {} 已加载", modContainer.getModInfo().getVersion());
    }

    /**
     * 辅助方法：将 GlobalPos 转换为 ChunkPos。
     */
    public static ChunkPos toChunkPos(GlobalPos pos) {
        return new ChunkPos(pos.pos());
    }
}
