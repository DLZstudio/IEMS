package com.iems.webpanel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M10 V-13：配置读取/解析单测（TOML 子集）。
 */
class WebPanelConfigTest {

    @Test
    void defaultsAreSane() {
        WebPanelConfig def = WebPanelConfig.defaults();
        assertTrue(def.enabled());
        assertEquals("127.0.0.1", def.host());
        assertEquals(28567, def.port());
        assertTrue(def.token() != null && def.token().length() >= 32);
    }

    @Test
    void configFileLivesUnderDlzstudioIems() {
        String path = WebPanelConfig.configFile(java.nio.file.Path.of("config"))
                .toString().replace('\\', '/');
        assertEquals("config/DLZstudio/IEMS/APIserve.toml", path);
    }

    @Test
    void tokenIsRandomizedPerCall() {
        assertNotEquals(WebPanelConfig.randomToken(), WebPanelConfig.randomToken());
    }

    @Test
    void parseValidToml() {
        String content = """
                # 注释行
                [APIserve]
                enabled = true
                host = "0.0.0.0"
                port = 3000
                token = "abc123"
                """;
        WebPanelConfig cfg = WebPanelConfig.parse(content, WebPanelConfig.defaults());
        assertTrue(cfg.enabled());
        assertEquals("0.0.0.0", cfg.host());
        assertEquals(3000, cfg.port());
        assertEquals("abc123", cfg.token());
    }

    @Test
    void parseMissingKeysFallBackToDefaults() {
        WebPanelConfig cfg = WebPanelConfig.parse("[APIserve]\nenabled = false\n",
                WebPanelConfig.defaults());
        assertFalse(cfg.enabled());
        assertEquals(WebPanelConfig.DEFAULT_HOST, cfg.host());
        assertEquals(WebPanelConfig.DEFAULT_PORT, cfg.port());
    }

    @Test
    void parseIgnoresOtherSections() {
        String content = """
                [other]
                host = "evil"
                [APIserve]
                port = 9999
                """;
        WebPanelConfig cfg = WebPanelConfig.parse(content, WebPanelConfig.defaults());
        assertEquals(WebPanelConfig.DEFAULT_HOST, cfg.host());
        assertEquals(9999, cfg.port());
    }

    @Test
    void parseInvalidPortFallsBack() {
        WebPanelConfig cfg = WebPanelConfig.parse("[APIserve]\nport = not-a-number\n",
                WebPanelConfig.defaults());
        assertEquals(WebPanelConfig.DEFAULT_PORT, cfg.port());
    }
}
