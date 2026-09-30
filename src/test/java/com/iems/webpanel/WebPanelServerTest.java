package com.iems.webpanel;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M10 V-13：HTTP 服务集成单测（真实 HttpServer + 假数据源，本机回环随机端口）。
 * <p>
 * 覆盖：信封、404/405、全部 {@code /api/*} 端点 Token 鉴权（请求头 / 查询参数，
 * 缺失/错误/正确）、400 非法体、409 无核心、413 超限。
 * </p>
 */
class WebPanelServerTest {

    private static final String TOKEN = "test-token-123";

    private static FakeSource source;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void startServer() {
        source = new FakeSource();
        WebPanelConfig cfg = new WebPanelConfig(true, "127.0.0.1", 0, TOKEN);
        WebPanelServer.start(cfg, source);
        port = WebPanelServer.activePort();
        assertTrue(port > 0, "server should bind an ephemeral port");
    }

    /** 每用例重置假源状态，避免用例间共享静态实例导致断言串扰。 */
    @BeforeEach
    void resetSource() {
        source.lastActive.set(false);
        source.corePresent = true;
    }

    @AfterAll
    static void stopServer() {
        WebPanelServer.stop();
    }

    // ------------------------------------------------------------------
    // 只读端点
    // ------------------------------------------------------------------

    @Test
    void healthReturnsOk() throws Exception {
        HttpResponse<String> res = get("/health");
        assertEquals(200, res.statusCode());
        assertEquals("ok", res.body());
    }

    @Test
    void statusReturnsEnvelope() throws Exception {
        HttpResponse<String> res = getWithToken("/api/status");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().startsWith("{\"schema\":1,\"ts\":"), res.body());
        assertTrue(res.body().contains("\"data\":{\"gridShutdown\":false}"), res.body());
        assertEquals("application/json; charset=utf-8",
                res.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void devicesAndTopologyAreServed() throws Exception {
        assertEquals(200, getWithToken("/api/devices").statusCode());
        assertEquals(200, getWithToken("/api/topology").statusCode());
        assertEquals(200, getWithToken("/api/connections").statusCode());
        assertEquals(200, getWithToken("/api/metrics").statusCode());
    }

    @Test
    void readEndpointsWithoutTokenReturn401() throws Exception {
        for (String path : new String[]{"/api/status", "/api/devices", "/api/connections",
                "/api/topology", "/api/metrics"}) {
            HttpResponse<String> res = get(path);
            assertEquals(401, res.statusCode(), path);
            assertTrue(res.body().contains("\"code\":401"), path + " -> " + res.body());
        }
    }

    @Test
    void readEndpointAcceptsQueryToken() throws Exception {
        HttpResponse<String> res = get("/api/status?token=" + TOKEN);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"data\":"), res.body());
    }

    @Test
    void readEndpointRejectsWrongQueryToken() throws Exception {
        assertEquals(401, get("/api/status?token=nope").statusCode());
    }

    @Test
    void rootServesEntryPage() throws Exception {
        HttpResponse<String> res = get("/");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("IEMS Web 监控面板"), res.body());
    }

    @Test
    void unknownPathReturns404() throws Exception {
        HttpResponse<String> res = get("/api/nope");
        assertEquals(404, res.statusCode());
        assertTrue(res.body().contains("\"code\":404"), res.body());
    }

    @Test
    void postOnReadEndpointReturns405() throws Exception {
        HttpResponse<String> res = CLIENT.send(HttpRequest.newBuilder(uri("/api/status"))
                .header(WebPanelServer.TOKEN_HEADER, TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res.statusCode());
    }

    @Test
    void postOnReadEndpointWithoutTokenReturns401() throws Exception {
        HttpResponse<String> res = CLIENT.send(HttpRequest.newBuilder(uri("/api/status"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, res.statusCode());
    }

    // ------------------------------------------------------------------
    // 写端点（鉴权）
    // ------------------------------------------------------------------

    @Test
    void gridActiveWithoutTokenReturns401() throws Exception {
        HttpResponse<String> res = postActive("{\"active\":true}", null);
        assertEquals(401, res.statusCode());
        assertFalse(source.lastActive.get());
    }

    @Test
    void gridActiveWithWrongTokenReturns401() throws Exception {
        HttpResponse<String> res = postActive("{\"active\":true}", "wrong-token");
        assertEquals(401, res.statusCode());
        assertFalse(source.lastActive.get());
    }

    @Test
    void gridActiveWithTokenSucceeds() throws Exception {
        HttpResponse<String> res = postActive("{\"active\":true}", TOKEN);
        assertEquals(200, res.statusCode());
        assertTrue(source.lastActive.get());
        assertTrue(res.body().contains("\"data\":"), res.body());
    }

    @Test
    void gridActiveInvalidBodyReturns400() throws Exception {
        HttpResponse<String> res = postActive("{\"active\":\"yes\"}", TOKEN);
        assertEquals(400, res.statusCode());
        assertTrue(res.body().contains("\"code\":400"), res.body());
    }

    @Test
    void gridActiveWithoutCoreReturns409() throws Exception {
        source.corePresent = false;
        try {
            HttpResponse<String> res = postActive("{\"active\":false}", TOKEN);
            assertEquals(409, res.statusCode());
            assertTrue(res.body().contains("\"code\":409"), res.body());
        } finally {
            source.corePresent = true;
        }
    }

    @Test
    void gridActiveOversizedBodyReturns413() throws Exception {
        StringBuilder big = new StringBuilder("{\"active\":true,\"pad\":\"");
        big.append("x".repeat(8192)).append("\"}");
        HttpResponse<String> res = postActive(big.toString(), TOKEN);
        assertEquals(413, res.statusCode());
        assertTrue(res.body().contains("\"code\":413"), res.body());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 带 Token 请求头的 GET。 */
    private static HttpResponse<String> getWithToken(String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(uri(path))
                .header(WebPanelServer.TOKEN_HEADER, TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postActive(String body, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/api/grid/active"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header(WebPanelServer.TOKEN_HEADER, token);
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** 假数据源：只读端点返回固定 data JSON；写端点记录并受 corePresent 门控。 */
    private static final class FakeSource implements WebPanelSource {
        final java.util.concurrent.atomic.AtomicBoolean lastActive = new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean corePresent = true;

        @Override public String status() { return "{\"gridShutdown\":false}"; }
        @Override public String devices() { return "{\"devices\":[]}"; }
        @Override public String connections() { return "{\"connections\":[]}"; }
        @Override public String topology() { return "{\"mainNetwork\":[]}"; }
        @Override public String metrics() { return "{\"samples\":[]}"; }
        @Override public boolean setGridActive(boolean active) {
            lastActive.set(active);
            return corePresent;
        }
        @Override public String rootPage() {
            return "<html><body>IEMS Web 监控面板</body></html>";
        }
    }
}
