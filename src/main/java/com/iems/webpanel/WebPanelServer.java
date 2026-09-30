package com.iems.webpanel;

import com.iems.IEMS;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web 监控面板 HTTP 服务（M10 §5）。
 * <p>
 * JDK 内置 {@link HttpServer}，零第三方依赖。职责：
 * 路由分发、数据信封（{@link WebPanelJson}）、错误码（§8）、
 * <b>全部 {@code /api/*} 端点鉴权</b>（{@code X-IEMS-Token} 请求头或 {@code ?token=} 查询参数，
 * 常量时间比较）、请求体 4 KiB 上限、固定小线程池（2 工作线程 + 64 队列）。
 * </p>
 * <p>
 * 免鉴权端点仅 {@code /health}（存活探针）与 {@code /}（端点说明页）。
 * </p>
 * <p>
 * 单例生命周期由 {@link IEMSEvents} 驱动（ServerStarted 启动 / ServerStopped 停止）；
 * 指标采样由服务端 tick 驱动（每 20 tick 调用 {@link IemsWebPanelSource#sample()}）。
 * </p>
 */
public final class WebPanelServer {

    /** 鉴权 Token 请求头。 */
    public static final String TOKEN_HEADER = "X-IEMS-Token";

    /** POST 请求体上限（4 KiB）。 */
    public static final int MAX_BODY_BYTES = 4096;

    private static final String CONTENT_JSON = "application/json; charset=utf-8";

    private static final Pattern ACTIVE_PATTERN =
            Pattern.compile("\\{\\s*\"active\"\\s*:\\s*(true|false)\\s*}");

    /** 运行中的实例（单例）。 */
    private static volatile WebPanelServer ACTIVE;

    private final HttpServer server;
    private final WebPanelConfig config;
    private final WebPanelSource source;
    /** 固定小线程池（M-01：持有引用以便 stop() 时手动回收，防跨世界线程泄漏）。 */
    private final ThreadPoolExecutor executor;
    private volatile boolean running = true;

    private WebPanelServer(HttpServer server, WebPanelConfig config, WebPanelSource source,
                           ThreadPoolExecutor executor) {
        this.server = server;
        this.config = config;
        this.source = source;
        this.executor = executor;
    }

    // ------------------------------------------------------------------
    // 生命周期（单例）
    // ------------------------------------------------------------------

    /** 从配置目录加载配置并启动（禁用时仅记录日志）。 */
    public static synchronized void start(Path configDir, WebPanelSource source) {
        start(WebPanelConfig.load(configDir), source);
    }

    /** 以给定配置启动（供测试注入固定配置/随机端口）。 */
    public static synchronized void start(WebPanelConfig config, WebPanelSource source) {
        if (ACTIVE != null) {
            return;
        }
        if (!config.enabled()) {
            IEMS.LOGGER.info("IEMS WebPanel: 已禁用（{} enabled=false），不启动",
                    "config/" + WebPanelConfig.SUBDIR_1 + "/" + WebPanelConfig.SUBDIR_2
                            + "/" + WebPanelConfig.FILE_NAME);
            return;
        }
        try {
            InetSocketAddress address = new InetSocketAddress(config.host(), config.port());
            HttpServer server = HttpServer.create(address, 0);
            ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.CallerRunsPolicy());
            WebPanelServer panel = new WebPanelServer(server, config, source, executor);
            panel.registerContexts();
            server.setExecutor(executor);
            server.start();
            ACTIVE = panel;
            if (!"127.0.0.1".equals(config.host()) && !"localhost".equalsIgnoreCase(config.host())) {
                IEMS.LOGGER.warn("IEMS WebPanel: 绑定 {}（非本机回环）——远程暴露前请务必修改 token 并配置反向代理 TLS",
                        config.host());
            }
            IEMS.LOGGER.info("IEMS WebPanel: 已启动 http://{}:{}/ (schema={})",
                    config.host(), server.getAddress().getPort(), WebPanelJson.SCHEMA);
        } catch (IOException e) {
            IEMS.LOGGER.error("IEMS WebPanel: 启动失败（{}）", e.getMessage());
        } catch (RuntimeException e) {
            // L-01 兜底：非法 host / 参数等运行时异常（如 InetSocketAddress 的
            // IllegalArgumentException）不得外逸至 onServerStarted 中断服务器启动
            IEMS.LOGGER.error("IEMS WebPanel: 启动失败（非法配置：{}）", e.getMessage());
        }
    }

    /** 优雅停止并释放实例。 */
    public static synchronized void stop() {
        WebPanelServer panel = ACTIVE;
        if (panel == null) {
            return;
        }
        ACTIVE = null;
        panel.running = false;
        panel.server.stop(1); // 最多等 1 秒让在途请求收尾
        // M-01：HttpServer 不拥有线程池——不手动关闭则 2 个工作线程跨世界存活
        panel.executor.shutdownNow();
        IEMS.LOGGER.info("IEMS WebPanel: 已停止");
    }

    public static boolean isRunning() {
        WebPanelServer panel = ACTIVE;
        return panel != null && panel.running;
    }

    /** 当前配置（测试读取 token 用）。 */
    public static WebPanelConfig activeConfig() {
        WebPanelServer panel = ACTIVE;
        return panel == null ? null : panel.config;
    }

    /** 实际监听端口（绑定 0 时由系统分配；测试用）。 */
    public static int activePort() {
        WebPanelServer panel = ACTIVE;
        return panel == null ? -1 : panel.server.getAddress().getPort();
    }

    // ------------------------------------------------------------------
    // 路由
    // ------------------------------------------------------------------

    private void registerContexts() {
        // 健康探针（不套信封，免鉴权）
        server.createContext("/health", this::handleHealth);
        // 业务端点（GET，Token 鉴权）
        server.createContext("/api/status", ex -> handleRead(ex, source::status));
        server.createContext("/api/devices", ex -> handleRead(ex, source::devices));
        server.createContext("/api/connections", ex -> handleRead(ex, source::connections));
        server.createContext("/api/topology", ex -> handleRead(ex, source::topology));
        server.createContext("/api/metrics", ex -> handleRead(ex, source::metrics));
        // 写端点（POST，Token 鉴权）
        server.createContext("/api/grid/active", this::handleGridActive);
        // 根路径 + 未知路径兜底（404）
        server.createContext("/", this::handleRootOrNotFound);
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            sendMethodNotAllowed(ex);
            return;
        }
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private void handleRead(HttpExchange ex, DataSupplier data) throws IOException {
        // 鉴权先于方法判定：未授权调用者不应获知端点接受的 HTTP 方法
        if (!authorized(ex)) {
            sendJson(ex, 401, WebPanelJson.error(401, "Unauthorized"));
            return;
        }
        if (!isGet(ex)) {
            sendMethodNotAllowed(ex);
            return;
        }
        try {
            sendJson(ex, 200, WebPanelJson.envelope(data.get()));
        } catch (RuntimeException e) {
            IEMS.LOGGER.error("IEMS WebPanel: 只读端点异常", e);
            sendJson(ex, 500, WebPanelJson.error(500, "Internal Server Error"));
        }
    }

    private void handleGridActive(HttpExchange ex) throws IOException {
        // 鉴权先于方法判定（与只读端点一致）
        if (!authorized(ex)) {
            sendJson(ex, 401, WebPanelJson.error(401, "Unauthorized"));
            return;
        }
        if (!isPost(ex)) {
            sendMethodNotAllowed(ex);
            return;
        }
        try {
            byte[] body = readBodyLimited(ex);
            Boolean active = parseActive(body);
            if (active == null) {
                sendJson(ex, 400, WebPanelJson.error(400, "Invalid body: {\"active\": true|false}"));
                return;
            }
            if (!source.setGridActive(active)) {
                sendJson(ex, 409, WebPanelJson.error(409, "Grid not established (no core)"));
                return;
            }
            IEMS.LOGGER.info("IEMS WebPanel: grid active -> {} from {}", active, ex.getRemoteAddress());
            sendJson(ex, 200, WebPanelJson.envelope(source.status()));
        } catch (BodyTooLargeException e) {
            sendJson(ex, 413, WebPanelJson.error(413, "Payload Too Large"));
        } catch (Exception e) {
            IEMS.LOGGER.error("IEMS WebPanel: 写端点异常", e);
            sendJson(ex, 500, WebPanelJson.error(500, "Internal Server Error"));
        }
    }

    private void handleRootOrNotFound(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            sendMethodNotAllowed(ex);
            return;
        }
        if ("/".equals(ex.getRequestURI().getPath())) {
            byte[] page = source.rootPage().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, page.length);
            ex.getResponseBody().write(page);
            ex.close();
            return;
        }
        sendJson(ex, 404, WebPanelJson.error(404, "Not Found"));
    }

    // ------------------------------------------------------------------
    // 鉴权 / 请求解析
    // ------------------------------------------------------------------

    /**
     * 请求鉴权：优先 {@value #TOKEN_HEADER} 请求头，其次 URL 查询参数 {@code ?token=}。
     * <p>常量时间比较，避免计时侧信道。</p>
     */
    private boolean authorized(HttpExchange ex) {
        String token = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (token == null) {
            token = queryToken(ex.getRequestURI().getRawQuery());
        }
        if (token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                config.token().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从原始查询串中提取 {@code token} 参数（URL 解码）。
     * <p>Base64 Token 可能含 {@code + / =}，调用方需 URL 编码（如 curl 的
     * {@code --data-urlencode} 或浏览器自动编码）；解码失败视为未提供。</p>
     */
    private static String queryToken(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !"token".equals(pair.substring(0, eq))) {
                continue;
            }
            try {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static byte[] readBodyLimited(HttpExchange ex) throws IOException, BodyTooLargeException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        byte[] buf = new byte[1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 解析 {@code {"active": true|false}}；非法返回 null。 */
    private static Boolean parseActive(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        Matcher m = ACTIVE_PATTERN.matcher(text);
        if (!m.matches()) {
            return null;
        }
        return Boolean.parseBoolean(m.group(1));
    }

    private static boolean isGet(HttpExchange ex) {
        return "GET".equalsIgnoreCase(ex.getRequestMethod());
    }

    private static boolean isPost(HttpExchange ex) {
        return "POST".equalsIgnoreCase(ex.getRequestMethod());
    }

    private static void sendMethodNotAllowed(HttpExchange ex) throws IOException {
        sendJson(ex, 405, WebPanelJson.error(405, "Method Not Allowed"));
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", CONTENT_JSON);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** 数据供应商（只读端点取值）。 */
    @FunctionalInterface
    private interface DataSupplier {
        String get();
    }

    /** 请求体超限标记。 */
    private static final class BodyTooLargeException extends IOException {
    }
}
