package com.iems.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M11：渲染器配置读取/解析单测（TOML 子集）。
 */
class RendererConfigTest {

    @Test
    void defaultsEnableColorize() {
        RendererConfig def = RendererConfig.defaults();
        assertTrue(def.colorizeAutoConnect());
        assertTrue(RendererConfig.DEFAULT_COLORIZE_AUTO_CONNECT);
    }

    @Test
    void configFileLivesUnderDlzstudioIems() {
        String path = RendererConfig.configFile(Path.of("config"))
                .toString().replace('\\', '/');
        assertEquals("config/DLZstudio/IEMS/Renderer.toml", path);
    }

    @Test
    void parseValidToml() {
        String content = """
                # 注释行
                [renderer]
                colorizeAutoConnect = false
                """;
        RendererConfig cfg = RendererConfig.parse(content, RendererConfig.defaults());
        assertFalse(cfg.colorizeAutoConnect());
    }

    @Test
    void parseMissingKeyFallsBackToDefault() {
        RendererConfig cfg = RendererConfig.parse("[renderer]\n# 只有注释\n",
                RendererConfig.defaults());
        assertTrue(cfg.colorizeAutoConnect());
    }

    @Test
    void parseIgnoresOtherSections() {
        String content = """
                [APIserve]
                colorizeAutoConnect = false
                """;
        RendererConfig cfg = RendererConfig.parse(content, RendererConfig.defaults());
        assertTrue(cfg.colorizeAutoConnect());
    }

    @Test
    void loadCreatesDefaultFileWhenMissing(@TempDir Path dir) throws IOException {
        RendererConfig cfg = RendererConfig.load(dir);
        assertTrue(cfg.colorizeAutoConnect());
        Path file = RendererConfig.configFile(dir);
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("[renderer]"));
    }

    @Test
    void loadRoundTripsWrittenValue(@TempDir Path dir) throws IOException {
        Path file = RendererConfig.configFile(dir);
        Files.createDirectories(file.getParent());
        RendererConfig.write(file, new RendererConfig(false));
        assertFalse(RendererConfig.load(dir).colorizeAutoConnect());
    }
}