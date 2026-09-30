package com.iems.core.energy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SE:FE 汇率配置读取/解析单测（TOML 子集）。
 */
class EnergyConfigTest {

    @Test
    void defaultsAreTenFePerSe() {
        EnergyConfig def = EnergyConfig.defaults();
        assertEquals(BigInteger.TEN, def.fePerSe());
        assertEquals(BigInteger.TEN, EnergyConfig.DEFAULT_FE_PER_SE);
    }

    @Test
    void configFileLivesUnderDlzstudioIems() {
        String path = EnergyConfig.configFile(Path.of("config"))
                .toString().replace('\\', '/');
        assertEquals("config/DLZstudio/IEMS/Energy.toml", path);
    }

    @Test
    void parseValidToml() {
        String content = """
                # 注释行
                [energy]
                fePerSe = 100
                """;
        EnergyConfig cfg = EnergyConfig.parse(content, EnergyConfig.defaults());
        assertEquals(new BigInteger("100"), cfg.fePerSe());
    }

    @Test
    void parseMissingKeyFallsBackToDefault() {
        EnergyConfig cfg = EnergyConfig.parse("[energy]\n# 只有注释\n",
                EnergyConfig.defaults());
        assertEquals(EnergyConfig.DEFAULT_FE_PER_SE, cfg.fePerSe());
    }

    @Test
    void parseIgnoresOtherSections() {
        String content = """
                [APIserve]
                fePerSe = 100
                """;
        EnergyConfig cfg = EnergyConfig.parse(content, EnergyConfig.defaults());
        assertEquals(EnergyConfig.DEFAULT_FE_PER_SE, cfg.fePerSe());
    }

    @Test
    void parseNonNumericFallsBackToDefault() {
        EnergyConfig cfg = EnergyConfig.parse("[energy]\nfePerSe = abc\n",
                EnergyConfig.defaults());
        assertEquals(EnergyConfig.DEFAULT_FE_PER_SE, cfg.fePerSe());
    }

    @Test
    void parseClampsAboveMax() {
        EnergyConfig cfg = EnergyConfig.parse("[energy]\nfePerSe = 99999999999999999999999999999\n",
                EnergyConfig.defaults());
        assertEquals(EnergyConfig.MAX_FE_PER_SE, cfg.fePerSe());
    }

    @Test
    void parseClampsBelowMin() {
        EnergyConfig zero = EnergyConfig.parse("[energy]\nfePerSe = 0\n",
                EnergyConfig.defaults());
        assertEquals(EnergyConfig.MIN_FE_PER_SE, zero.fePerSe());
        EnergyConfig negative = EnergyConfig.parse("[energy]\nfePerSe = -5\n",
                EnergyConfig.defaults());
        assertEquals(EnergyConfig.MIN_FE_PER_SE, negative.fePerSe());
    }

    @Test
    void loadCreatesDefaultFileWhenMissing(@TempDir Path dir) throws IOException {
        EnergyConfig cfg = EnergyConfig.load(dir);
        assertEquals(EnergyConfig.DEFAULT_FE_PER_SE, cfg.fePerSe());
        Path file = EnergyConfig.configFile(dir);
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("[energy]"));
    }

    @Test
    void loadRoundTripsWrittenValue(@TempDir Path dir) throws IOException {
        Path file = EnergyConfig.configFile(dir);
        Files.createDirectories(file.getParent());
        EnergyConfig.write(file, new EnergyConfig(new BigInteger("64")));
        assertEquals(new BigInteger("64"), EnergyConfig.load(dir).fePerSe());
    }

    @Test
    void reloadAppliesNewValue(@TempDir Path dir) throws IOException {
        Path file = EnergyConfig.configFile(dir);
        EnergyConfig.load(dir);
        Files.writeString(file, "[energy]\nfePerSe = 4096\n", StandardCharsets.UTF_8);

        EnergyConfig reloaded = EnergyConfig.reload(dir);
        assertEquals(new BigInteger("4096"), reloaded.fePerSe());
        assertEquals(new BigInteger("4096"), EnergyConverter.getFeFactor(EnergyUnit.SE));
        EnergyConverter.setFeFactor(EnergyUnit.SE, EnergyConfig.DEFAULT_FE_PER_SE);
    }

    @Test
    void reloadKeepsCurrentValueOnMissingKey(@TempDir Path dir) throws IOException {
        Path file = EnergyConfig.configFile(dir);
        EnergyConfig.load(dir);
        Files.writeString(file, "[energy]\nfePerSe = 2048\n", StandardCharsets.UTF_8);
        EnergyConfig.reload(dir);

        // 局部编辑掉键：应保留当前生效值 2048，而非回退出厂默认
        Files.writeString(file, "[energy]\n# 只剩注释\n", StandardCharsets.UTF_8);
        EnergyConfig reloaded = EnergyConfig.reload(dir);
        assertEquals(new BigInteger("2048"), reloaded.fePerSe());
        EnergyConverter.setFeFactor(EnergyUnit.SE, EnergyConfig.DEFAULT_FE_PER_SE);
    }

    @Test
    void reloadCreatesDefaultFileWhenMissing(@TempDir Path dir) throws IOException {
        Path file = EnergyConfig.configFile(dir);
        EnergyConfig reloaded = EnergyConfig.reload(dir);
        assertEquals(EnergyConfig.DEFAULT_FE_PER_SE, reloaded.fePerSe());
        assertTrue(Files.exists(file));
        EnergyConverter.setFeFactor(EnergyUnit.SE, EnergyConfig.DEFAULT_FE_PER_SE);
    }

    @Test
    void applyInjectsIntoConverter() {
        BigInteger original = EnergyConverter.getFeFactor(EnergyUnit.SE);
        try {
            EnergyConfig.apply(new EnergyConfig(new BigInteger("128")));
            assertEquals(new BigInteger("128"), EnergyConverter.getFeFactor(EnergyUnit.SE));
        } finally {
            EnergyConverter.setFeFactor(EnergyUnit.SE, original);
        }
    }
}