package com.iems;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.iems.api.IEMSAPI;
import com.iems.core.grid.GridSnapshot;
import com.iems.core.grid.GridTopology;
import com.iems.core.node.CoreDevice;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * IEMS 指令系统（M8）。
 * <p>
 * 白皮书 §9 里程碑 M8：指令系统 + HUD。指令树：
 * </p>
 * <ul>
 *   <li>{@code /iems status}   — 电网总览（核心/能量/协议/拓扑），所有人可用</li>
 *   <li>{@code /iems protocol} — 协议容量明细（used/limit + 超限与否），所有人可用</li>
 *   <li>{@code /iems scan}     — 强制 BFS 重扫 + 结果摘要，所有人可用</li>
 *   <li>{@code /iems shutdown} — 手动关停电网（permission level 2）</li>
 *   <li>{@code /iems restart}  — 手动恢复供电（permission level 2）</li>
 * </ul>
 * <p>
 * 手动关停与协议超限自动关停相互独立：手动关停不受设备增删影响，
 * 也不会被协议容量检查自动恢复（见 DeviceRegistry.protocolShutdown）。
 * </p>
 */
public final class IEMSCommands {

    private IEMSCommands() {
    }

    /** 由 IEMSEvents.register() 挂载（game 总线）。 */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("iems")
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("protocol").executes(ctx -> protocol(ctx.getSource())))
                .then(Commands.literal("scan").executes(ctx -> scan(ctx.getSource())))
                .then(Commands.literal("shutdown").requires(src -> src.hasPermission(2))
                        .executes(ctx -> setActive(ctx.getSource(), false)))
                .then(Commands.literal("restart").requires(src -> src.hasPermission(2))
                        .executes(ctx -> setActive(ctx.getSource(), true)));
        event.getDispatcher().register(root);
    }

    // ------------------------------------------------------------------
    // /iems status
    // ------------------------------------------------------------------

    private static int status(CommandSourceStack source) {
        CoreDevice core = IEMSAPI.getRegistry().getCore();
        if (core == null) {
            source.sendSuccess(() -> Component.literal("§7[IEMS] 电网无核心 — 等待外部模组注册核心。"), false);
            return Command.SINGLE_SUCCESS;
        }
        GridSnapshot snap = IEMSAPI.getSnapshot();
        String reason = core.isGridActive() ? "运行中"
                : (IEMSAPI.isProtocolShutdown() ? "关停（协议容量超限，拆除设备后自动恢复）" : "关停（手动）");
        String lines = "§6[IEMS] 电网状态\n"
                + "§f" + core.getFormattedStatus() + "\n"
                + "§7关停原因: §f" + reason + "\n"
                + "§7主网设备: §f" + snap.getMainNetwork().size()
                + " §7孤岛集群: §f" + snap.getOrphanNetworks().size()
                + " §7连接总数: §f" + GridTopology.instance().getConnections().size()
                + " §7边界连接: §f" + snap.getPendingConnections().size() + "\n"
                + "§7核心位置: §f" + snap.getCorePos();
        source.sendSuccess(() -> Component.literal(lines), false);
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------
    // /iems protocol
    // ------------------------------------------------------------------

    private static int protocol(CommandSourceStack source) {
        CoreDevice core = IEMSAPI.getRegistry().getCore();
        if (core == null) {
            source.sendSuccess(() -> Component.literal("§7[IEMS] 电网无核心 — 等待外部模组注册核心。"), false);
            return Command.SINGLE_SUCCESS;
        }
        String used = IEMSAPI.getProtocolUsed().toString();
        String limit = IEMSAPI.getProtocolTotal().toString();
        boolean over = IEMSAPI.getProtocolUsed().compareTo(IEMSAPI.getProtocolTotal()) > 0;
        String line = "§6[IEMS] §f协议容量: " + used + " / " + limit
                + (over ? " §c（已超限" + (IEMSAPI.isProtocolShutdown() ? "，电网已关停" : "") + "）"
                        : " §a（正常）");
        source.sendSuccess(() -> Component.literal(line), false);
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------
    // /iems scan
    // ------------------------------------------------------------------

    private static int scan(CommandSourceStack source) {
        if (IEMSAPI.getRegistry().getCore() == null) {
            source.sendSuccess(() -> Component.literal("§7[IEMS] 电网无核心 — 等待外部模组注册核心。"), false);
            return Command.SINGLE_SUCCESS;
        }
        IEMSAPI.forceRescan();
        GridSnapshot snap = IEMSAPI.getSnapshot();
        String line = "§6[IEMS] §a重扫完成§f — 主网设备 " + snap.getMainNetwork().size()
                + "，孤岛集群 " + snap.getOrphanNetworks().size()
                + "，连接 " + GridTopology.instance().getConnections().size()
                + (snap.isGridShutdown() ? " §c（电网关停中）" : "");
        source.sendSuccess(() -> Component.literal(line), false);
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------
    // /iems shutdown | restart
    // ------------------------------------------------------------------

    private static int setActive(CommandSourceStack source, boolean active) {
        if (IEMSAPI.getRegistry().getCore() == null) {
            source.sendSuccess(() -> Component.literal("§7[IEMS] 电网无核心 — 等待外部模组注册核心。"), false);
            return Command.SINGLE_SUCCESS;
        }
        IEMSAPI.setGridActive(active);
        String line = active ? "§6[IEMS] §a电网已恢复供电。" : "§6[IEMS] §c电网已手动关停（设备增删不会自动恢复）。";
        source.sendSuccess(() -> Component.literal(line), true);
        return Command.SINGLE_SUCCESS;
    }
}
