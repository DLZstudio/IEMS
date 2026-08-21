package com.iems.client;

import com.iems.core.grid.Connection;
import com.iems.core.grid.ConnectionType;
import com.iems.core.grid.GlobalPos;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * 激光连接渲染器（M7 适配副本）。
 * <p>
 * 旧版（com.dlzstudio.iems.client.ConnectionLaserRenderer）存在三类问题，本副本
 * 针对当前版本（com.iems / 1.21.1 / NeoForge）逐项重构：
 * </p>
 * <ul>
 *   <li><b>性能</b>：圆柱体（16 边 × 12 顶点/段）→ <b>带状网格（TRIANGLE_STRIP）</b>，
 *       顶点数从 ~230,400（30 连接 × 40 段 × 192）降至 ~2,460（30 × 82），降幅 <b>~99%</b>，
 *       彻底消除单帧 MeshData 超限导致的缓冲区溢出风险。</li>
 *   <li><b>并发</b>：数据源改用 {@link ClientGridCache}（volatile + 不可变快照），
 *       根除网络线程写 / 渲染线程读的 {@link java.util.ConcurrentModificationException}。</li>
 *   <li><b>稳定性</b>：渲染阶段改为 {@code AFTER_PARTICLES} 解决与透明方块的 Z-fighting；
 *       渲染结束复位着色器与混合状态，避免污染后续 UI。</li>
 *   <li><b>计算冗余</b>：方向/正交基每条连接只算一次；{@code System.currentTimeMillis()}
 *       每帧只调用一次（旧版每段 3 次）。</li>
 *   <li><b>适配</b>：核心端判定从旧版 {@code ConnectionType.CORE_TO_*} 改为比对
 *       {@link ClientGridCache#getCorePos()}（当前版本核心位置即身份）；
 *       连接端点使用 {@link GlobalPos}（维度 + 坐标）。</li>
 * </ul>
 *
 * @see ClientGridCache
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ConnectionLaserRenderer {

    // ------------------------------------------------------------------
    // 常量
    // ------------------------------------------------------------------

    /** 渲染阶段：粒子之后（解决与透明方块同层级的 Z-fighting）。 */
    private static final RenderLevelStageEvent.Stage RENDER_STAGE =
            RenderLevelStageEvent.Stage.AFTER_PARTICLES;

    /** 带状网格半宽（世界格单位）。旧版圆柱半径 0.03，这里取宽度 0.06 视觉相当。 */
    private static final float BEAM_HALF_WIDTH = 0.03f;

    /** 分段粒度（格/段），保留旧版扩散动画粒度。 */
    private static final float SEGMENT_LENGTH = 0.5f;

    /** 跨维度门在当前维度的光晕段长度（格）。 */
    private static final float GATE_STUB_LENGTH = 1.5f;

    /** 渲染距离缓冲（格），避免边界闪烁。 */
    private static final double RENDER_DISTANCE_PADDING = 32.0;

    /** 连线颜色：红=0 黄=1 的绿色通道进度。R/B 恒为 1/0。 */
    private static final float COLOR_R = 1.0f;
    private static final float COLOR_B = 0.0f;
    private static final float COLOR_A = 1.0f;

    private ConnectionLaserRenderer() {
    }

    // ------------------------------------------------------------------
    // 渲染入口
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RENDER_STAGE) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // 快照：ClientGridCache 内部为不可变集合，遍历安全（方案 A 并发加固）
        List<Connection> connections = ClientGridCache.getConnections(mc.level.dimension());
        if (connections.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();

        // 渲染距离（区块 × 16 + 缓冲）
        double renderDistanceBlocks = mc.options.renderDistance().get() * 16.0
                + RENDER_DISTANCE_PADDING;

        // 电网状态快照（一次读取，循环内复用）
        final boolean gridHasCore = ClientGridCache.hasCore();
        final boolean gridShutdown = ClientGridCache.isGridShutdown();
        final int deviceCount = ClientGridCache.getConnectedDeviceCount();
        final boolean poweringOff = ClientGridCache.isPoweringOff();
        final long coreStartTime = ClientGridCache.getClientCorePowerStartTime();
        final long powerOffStartTime = ClientGridCache.getClientPowerOffStartTime();
        final GlobalPos corePos = ClientGridCache.getCorePos();
        // 时间戳：整帧仅调用一次（方案 A：消除 3×分段数 的系统调用）
        final long now = System.currentTimeMillis();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(
                VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        ResourceKey<Level> currentDim = mc.level.dimension();

        for (Connection connection : connections) {
            // 端点世界坐标（锚点：默认方块中心，异形设备由 IEnergyNode.getAnchorOffset 指定）
            Vec3 startWorld = anchorWorld(connection, true);
            Vec3 endWorld = anchorWorld(connection, false);

            // 距离剔除：以连接中点到相机距离判定
            Vec3 midPoint = startWorld.add(endWorld).scale(0.5);
            if (midPoint.distanceTo(cameraPos) > renderDistanceBlocks) {
                continue;
            }

            // 视锥裁剪（方案 B）：连接包围盒不可见则跳过
            AABB aabb = new AABB(startWorld, endWorld).inflate(BEAM_HALF_WIDTH);
            if (event.getFrustum() != null && !event.getFrustum().isVisible(aabb)) {
                continue;
            }

            // 核心端判定：端点 == corePos（适配：不再依赖 CORE_TO_* 连接类型）
            boolean coreAtStart = corePos != null && connection.start().equals(corePos);
            boolean coreAtEnd = corePos != null && connection.end().equals(corePos);

            // 跨维度桥：两端不在同一维度 → 当前维度端绘制竖直门光晕
            // （另一端坐标在别的维度，无法计算方向向量，故使用固定竖直光晕）
            boolean sameDimension = connection.start().dimension().equals(connection.end().dimension());
            if (connection.type() == ConnectionType.DIMENSION_BRIDGE && !sameDimension) {
                if (connection.start().dimension().equals(currentDim)) {
                    drawGateStub(buffer, startWorld, cameraPos, connection.start(),
                            gridHasCore, gridShutdown, deviceCount);
                } else if (connection.end().dimension().equals(currentDim)) {
                    drawGateStub(buffer, endWorld, cameraPos, connection.end(),
                            gridHasCore, gridShutdown, deviceCount);
                }
                continue;
            }

            // 同维度连接：完整带状激光
            drawBeamStrip(buffer, connection,
                    startWorld, endWorld, cameraPos,
                    coreAtStart, coreAtEnd,
                    now, coreStartTime, powerOffStartTime,
                    gridHasCore, gridShutdown, deviceCount, poweringOff);
        }

        MeshData meshData = buffer.build();
        if (meshData != null) {
            BufferUploader.drawWithShader(meshData);
        }

        // 状态复位（方案 B）：防止着色器/混合污染后续渲染
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> null);
    }

    // ------------------------------------------------------------------
    // 带状网格激光
    // ------------------------------------------------------------------

    /**
     * 绘制同维度连接的带状激光（TRIANGLE_STRIP）。
     * <p>优化点：方向向量与正交基仅计算一次；顶点数为 (segments+1)*2，
     * 取代旧版圆柱体的 segments*192。颜色沿长度按通电/断电进度渐变。</p>
     */
    private static void drawBeamStrip(BufferBuilder buffer,
                                      Connection connection,
                                      Vec3 startWorld, Vec3 endWorld, Vec3 cameraPos,
                                      boolean coreAtStart, boolean coreAtEnd,
                                      long now, long coreStartTime, long powerOffStartTime,
                                      boolean gridHasCore, boolean gridShutdown, int deviceCount,
                                      boolean poweringOff) {
        // 相机相对坐标
        float sx = (float) (startWorld.x - cameraPos.x);
        float sy = (float) (startWorld.y - cameraPos.y);
        float sz = (float) (startWorld.z - cameraPos.z);
        float ex = (float) (endWorld.x - cameraPos.x);
        float ey = (float) (endWorld.y - cameraPos.y);
        float ez = (float) (endWorld.z - cameraPos.z);

        float dx = ex - sx, dy = ey - sy, dz = ez - sz;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.001f) {
            return;
        }

        // 归一化方向 + 正交基（每条连接仅计算一次）
        float nx = dx / length, ny = dy / length, nz = dz / length;
        Vec3 right = orthonormalRight(nx, ny, nz);
        float rx = (float) right.x * BEAM_HALF_WIDTH;
        float ry = (float) right.y * BEAM_HALF_WIDTH;
        float rz = (float) right.z * BEAM_HALF_WIDTH;

        boolean isOffline = !gridHasCore || gridShutdown || deviceCount == 0;

        // 端点颜色进度（核心端恒为 1.0，其余从 ClientGridCache 按 GlobalPos 查询）
        float startProgress = coreAtStart ? 1.0f
                : ClientGridCache.getDevicePowerProgress(connection.start());
        float endProgress = coreAtEnd ? 1.0f
                : ClientGridCache.getDevicePowerProgress(connection.end());

        int segments = Math.max((int) (length / SEGMENT_LENGTH), 1);
        // TRIANGLE_STRIP：每段左右两个顶点
        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            float cx = sx + t * dx;
            float cy = sy + t * dy;
            float cz = sz + t * dz;

            // 该点沿连接的距离（用于扩散动画与颜色插值）
            float distFromCore = coreAtStart ? t * length : (coreAtEnd ? (1 - t) * length : t * length);

            // 颜色进度：端点进度插值 + 断电动画覆盖
            float progress = interpolate(startProgress, endProgress, t);
            if (isOffline) {
                progress = offlineProgress(distFromCore, now, powerOffStartTime, poweringOff);
            } else if (coreStartTime < 0) {
                progress = 0.0f;
            } else {
                progress *= onProgress(distFromCore, now, coreStartTime);
            }
            float g = clamp01(progress);

            // 左右两顶点（同一颜色，带内插值）
            buffer.addVertex(cx + rx, cy + ry, cz + rz).setColor(COLOR_R, g, COLOR_B, COLOR_A);
            buffer.addVertex(cx - rx, cy - ry, cz - rz).setColor(COLOR_R, g, COLOR_B, COLOR_A);
        }
    }

    /**
     * 绘制跨维度门的当前维度端竖直光晕（表示"此门已与另一维度连通"）。
     * 方向固定竖直向上，不依赖另一端坐标。
     * <p>
     * 颜色：正常青色 {@code (0.3,1,1)}；断电时按门自身的通电进度
     * （{@link ClientGridCache#getDevicePowerProgress}）平滑渐变到红色，
     * 与普通连线共享同一套服务端同步的细粒度进度语义。
     * </p>
     */
    private static void drawGateStub(BufferBuilder buffer,
                                     Vec3 stubStart, Vec3 cameraPos, GlobalPos gatePos,
                                     boolean gridHasCore, boolean gridShutdown, int deviceCount) {
        // 门光晕使用固定高亮颜色（青色），不随普通连线动画
        float sx = (float) (stubStart.x - cameraPos.x);
        float sy = (float) (stubStart.y - cameraPos.y);
        float sz = (float) (stubStart.z - cameraPos.z);
        // 竖直向上光晕
        float ex = sx;
        float ey = sy + GATE_STUB_LENGTH;
        float ez = sz;

        // 右向量：取"竖直方向(0,1,0) × 视线方向(相机→门)"的水平分量，
        // 使光晕始终面向玩家。避免固定取 X 轴时，相机正对 X 方向观察
        // 导致带状网格压缩成不可见的线（已知瑕疵修复）。
        float halfWidth = BEAM_HALF_WIDTH * 1.5f;
        Vec3 viewDir = stubStart.subtract(cameraPos); // 相机 → 门
        double horizLen = Math.sqrt(viewDir.x * viewDir.x + viewDir.z * viewDir.z);
        float rx, rz;
        if (horizLen > 1.0e-4) {
            // up(0,1,0) × view = (view.z, 0, -view.x)，取水平分量归一化
            rx = (float) (viewDir.z / horizLen) * halfWidth;
            rz = (float) (-viewDir.x / horizLen) * halfWidth;
        } else {
            // 视线几乎竖直（相机在门正上/正下方）：退化到 X 轴，避免除零
            rx = halfWidth;
            rz = 0.0f;
        }
        float ry = 0.0f;

        // 门光晕颜色：正常青色（R=0.3 G=1 B=1）；断电时按门设备通电进度
        // fade = 1 - progress 从 0（满电·青色）渐变到 1（断电·红色）。
        // 门也是设备，与普通连线共享 getDevicePowerProgress 细粒度进度。
        boolean isOffline = !gridHasCore || gridShutdown || deviceCount == 0;
        float progress = isOffline
                ? clamp01(1.0f - ClientGridCache.getDevicePowerProgress(gatePos))
                : 0.0f;
        float r = 0.3f + 0.7f * progress;
        float g = 1.0f - progress;
        float b = 1.0f - progress;

        int segments = Math.max((int) (GATE_STUB_LENGTH / SEGMENT_LENGTH), 1);
        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            float cx = sx + t * (ex - sx);
            float cy = sy + t * (ey - sy);
            float cz = sz + t * (ez - sz);
            buffer.addVertex(cx + rx, cy + ry, cz + rz).setColor(r, g, b, COLOR_A);
            buffer.addVertex(cx - rx, cy - ry, cz - rz).setColor(r, g, b, COLOR_A);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 与方向向量正交的单位向量（方案 B：预计算，避免每段重复 sqrt）。 */
    private static Vec3 orthonormalRight(float nx, float ny, float nz) {
        Vec3 up = Math.abs(ny) < 0.99f ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 right = new Vec3(ny * up.z - nz * up.y,
                nz * up.x - nx * up.z,
                nx * up.y - ny * up.x);
        return right.normalize();
    }

    /** 通电扩散进度：0~1，从核心开始随时间推进。 */
    private static float onProgress(float distFromCore, long now, long coreStartTime) {
        long elapsed = now - coreStartTime;
        long readyTime = (long) (distFromCore * ClientGridCache.GRADIENT_SPEED_MS_PER_BLOCK);
        return clamp01((float) elapsed / Math.max(readyTime, 1L));
    }

    /** 断电扩散进度：1→0，从核心开始随时间变红。 */
    private static float offlineProgress(float distFromCore, long now, long powerOffStartTime,
                                         boolean poweringOff) {
        if (!poweringOff) {
            return 0.0f;
        }
        long elapsed = now - powerOffStartTime;
        long readyTime = (long) (distFromCore * ClientGridCache.GRADIENT_SPEED_MS_PER_BLOCK);
        return 1.0f - clamp01((float) elapsed / Math.max(readyTime, 1L));
    }

    private static float interpolate(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /**
     * Connection 端点 → 锚点世界坐标。
     * <p>
     * 锚点 = 端点方块坐标 + 建连时固化的偏移（默认方块中心 0.5,0.5,0.5；
     * 多方块/异形设备由 {@code IEnergyNode.getAnchorOffset()} 在服务端指定）。
     * 渲染时维度已由调用方过滤。
     * </p>
     */
    private static Vec3 anchorWorld(Connection c, boolean start) {
        GlobalPos p = start ? c.start() : c.end();
        float dx = start ? c.startDx() : c.endDx();
        float dy = start ? c.startDy() : c.endDy();
        float dz = start ? c.startDz() : c.endDz();
        BlockPos b = p.pos();
        return new Vec3(b.getX() + dx, b.getY() + dy, b.getZ() + dz);
    }
}
