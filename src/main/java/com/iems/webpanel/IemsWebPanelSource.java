package com.iems.webpanel;

import com.iems.api.IEMSAPI;
import com.iems.core.grid.Connection;
import com.iems.core.grid.GlobalPos;
import com.iems.core.grid.GridSnapshot;
import com.iems.core.grid.GridTopology;
import com.iems.core.node.CoreDevice;
import com.iems.core.node.IEnergyNode;
import com.iems.core.node.StorageDevice;

import java.math.BigInteger;

/**
 * Web 面板数据源实现（M10 §9：全部数据来自 IEMSAPI 门面与 GridSnapshot，核心零重构）。
 * <p>
 * 持有时序指标环形缓冲（服务端 tick 采样写入、HTTP 线程只读）；
 * 各端点方法返回已序列化的 data JSON 片段。
 * </p>
 */
public final class IemsWebPanelSource implements WebPanelSource {

    /** 指标采样间隔（tick），20 tick = 1 秒。 */
    public static final int METRIC_INTERVAL_TICKS = 20;

    private static final IemsWebPanelSource INSTANCE = new IemsWebPanelSource();

    /** 环形缓冲容量：3600 点 ≈ 1 小时 @ 1 秒采样。 */
    private static final int METRIC_CAPACITY = 3600;

    private final MetricBuffer metricBuffer = new MetricBuffer(METRIC_CAPACITY);

    private IemsWebPanelSource() {
    }

    public static IemsWebPanelSource instance() {
        return INSTANCE;
    }

    /** 服务端 tick 驱动：采样一帧（每 METRIC_INTERVAL_TICKS tick 调用一次）。 */
    public void sample() {
        metricBuffer.add(new MetricBuffer.Sample(System.currentTimeMillis(),
                IEMSAPI.getCurrentEnergy(),
                IEMSAPI.getTotalCapacity(),
                IEMSAPI.getProtocolUsed().intValue(),
                IEMSAPI.getSnapshot().getMainNetwork().size()));
    }

    // ------------------------------------------------------------------
    // 端点 data JSON
    // ------------------------------------------------------------------

    @Override
    public String status() {
        GridSnapshot snap = IEMSAPI.getSnapshot();
        boolean shutdown = snap.isGridShutdown();
        boolean protocol = IEMSAPI.isProtocolShutdown();
        return new JsonWriter().obj()
                .key("corePos").raw(corePosOrNull(snap))
                .kv("currentEnergy", IEMSAPI.getCurrentEnergy())
                .kv("totalCapacity", IEMSAPI.getTotalCapacity())
                .kv("protocolUsed", IEMSAPI.getProtocolUsed())
                .kv("protocolTotal", IEMSAPI.getProtocolTotal())
                .key("deviceCount").val(snap.getMainNetwork().size())
                .key("gridShutdown").val(shutdown)
                .kv("shutdownCause", WebPanelMapping.shutdownCause(shutdown, protocol))
                .key("isProtocolShutdown").val(protocol)
                .end().build();
    }

    @Override
    public String devices() {
        // 核心独立于设备池（DeviceRegistry.getAll 不含核心），显式并入首项
        JsonWriter w = new JsonWriter();
        w.obj().key("devices").arr();
        CoreDevice core = IEMSAPI.getRegistry().getCore();
        GlobalPos corePos = IEMSAPI.getRegistry().getCorePos();
        if (core != null && corePos != null) {
            w.raw(deviceJson(corePos, core, true));
        }
        for (GlobalPos pos : IEMSAPI.getRegistry().getAllPositions()) {
            IEnergyNode node = IEMSAPI.getRegistry().get(pos);
            if (node != null) {
                w.raw(deviceJson(pos, node, false));
            }
        }
        w.endArr().end();
        return w.build();
    }

    @Override
    public String connections() {
        GridSnapshot snap = IEMSAPI.getSnapshot();
        JsonWriter w = new JsonWriter();
        // 主网可达连接（两端均接入核心电网）
        w.obj().key("connections").arr();
        GridTopology.instance().getConnections().stream()
                .filter(c -> snap.isReachable(c.start()) && snap.isReachable(c.end()))
                .forEach(c -> w.raw(connectionJson(c)));
        w.endArr();
        // 边界连接（连接存在但远端未注册/未加载）
        w.key("pendingConnections").arr();
        for (Connection c : snap.getPendingConnections()) {
            w.raw(connectionJson(c));
        }
        w.endArr().end();
        return w.build();
    }

    @Override
    public String topology() {
        GridSnapshot snap = IEMSAPI.getSnapshot();
        JsonWriter w = new JsonWriter();
        w.obj()
                .key("gridShutdown").val(snap.isGridShutdown())
                .key("corePos").raw(corePosOrNull(snap))
                .key("mainNetwork").arr();
        for (GlobalPos p : snap.getMainNetwork()) {
            w.raw(posOf(p));
        }
        w.endArr().key("orphanNetworks").arr();
        for (var cluster : snap.getOrphanNetworks()) {
            w.arr();
            for (GlobalPos p : cluster) {
                w.raw(posOf(p));
            }
            w.endArr();
        }
        w.endArr().key("pendingConnections").arr();
        for (Connection c : snap.getPendingConnections()) {
            w.raw(connectionJson(c));
        }
        w.endArr().end();
        return w.build();
    }

    @Override
    public String metrics() {
        JsonWriter w = new JsonWriter();
        w.obj()
                .key("intervalTicks").val(METRIC_INTERVAL_TICKS)
                .key("capacity").val(metricBuffer.capacity())
                .key("samples").arr();
        for (MetricBuffer.Sample s : metricBuffer.snapshot()) {
            w.obj()
                    .key("t").val(s.t())
                    .kv("currentEnergy", s.currentEnergy())
                    .kv("totalCapacity", s.totalCapacity())
                    .key("protocolUsed").val(s.protocolUsed())
                    .key("deviceCount").val(s.deviceCount())
                    .end();
        }
        w.endArr().end();
        return w.build();
    }

    @Override
    public boolean setGridActive(boolean active) {
        // H-01：HTTP 线程不得直接触碰游戏状态（与 tick 结算并发读写）。
        // 排队到服务端主线程执行并同步等待结果（500ms 超时按失败处理 → 409）。
        return Boolean.TRUE.equals(IEMSAPI.callOnServerThread(() -> {
            if (IEMSAPI.getRegistry().getCore() == null) {
                return false; // 无核心 → 409
            }
            IEMSAPI.setGridActive(active);
            return true;
        }, 500L));
    }

    /** 服务器停止时重置面板状态（清空时序缓冲，防单机切世界指标残留）。 */
    public void reset() {
        metricBuffer.clear();
    }

    @Override
    public String rootPage() {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><meta charset="UTF-8"><title>IEMS Web 监控面板</title></head>
                <body>
                <h1>IEMS Web 监控面板</h1>
                <p>除 <a href="/health">/health</a> 外的所有 API 端点均需 Token：
                请求头 <code>X-IEMS-Token: &lt;token&gt;</code>，或 URL 查询参数
                <code>?token=&lt;token&gt;</code>（Token 见
                <code>config/DLZstudio/IEMS/APIserve.toml</code>）。</p>
                <ul>
                  <li><a href="/health">/health</a> 存活探针（免鉴权）</li>
                  <li><a href="/api/status">/api/status</a> 电网总览</li>
                  <li><a href="/api/devices">/api/devices</a> 设备列表</li>
                  <li><a href="/api/connections">/api/connections</a> 连接列表</li>
                  <li><a href="/api/topology">/api/topology</a> 拓扑快照</li>
                  <li><a href="/api/metrics">/api/metrics</a> 时序指标</li>
                </ul>
                <p>写操作：<code>POST /api/grid/active</code>（同样需 Token）</p>
                </body>
                </html>
                """;
    }

    // ------------------------------------------------------------------
    // 序列化辅助
    // ------------------------------------------------------------------

    private static String deviceJson(GlobalPos pos, IEnergyNode node, boolean isCore) {
        BigInteger stored = BigInteger.ZERO;
        BigInteger max = BigInteger.ZERO;
        BigInteger io = BigInteger.ZERO;
        if (node instanceof StorageDevice sd) {
            stored = sd.getStoredEnergy();
            max = sd.getMaxEnergy();
            io = sd.getIoRatePerTick();
        } else if (node instanceof CoreDevice core) {
            stored = core.getCurrentEnergy();
            max = core.getEnergyCapacity();
            io = core.getPowerGenRate();
        }
        return new JsonWriter().obj()
                .key("pos").raw(posOf(pos))
                .key("isCore").val(isCore)
                .kv("type", WebPanelMapping.deviceType(node))
                .kv("displayName", node.getDeviceName())
                .kv("protocolCost", node.getProtocolCost())
                .key("connected").val(IEMSAPI.isDeviceConnected(pos))
                .kv("storedEnergy", stored)
                .kv("maxEnergy", max)
                .kv("ioRatePerTick", io)
                .end().build();
    }

    private static String connectionJson(Connection c) {
        return new JsonWriter().obj()
                .key("start").raw(posOf(c.start()))
                .key("end").raw(posOf(c.end()))
                .kv("type", c.type().name())
                .key("startAnchor").raw(anchorJson(c.startDx(), c.startDy(), c.startDz()))
                .key("endAnchor").raw(anchorJson(c.endDx(), c.endDy(), c.endDz()))
                .end().build();
    }

    private static String posOf(GlobalPos pos) {
        return WebPanelJson.pos(pos.dimension().location().toString(),
                pos.pos().getX(), pos.pos().getY(), pos.pos().getZ());
    }

    private static String anchorJson(float dx, float dy, float dz) {
        return new JsonWriter().obj().key("dx").val(dx).key("dy").val(dy).key("dz").val(dz).end().build();
    }

    private static String corePosOrNull(GridSnapshot snap) {
        GlobalPos corePos = snap.getCorePos();
        return corePos == null ? "null" : posOf(corePos);
    }
}
