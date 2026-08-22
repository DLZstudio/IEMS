package com.iems.client;

import com.iems.core.grid.GlobalPos;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.math.BigInteger;

/**
 * 顶部能量显示条（M8 HUD）。
 * <p>
 * 移植自旧版 com.dlzstudio.iems.client.EnergyOverlayRenderer（参考明日方舟终末地
 * 的顶部电力显示），数据源改接 {@link ClientGridCache}（能量/协议数值随
 * GridSyncPayload 每 40 tick 同步，HUD 数值约 2 秒刷新一次）。
 * </p>
 * <p>
 * 三态显示：
 * </p>
 * <ul>
 *   <li>无核心 — 灰色提示等待激活；</li>
 *   <li>电网关停 — 红色，显示协议 used/limit（超限）；</li>
 *   <li>正常运行 — 能量 SE + 协议 used/limit。</li>
 * </ul>
 * <p>
 * 拉线模式（外部模组拉电线时）：{@link #setConnectingMode(boolean, GlobalPos, int)}
 * 由外部模组调用，显示起点到玩家的实时距离，超出 maxLength+10 自动取消并提示。
 * </p>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public class EnergyOverlayRenderer {

    // UI 尺寸
    private static final int BAR_HEIGHT = 40;
    private static final int BAR_WIDTH = 500;
    private static final int CORNER_RADIUS = 10; // 圆角半径（减小，让中间有平直部分）

    // 颜色
    private static final int BG_COLOR_DARK = 0x801a1a1a;    // 深灰色背景 (半透明)
    private static final int TEXT_COLOR = 0xFFFFFFFF;        // 白色文字
    private static final int ACCENT_COLOR = 0xFF4A9EFF;      // 蓝色强调色
    private static final int WARNING_COLOR = 0xFFFFA500;     // 橙色警告
    private static final int DANGER_COLOR = 0xFFFF0000;      // 红色危险

    /** 拉线超距自动取消的宽容距离（与旧版一致：maxLength + 10）。 */
    private static final int CONNECT_CANCEL_BUFFER = 10;

    // 拉电线模式（运行时由外部模组驱动）
    private static boolean isConnectingMode = false;
    private static GlobalPos startPos = null;
    private static int maxLength = 0;

    /** 获取连接模式状态（供其他类访问）。 */
    public static boolean isConnectingMode() {
        return isConnectingMode;
    }

    /**
     * 设置拉电线模式（外部模组在玩家开始/结束拉线时调用）。
     *
     * @param connecting 是否处于拉线模式
     * @param start      拉线起点（电网设备位置）
     * @param max        本连接的最大允许距离
     */
    public static void setConnectingMode(boolean connecting, GlobalPos start, int max) {
        isConnectingMode = connecting;
        startPos = start;
        maxLength = max;
    }

    /** 获取最大连接距离。 */
    public static int getMaxLength() {
        return maxLength;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 拉线模式：检查是否超出最大距离（仅同维度比较）
        if (isConnectingMode && startPos != null && startPos.dimension() == mc.level.dimension()) {
            Vec3 startVec = Vec3.atCenterOf(startPos.pos());
            double distance = mc.player.position().distanceTo(startVec);
            if (distance > maxLength + CONNECT_CANCEL_BUFFER) {
                // 超出最大距离 +10，自动断连
                mc.player.displayClientMessage(Component.literal("§c 距离太远！连接已自动取消"), true);
                setConnectingMode(false, null, maxLength);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // 计算位置：屏幕正上方居中
        int x = (screenWidth - BAR_WIDTH) / 2;
        int y = 10; // 距离顶部 10 像素

        // 绘制背景
        drawRoundedBar(guiGraphics, x, y, BAR_WIDTH, BAR_HEIGHT, CORNER_RADIUS);

        String text;
        if (isConnectingMode) {
            text = buildConnectingText(mc);
        } else {
            text = buildStatusText();
        }

        // 绘制文字
        guiGraphics.drawString(mc.font, text, x + BAR_WIDTH / 2 - mc.font.width(text) / 2, y + 12, TEXT_COLOR, true);
    }

    /** 拉线模式文案：实时距离 + 超距警告。 */
    private static String buildConnectingText(Minecraft mc) {
        if (startPos != null && startPos.dimension() == mc.level.dimension()) {
            Vec3 startVec = Vec3.atCenterOf(startPos.pos());
            double distance = mc.player.position().distanceTo(startVec);
            int currentLength = (int) Math.floor(distance);

            if (currentLength > maxLength + CONNECT_CANCEL_BUFFER) {
                // 超过最大距离 10m，红色，即将自动断连
                return "§4超出最大距离！" + currentLength + "m / " + maxLength + "m  §7(已自动断开)";
            } else if (currentLength > maxLength) {
                // 超过最大距离但未超过 10m，橙色警告
                return "§6超出最大距离！" + currentLength + "m / " + maxLength + "m  §7(Shift+ 右键取消)";
            } else {
                // 正常距离，蓝色
                return "§b连接中§f... §b" + currentLength + "m§f / " + maxLength + "m  §7(Shift+ 右键取消)";
            }
        }
        return "§b连接中§f... 0m / " + maxLength + "m  §7(Shift+ 右键取消)";
    }

    /** 常态文案：能量与协议容量（数据来自 ClientGridCache，服务端每 40 tick 同步）。 */
    private static String buildStatusText() {
        boolean hasCore = ClientGridCache.hasCore();
        boolean isShutdown = ClientGridCache.isGridShutdown();

        if (!hasCore) {
            // 无核心：提示等待激活
            return "§7无核心 — 等待激活...";
        } else if (isShutdown) {
            // 电网关停：协议容量超限
            return "§c电网已关停  §8|  §c" + ClientGridCache.getProtocolUsed() + " / "
                    + ClientGridCache.getProtocolTotal() + " §7(协议容量超限)";
        } else {
            // 正常运行：显示能量和协议容量
            BigInteger currentEnergy = ClientGridCache.getCurrentEnergy();
            BigInteger totalCapacity = ClientGridCache.getTotalCapacity();
            BigInteger protocolUsed = ClientGridCache.getProtocolUsed();
            BigInteger protocolTotal = ClientGridCache.getProtocolTotal();

            String currentStr = currentEnergy.toString();
            String totalStr = totalCapacity.toString();
            String protocolTotalStr = protocolTotal.toString();

            if (!protocolTotalStr.equals("0")) {
                return "§f" + currentStr + " / " + totalStr + " SE  §8|  §f"
                        + protocolUsed + " / " + protocolTotalStr;
            } else {
                return "§f" + currentStr + " / " + totalStr + " SE  §8|  §7-- / --";
            }
        }
    }

    /**
     * 绘制圆角条形背景：左边半圆 + 中间长方形 + 右边半圆。
     */
    private static void drawRoundedBar(GuiGraphics guiGraphics, int x, int y, int width, int height, int radius) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // 中间矩形（全高度）
        guiGraphics.fill(x + radius, y, x + width - radius, y + height, BG_COLOR_DARK);

        // 逐行绘制左右半圆区域
        for (int dy = 0; dy < height; dy++) {
            float offsetF;
            // 计算左侧偏移量（从左边到圆弧边界的水平距离）
            if (dy < radius) {
                // 顶部圆弧
                int relY = radius - dy; // 距圆心距离（垂直）
                offsetF = radius - (float) Math.sqrt(radius * radius - relY * relY);
            } else if (dy >= height - radius) {
                // 底部圆弧
                int relY = dy - (height - radius); // 距圆心距离
                offsetF = radius - (float) Math.sqrt(radius * radius - relY * relY);
            } else {
                offsetF = 0; // 中间平直，左边界在 x
            }
            int leftOffset = Math.round(offsetF);

            // 填充左半圆区域：[x + leftOffset, x + radius]
            if (leftOffset < radius) {
                guiGraphics.fill(x + leftOffset, y + dy, x + radius, y + dy + 1, BG_COLOR_DARK);
            }

            // 右半圆区域：偏移量相同，但方向相反
            int rightOffset = leftOffset; // 对称
            // 右半圆区域：[x + width - radius, x + width - rightOffset]
            if (rightOffset < radius) {
                guiGraphics.fill(x + width - radius, y + dy, x + width - rightOffset, y + dy + 1, BG_COLOR_DARK);
            }
        }

        RenderSystem.disableBlend();
    }
}
