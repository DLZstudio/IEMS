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
import net.minecraft.client.renderer.culling.Frustum;
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
 *   <li><b>孤岛可见性</b>：未接入核心电网的连接（{@link ClientGridCache#getIslandConnections}）
 *       以恒定红色绘制，等价旧 IEMS「无核心时整网变红」的表现——否则拉线成功却
 *       看不到任何激光，玩家会误判连接失败。</li>
 *   <li><b>桥接带</b>：适配器 ↔ 外部 FE 设备的 {@code ADAPTER_BRIDGE} 连接
 *       （{@link ClientGridCache#getAdapterBridges}）以青色绘制，与能量电网连线区分；
 *       是否着色由渲染器配置 {@code [renderer].colorizeAutoConnect} 决定
 *       （{@code false} 时与普通连线同色）。</li>
 * </ul>
 *
 * @see ClientGridCache
 * @see RendererConfig
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

    /**
     * 屏幕空间最小半宽斜率（半宽 / 顶点到相机距离）。
     *
     * <p>固定 0.06 格宽的长距离激光在数十格外会低于 1 像素，退化为亚像素后
     * 随视角旋转出现走样甚至整条消失（表现为「特定角度下很细甚至消失」）。
     * 顶点按自身到相机的距离取 {@code max(BEAM_HALF_WIDTH, dist * 本值)}，
     * 使屏幕上宽度恒不低于约 1 像素（FOV 70°/1080p 下 0.0015 约合 1.2 像素半宽），
     * 近处仍为 {@link #BEAM_HALF_WIDTH}，不改变原有观感。</p>
     */
    private static final float MIN_HALF_WIDTH_PER_BLOCK = 0.0015f;

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

    /**
     * 青色桥接带红色通道（与跨维度门光晕同色系：{@code (0.3, 1, 1)}）。
     * <p>用于标记「适配器 ↔ 外部 FE 设备」的非电网连线，与普通连线的红/黄渐变区分。</p>
     */
    private static final float BRIDGE_CYAN_R = 0.3f;

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
        List<Connection> islands = ClientGridCache.getIslandConnections(mc.level.dimension());
        List<Connection> bridges = ClientGridCache.getAdapterBridges(mc.level.dimension());
        if (connections.isEmpty() && islands.isEmpty() && bridges.isEmpty()) {
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
        // 渲染器配置（进程内缓存，命中时仅为一次 volatile 读）
        final boolean colorizeBridges = RendererConfig.get().colorizeAutoConnect();
        // 时间戳：整帧仅调用一次（方案 A：消除 3×分段数 的系统调用）
        final long now = System.currentTimeMillis();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        // 显式关闭背面剔除：AFTER_PARTICLES 阶段继承区块层（RenderType.translucent/
        // tripwire）的默认 CULL 状态（CompositeStateBuilder 默认 cullState=CULL，
        // 粒子渲染不触碰 cull），本渲染器执行时背面剔除处于开启状态。而条带的
        // billboard 宽度方向在相机接近光束延长线时退化到固定正交基（wlen<=1e-4
        // 的 fallback），绕向不再保证正面朝相机；过渡带（wlen 略大于阈值）内
        // 叉积是两个大数的灾难性相减，宽度方向被浮点噪声主导，绕向随舍入翻转。
        // 单面条带 + 开启的 cull 会让部分激光在特定观察角度整条被剔除——
        // 表现为「这批消失、转几度又换一批」的可见性轮换突变。激光是发光条带，
        // 双面渲染无视觉差异，关闭 cull 根治。
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(
                VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        ResourceKey<Level> currentDim = mc.level.dimension();

        // 跨条带分隔链：同一 BufferBuilder 内连续输出多条 TRIANGLE_STRIP 时，
        // 相邻两带之间必须插入退化三角形分隔（drawBeamStrip/drawGateStub 内部处理），
        // 否则两带衔接处会多出真实斜跨三角形——「从设备 A 斜到设备 B 的幽灵细线」。
        float[] prevTail = null;

        for (Connection connection : connections) {
            // 端点世界坐标（锚点：默认方块中心，异形设备由 IEnergyNode.getAnchorOffset 指定）
            Vec3 startWorld = anchorWorld(connection, true);
            Vec3 endWorld = anchorWorld(connection, false);
            boolean sameDimension = connection.start().dimension().equals(connection.end().dimension());

            // 跨维度的非桥连接（异常数据 / 旧存档迁移）：另一端坐标在异维度，
            // 直接画出来是一条从当前维度端斜向异维度坐标的幽灵线，跳过。
            // 孤岛/桥接循环已有同款防御，主循环此前缺失。
            if (!sameDimension && connection.type() != ConnectionType.DIMENSION_BRIDGE) {
                continue;
            }

            // 跨维度桥：两端不在同一维度 → 当前维度端绘制竖直门光晕
            // （另一端坐标在别的维度，无法计算方向向量，故使用固定竖直光晕）。
            // 可见性只判当前维度端：异维度端坐标对本维度没有空间意义，
            // 混入距离/视锥判定会因坐标恰好超距/超视锥而错误剔除门光晕。
            if (connection.type() == ConnectionType.DIMENSION_BRIDGE && !sameDimension) {
                // filterByDimension 保证至少一端在当前维度：start 不匹配则必为 end 端
                Vec3 localEnd = connection.start().dimension().equals(currentDim) ? startWorld : endWorld;
                if (localEnd.distanceTo(cameraPos) > renderDistanceBlocks) {
                    continue;
                }
                double gateInflate = Math.max(BEAM_HALF_WIDTH * 1.5f,
                        localEnd.distanceTo(cameraPos) * MIN_HALF_WIDTH_PER_BLOCK);
                AABB gateBox = new AABB(localEnd, localEnd.add(0, GATE_STUB_LENGTH, 0))
                        .inflate(gateInflate);
                if (event.getFrustum() != null && !event.getFrustum().isVisible(gateBox)) {
                    continue;
                }
                prevTail = appendOrNull(prevTail, drawGateStub(buffer, localEnd, cameraPos,
                        gridHasCore, gridShutdown, deviceCount,
                        now, powerOffStartTime, poweringOff, prevTail));
                continue;
            }

            // 同维度连接：距离 + 视锥剔除
            if (!isVisible(event.getFrustum(), cameraPos, renderDistanceBlocks, startWorld, endWorld)) {
                continue;
            }

            // 核心端判定：端点 == corePos（适配：不再依赖 CORE_TO_* 连接类型）
            boolean coreAtStart = corePos != null && connection.start().equals(corePos);
            boolean coreAtEnd = corePos != null && connection.end().equals(corePos);

            // 同维度连接：完整带状激光
            prevTail = appendOrNull(prevTail, drawBeamStrip(buffer, connection,
                    startWorld, endWorld, cameraPos, corePos,
                    coreAtStart, coreAtEnd,
                    now, coreStartTime, powerOffStartTime,
                    gridHasCore, gridShutdown, deviceCount, poweringOff,
                    false, false, false, prevTail));
        }

        // 孤岛连接：两端已注册但未接入核心电网 → 恒定红色（没电）。
        // 旧 IEMS 在无核心时整网渲染为红色，玩家据此判断「线拉上了、只是没电」；
        // 若不渲染，拉线成功后画面毫无反馈，会被误判为连接失败。
        for (Connection connection : islands) {
            // 跨维度孤岛无法确定方向向量（另一端坐标在别的维度），跳过；
            // 门光晕语义只对已接入核心电网的维度桥有意义。
            if (!connection.start().dimension().equals(connection.end().dimension())) {
                continue;
            }
            Vec3 startWorld = anchorWorld(connection, true);
            Vec3 endWorld = anchorWorld(connection, false);
            if (!isVisible(event.getFrustum(), cameraPos, renderDistanceBlocks, startWorld, endWorld)) {
                continue;
            }
            prevTail = appendOrNull(prevTail, drawBeamStrip(buffer, connection,
                    startWorld, endWorld, cameraPos, corePos,
                    false, false,
                    now, coreStartTime, powerOffStartTime,
                    gridHasCore, gridShutdown, deviceCount, poweringOff,
                    true, false, false, prevTail));
        }

        // 适配器桥接带（ADAPTER_BRIDGE）：适配器中继器 ↔ 外部 FE 设备。
        // 端点均在设备池内，但不属于能量电网连线；由 [renderer].colorizeAutoConnect
        // 决定是否以青色与普通连线区分（false 时同色渲染，仍可见）。
        for (Connection connection : bridges) {
            // 跨维度桥无方向向量可算，跳过（与孤岛同策略）。
            if (!connection.start().dimension().equals(connection.end().dimension())) {
                continue;
            }
            Vec3 startWorld = anchorWorld(connection, true);
            Vec3 endWorld = anchorWorld(connection, false);
            if (!isVisible(event.getFrustum(), cameraPos, renderDistanceBlocks, startWorld, endWorld)) {
                continue;
            }
            prevTail = appendOrNull(prevTail, drawBeamStrip(buffer, connection,
                    startWorld, endWorld, cameraPos, corePos,
                    false, false,
                    now, coreStartTime, powerOffStartTime,
                    gridHasCore, gridShutdown, deviceCount, poweringOff,
                    false, true, colorizeBridges, prevTail));
        }

        MeshData meshData = buffer.build();
        if (meshData != null) {
            BufferUploader.drawWithShader(meshData);
        }

        // 状态复位（方案 B）：防止着色器/混合/剔除污染后续渲染
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.setShader(() -> null);
    }

    // ------------------------------------------------------------------
    // 带状网格激光
    // ------------------------------------------------------------------

    /**
     * 绘制同维度连接的带状激光（TRIANGLE_STRIP）。
     * <p>优化点：方向向量与正交基仅计算一次；顶点数远低于旧版圆柱体的
     * segments*192。渐变复刻旧版机制：按 {@code SEGMENT_LENGTH} 分段、每段
     * 整段共用段中点的扩散时间轴纯色——通电波前从扩散起点以段粒度逐格点亮，
     * 断电同轴逐格变红（「能量沿链路逐格流动」的进度条效果）；此前逐顶点
     * 连续取色会把波前抹平成连续渐变带，阶梯推进感丢失。</p>
     *
     * @param corePos 电网核心位置快照（仅用于非核心连接的扩散起点选向）
     * @param island 孤岛连接标记：{@code true} 时忽略进度/动画，整条恒定红色（没电）
     * @param bridge 适配器桥接带标记：{@code true} 时按 {@code cyanBridge} 取色
     * @param cyanBridge 桥接带着色开关：{@code true} 青色（与普通连线区分），
     *                   {@code false} 与普通连线同色（通电黄 / 断电红）
     * @param prevTail 上一条带最后写入的顶点 {x,y,z,r,g,b,a}（null = 本带是首条）；
     *                 非 null 时在本带顶点前插入退化三角形分隔（见 onRenderLevel 注释）
     * @return 本带最后写入的顶点 {x,y,z,r,g,b,a}；长度过短未产出顶点时返回 null
     */
    private static float[] drawBeamStrip(BufferBuilder buffer,
                                         Connection connection,
                                         Vec3 startWorld, Vec3 endWorld, Vec3 cameraPos,
                                         GlobalPos corePos,
                                         boolean coreAtStart, boolean coreAtEnd,
                                         long now, long coreStartTime, long powerOffStartTime,
                                         boolean gridHasCore, boolean gridShutdown, int deviceCount,
                                         boolean poweringOff, boolean island,
                                         boolean bridge, boolean cyanBridge,
                                         float[] prevTail) {
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
            return null;
        }

        // 归一化方向 + 宽度方向（billboard）
        float nx = dx / length, ny = dy / length, nz = dz / length;

        // 宽度方向 = 光束方向 × 「相机→光束」向量：结果同时垂直于光束与视线，
        // 使带状截面始终正对相机。对直线光束而言该向量沿全长恒定
        // （点沿光束平移只改变光束方向分量，不贡献叉积），因此不会发生扭转。
        // 若改用固定世界正交基（旧实现），某些环绕视角下带面与视线共面，
        // 激光会越变越细直至消失、转过角度后再变粗。
        float vx = sx, vy = sy, vz = sz; // 相机相对坐标即「相机→起点」向量
        float wx = ny * vz - nz * vy;
        float wy = nz * vx - nx * vz;
        float wz = nx * vy - ny * vx;
        float wlen = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        // 保留未归一化的宽度方向，配合每顶点的 halfWidth / wlen 缩放实现
        // 屏幕空间最小宽度（见 MIN_HALF_WIDTH_PER_BLOCK）。
        if (wlen <= 1.0e-4f) {
            // 相机几乎位于光束延长线上（正对着看）：叉积趋零，退化到固定正交基，
            // 并把 wlen 置 1 使下方缩放仍然成立
            Vec3 fallback = orthonormalRight(nx, ny, nz);
            wx = (float) fallback.x;
            wy = (float) fallback.y;
            wz = (float) fallback.z;
            wlen = 1.0f;
        }

        boolean isOffline = !gridHasCore || gridShutdown || deviceCount == 0;

        // 扩散动画的起点选向（distFromCore 用）：
        // - 核心在 start/end 端：方向已定（下方三元式短路）；
        // - 两端均非核心（设备↔设备链式连接）：此前固定从 start 端扩散，
        //   而 start/end 是 Connection.of 字典序规范化的结果，与拓扑无关，
        //   扩散方向可能是「离核心越来越远」的反方向。以欧氏距离更靠近
        //   核心的端点作为扩散起点（图上距离不可得，欧氏近似，仅影响动画方向）。
        boolean diffuseFromStart;
        if (coreAtStart || corePos == null
                || !corePos.dimension().equals(connection.start().dimension())
                || !corePos.dimension().equals(connection.end().dimension())) {
            diffuseFromStart = true;
        } else {
            diffuseFromStart = corePos.pos().distSqr(connection.start().pos())
                    <= corePos.pos().distSqr(connection.end().pos());
        }

        // 端点颜色进度（核心端恒为 1.0，其余从 ClientGridCache 按 GlobalPos 查询）
        float startProgress = coreAtStart ? 1.0f
                : ClientGridCache.getDevicePowerProgress(connection.start());
        float endProgress = coreAtEnd ? 1.0f
                : ClientGridCache.getDevicePowerProgress(connection.end());

        // ---- TRIANGLE_STRIP 多条带分隔 ----
        // 同一 BufferBuilder 连续输出多条带时，直接背靠背衔接会让上一带末两点与
        // 本带首两点之间多出一个真实斜跨三角形——表现为「从设备 A 斜到设备 B 的
        // 幽灵细线」，且视角相关（斜面侧看压缩成细线，特定角度才可见）。
        // 在本带顶点前插入 [上一带末顶点, 本带首顶点 ×2]：序列变为 ...Pn, Pn, Q0, Q0, Q0, Q1...
        // 跨带三角形全部退化（重复顶点零面积），本带三角形从 (Q0,Q1,Q2) 正常开始。
        // 段级划分（复刻旧版 SEGMENT_LENGTH 分段纯色渐变，提前定义供段 0 色使用）
        int segments = Math.max((int) (length / SEGMENT_LENGTH), 1);

        float firstDistToCamera = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        float firstK = Math.max(BEAM_HALF_WIDTH,
                firstDistToCamera * MIN_HALF_WIDTH_PER_BLOCK) / wlen;
        float firstX = sx + wx * firstK;
        float firstY = sy + wy * firstK;
        float firstZ = sz + wz * firstK;
        // 首顶点位于 t=0 位置，颜色按段级阶梯取「段 0」的段中点色
        //（tMid0 = 0.5/segments），与主循环首位置顶点同源
        float tMid0 = 0.5f / segments;
        float firstDistFromCore = coreAtStart ? tMid0 * length
                : (coreAtEnd ? (1 - tMid0) * length
                : (diffuseFromStart ? tMid0 * length : (1 - tMid0) * length));
        float[] firstColor = new float[3];
        beamColorInto(firstColor, tMid0, isOffline,
                startProgress, endProgress, firstDistFromCore,
                island, bridge, cyanBridge,
                now, coreStartTime, powerOffStartTime, poweringOff);
        if (prevTail != null) {
            buffer.addVertex(prevTail[0], prevTail[1], prevTail[2])
                    .setColor(prevTail[3], prevTail[4], prevTail[5], prevTail[6]);
            buffer.addVertex(firstX, firstY, firstZ)
                    .setColor(firstColor[0], firstColor[1], firstColor[2], COLOR_A);
            buffer.addVertex(firstX, firstY, firstZ)
                    .setColor(firstColor[0], firstColor[1], firstColor[2], COLOR_A);
        }

        // ---- 主循环：段级阶梯渐变（复刻旧版 0.5 格分段纯色机制） ----
        // 旧版把连接按 SEGMENT_LENGTH 分段，每段独立圆柱体、整段共用段中点的
        // 扩散时间轴进度（segProgress）——通电时波前从扩散起点以段粒度逐格
        // 点亮，断电时同轴逐格变红。带状实现的复刻方式：段区间 [t_i, t_{i+1}]
        // 的两个位置顶点对共用段中点色；相邻段在交界位置输出双顶点（前段色 +
        // 后段色），TRIANGLE_STRIP 滑动窗口在重复位置处全部构成零面积退化
        // 三角形（与跨带 prevTail 分隔同理）——段区间内保持纯色，段间颜色在
        // 交界位置直接跳变，波前呈阶梯推进。
        float[] color = firstColor;          // 当前段色（从段 0 起滚动复用）
        float[] nextColor = new float[3];    // 下一段色（交界双顶点预取）
        float[] tail = null;
        // 首位置顶点对（t=0，段 0 色）
        emitBeamVertexPair(buffer, sx, sy, sz, dx, dy, dz, 0.0f,
                color, wx, wy, wz, wlen, null);
        for (int i = 0; i < segments; i++) {
            float t2 = (float) (i + 1) / segments;
            if (i == segments - 1) {
                // 末段：段末位置顶点对（段 i 色），并记录本带末顶点供下一条带分隔
                tail = new float[7];
                emitBeamVertexPair(buffer, sx, sy, sz, dx, dy, dz, t2,
                        color, wx, wy, wz, wlen, tail);
            } else {
                // 预取段 i+1 色：段中点 tMid 处的扩散时间轴进度
                //（选向规则与首顶点处同一三元式：核心端 / 欧氏近核端）
                float tMid = (i + 1.5f) / segments;
                float distFromCoreMid = coreAtStart ? tMid * length
                        : (coreAtEnd ? (1 - tMid) * length
                        : (diffuseFromStart ? tMid * length : (1 - tMid) * length));
                beamColorInto(nextColor, tMid, isOffline,
                        startProgress, endProgress, distFromCoreMid,
                        island, bridge, cyanBridge,
                        now, coreStartTime, powerOffStartTime, poweringOff);
                // 段 i 末位置顶点对（段 i 色）
                emitBeamVertexPair(buffer, sx, sy, sz, dx, dy, dz, t2,
                        color, wx, wy, wz, wlen, null);
                // 交界双顶点：同位置 t2、取下一段色——段间在此纯色跳变，
                // 且带内滑动窗口在重复位置处自然退化，不会产生斜跨幽灵三角形
                emitBeamVertexPair(buffer, sx, sy, sz, dx, dy, dz, t2,
                        nextColor, wx, wy, wz, wlen, null);
                float[] tmp = color;
                color = nextColor;
                nextColor = tmp;
            }
        }
        return tail;
    }

    /**
     * 输出光束在参数 t 处的左右两顶点（同一颜色）。
     * <p>段循环的交界处会对同一位置连续输出两次（前段色 + 后段色），
     * 带内滑动窗口在重复位置处全部构成零面积退化三角形，段区间内保持纯色
     * （段级阶梯渐变，见 drawBeamStrip 主循环注释）。宽度方向沿全长恒定，
     * 但屏幕空间最小宽度按每个位置顶点自身的相机距离取值。</p>
     *
     * @param tailOut 非 null 时写入本位置右顶点 {x,y,z,r,g,b,a}（末段记录带尾用）
     */
    private static void emitBeamVertexPair(BufferBuilder buffer,
                                           float sx, float sy, float sz,
                                           float dx, float dy, float dz, float t,
                                           float[] color,
                                           float wx, float wy, float wz, float wlen,
                                           float[] tailOut) {
        float cx = sx + t * dx;
        float cy = sy + t * dy;
        float cz = sz + t * dz;

        // 屏幕空间最小宽度：按顶点自身到相机的距离放大半宽，
        // 远端由亚像素加宽到约 1 像素，近处仍取 BEAM_HALF_WIDTH。
        // cx/cy/cz 已是相机相对坐标，其模长即顶点到相机距离。
        float distToCamera = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
        float halfWidth = Math.max(BEAM_HALF_WIDTH,
                distToCamera * MIN_HALF_WIDTH_PER_BLOCK);
        float k = halfWidth / wlen;
        float rx = wx * k, ry = wy * k, rz = wz * k;

        float r = color[0], g = color[1], b = color[2];
        buffer.addVertex(cx + rx, cy + ry, cz + rz).setColor(r, g, b, COLOR_A);
        buffer.addVertex(cx - rx, cy - ry, cz - rz).setColor(r, g, b, COLOR_A);
        if (tailOut != null) {
            // 本位置右顶点，作为下一条带的分隔锚点
            tailOut[0] = cx - rx;
            tailOut[1] = cy - ry;
            tailOut[2] = cz - rz;
            tailOut[3] = r;
            tailOut[4] = g;
            tailOut[5] = b;
            tailOut[6] = COLOR_A;
        }
    }

    /**
     * 计算光束参数 t 处的颜色（桥接带 / 孤岛 / 普通连线三分支）。
     * <p>首顶点分隔插入与主循环逐段取色共用本方法，保证两处颜色一致。</p>
     */
    private static void beamColorInto(float[] out, float t, boolean isOffline,
                                      float startProgress, float endProgress, float distFromCore,
                                      boolean island, boolean bridge, boolean cyanBridge,
                                      long now, long coreStartTime, long powerOffStartTime,
                                      boolean poweringOff) {
        if (bridge) {
            if (cyanBridge) {
                // 青色桥接带（[renderer].colorizeAutoConnect = true）：
                // 固定青色，与跨维度门光晕同色系，标识「适配器 ↔ 外部设备」非电网连线。
                out[0] = BRIDGE_CYAN_R;
                out[1] = 1.0f;
                out[2] = 1.0f;
            } else {
                // 关闭颜色差异 → 与普通连线同色（通电黄 / 断电红）。
                // 桥接端点不参与电网通电进度，故只按电网整体状态取值。
                out[0] = COLOR_R;
                out[1] = isOffline ? 0.0f : 1.0f;
                out[2] = COLOR_B;
            }
        } else if (island) {
            // 孤岛连接未接入核心电网，恒定红色（G=0）= 没电。不参与通电/断电动画。
            out[0] = COLOR_R;
            out[1] = 0.0f;
            out[2] = COLOR_B;
        } else {
            // 颜色进度：端点进度插值 + 断电动画覆盖
            float progress = interpolate(startProgress, endProgress, t);
            if (isOffline) {
                progress = offlineProgress(distFromCore, now, powerOffStartTime, poweringOff);
            } else if (coreStartTime < 0) {
                progress = 0.0f;
            } else {
                progress *= onProgress(distFromCore, now, coreStartTime);
            }
            out[0] = COLOR_R;
            out[1] = clamp01(progress);
            out[2] = COLOR_B;
        }
    }

    /**
     * 绘制跨维度门的当前维度端竖直光晕（表示"此门已与另一维度连通"）。
     * 方向固定竖直向上，不依赖另一端坐标。
     * <p>
     * 颜色：正常青色 {@code (0.3,1,1)}；断电时复用连线的断电扩散时间轴
     * （{@link #offlineProgress}），从门底部向上渐变到红色
     * （1.5 格 × 150ms/格 ≈ 225ms）；断电完成后（或无核心）恒红。
     * 此前直接取 {@code getDevicePowerProgress}（快照映射为 0/1 阶跃），
     * 断电是瞬间变红，与注释宣称的「平滑渐变」不符。
     * </p>
     * <p>
     * 半宽与 {@link #drawBeamStrip} 同规则：按顶点到相机的距离取
     * {@code max(固定半宽, dist * MIN_HALF_WIDTH_PER_BLOCK)}，远处不再退化为
     * 亚像素闪烁/消失。
     * </p>
     *
     * @param prevTail 上一条带末顶点（null = 首条），非 null 时插入退化三角形分隔
     * @return 本带末顶点 {x,y,z,r,g,b,a}
     */
    private static float[] drawGateStub(BufferBuilder buffer,
                                        Vec3 stubStart, Vec3 cameraPos,
                                        boolean gridHasCore, boolean gridShutdown, int deviceCount,
                                        long now, long powerOffStartTime, boolean poweringOff,
                                        float[] prevTail) {
        // 门光晕常态为固定高亮青色；断电时经 offlineProgress 时间轴渐变到红
        float sx = (float) (stubStart.x - cameraPos.x);
        float sy = (float) (stubStart.y - cameraPos.y);
        float sz = (float) (stubStart.z - cameraPos.z);
        // 竖直向上光晕
        float ex = sx;
        float ey = sy + GATE_STUB_LENGTH;
        float ez = sz;

        // 门光晕整体是否处于没电状态（与普通连线同一判定）
        boolean isOffline = !gridHasCore || gridShutdown || deviceCount == 0;

        // 宽度方向：取"竖直方向(0,1,0) × 视线方向(相机→门)"的水平单位向量，
        // 使光晕始终面向玩家。避免固定取 X 轴时，相机正对 X 方向观察
        // 导致带状网格压缩成不可见的线（已知瑕疵修复）。
        Vec3 viewDir = stubStart.subtract(cameraPos); // 相机 → 门
        double horizLen = Math.sqrt(viewDir.x * viewDir.x + viewDir.z * viewDir.z);
        float ux, uz;
        if (horizLen > 1.0e-4) {
            // up(0,1,0) × view = (view.z, 0, -view.x)，取水平分量归一化
            ux = (float) (viewDir.z / horizLen);
            uz = (float) (-viewDir.x / horizLen);
        } else {
            // 视线几乎竖直（相机在门正上/正下方）：退化到 X 轴，避免除零
            ux = 1.0f;
            uz = 0.0f;
        }

        // ---- 首顶点（t=0）：与主循环 i=0 处同一公式，保证分隔插入颜色/宽度一致 ----
        float firstDist = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        float firstHalfWidth = Math.max(BEAM_HALF_WIDTH * 1.5f, firstDist * MIN_HALF_WIDTH_PER_BLOCK);
        float firstFade = isOffline
                ? 1.0f - offlineProgress(0.0f, now, powerOffStartTime, poweringOff)
                : 0.0f;
        float firstR = 0.3f + 0.7f * firstFade;
        float firstG = 1.0f - firstFade;
        float firstB = 1.0f - firstFade;

        // TRIANGLE_STRIP 多条带分隔（与 drawBeamStrip 同理，见其注释）
        if (prevTail != null) {
            buffer.addVertex(prevTail[0], prevTail[1], prevTail[2])
                    .setColor(prevTail[3], prevTail[4], prevTail[5], prevTail[6]);
            buffer.addVertex(sx + ux * firstHalfWidth, sy, sz + uz * firstHalfWidth)
                    .setColor(firstR, firstG, firstB, COLOR_A);
            buffer.addVertex(sx + ux * firstHalfWidth, sy, sz + uz * firstHalfWidth)
                    .setColor(firstR, firstG, firstB, COLOR_A);
        }

        float[] tail = null;
        int segments = Math.max((int) (GATE_STUB_LENGTH / SEGMENT_LENGTH), 1);
        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            float cx = sx + t * (ex - sx);
            float cy = sy + t * (ey - sy);
            float cz = sz + t * (ez - sz);

            // 屏幕空间最小宽度：按顶点自身到相机的距离放大半宽（与 drawBeamStrip 同规则）
            float distToCamera = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            float halfWidth = Math.max(BEAM_HALF_WIDTH * 1.5f, distToCamera * MIN_HALF_WIDTH_PER_BLOCK);
            float rx = ux * halfWidth;
            float rz = uz * halfWidth;

            // 断电渐变：沿竖直光晕自下而上按 offlineProgress 时间轴扩散
            float fade = isOffline
                    ? 1.0f - offlineProgress(t * GATE_STUB_LENGTH, now, powerOffStartTime, poweringOff)
                    : 0.0f;
            float r = 0.3f + 0.7f * fade;
            float g = 1.0f - fade;
            float b = 1.0f - fade;

            buffer.addVertex(cx + rx, cy, cz + rz).setColor(r, g, b, COLOR_A);
            buffer.addVertex(cx - rx, cy, cz - rz).setColor(r, g, b, COLOR_A);
            if (i == segments) {
                // 本带末顶点（右顶点），作为下一条带的分隔锚点
                tail = new float[]{cx - rx, cy, cz - rz, r, g, b, COLOR_A};
            }
        }
        return tail;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 分隔链推进：本带产出顶点（tail != null）时替换锚点，否则保留上一带末顶点。
     * <p>剔除/零长度连接不产出顶点，不能把链断成 null——否则下一带会被误判为首条，
     * 漏插退化分隔，幽灵斜线复发。</p>
     */
    private static float[] appendOrNull(float[] prevTail, float[] tail) {
        return tail != null ? tail : prevTail;
    }

    /**
     * 连接可见性剔除：渲染距离判定（任一端点进入即保留）+ 包围盒视锥判定。
     * <p>主网连接与孤岛连接共用同一套剔除规则。</p>
     * <p>距离剔除不能按「连接中点」判定：超长连接的中点可能超出渲染距离，
     * 但一端就在玩家旁边——中点判定会把整条激光剔除（表现为站在设备旁
     * 却看不到连线），故改为「两端都超距才剔除」。</p>
     */
    private static boolean isVisible(Frustum frustum, Vec3 cameraPos, double renderDistanceBlocks,
                                     Vec3 startWorld, Vec3 endWorld) {
        if (startWorld.distanceTo(cameraPos) > renderDistanceBlocks
                && endWorld.distanceTo(cameraPos) > renderDistanceBlocks) {
            return false;
        }
        // 包围盒宽度按最远端点的屏幕空间最小半宽计算：
        // 远端激光最宽可达 maxDist * MIN_HALF_WIDTH_PER_BLOCK，若仍按
        // BEAM_HALF_WIDTH 膨胀，加宽后的光束会在视锥边缘被错误剔除。
        double maxDist = Math.max(startWorld.distanceTo(cameraPos), endWorld.distanceTo(cameraPos));
        double inflate = Math.max(BEAM_HALF_WIDTH, maxDist * MIN_HALF_WIDTH_PER_BLOCK);
        AABB aabb = new AABB(startWorld, endWorld).inflate(inflate);
        return frustum == null || frustum.isVisible(aabb);
    }

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
