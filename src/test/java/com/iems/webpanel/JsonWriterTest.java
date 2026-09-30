package com.iems.webpanel;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M10 V-13：JSON 写出器与信封单测。
 */
class JsonWriterTest {

    @Test
    void objectWithKeyValues() {
        String json = new JsonWriter().obj()
                .kv("a", "x")
                .key("b").val(2)
                .end().build();
        assertEquals("{\"a\":\"x\",\"b\":2}", json);
    }

    @Test
    void nestedArrayAndObject() {
        String json = new JsonWriter().obj()
                .key("list").arr().val(1).val(2).endArr()
                .key("inner").obj().kv("k", "v").end()
                .end().build();
        assertEquals("{\"list\":[1,2],\"inner\":{\"k\":\"v\"}}", json);
    }

    @Test
    void bigIntegerSerializedAsString() {
        BigInteger huge = new BigInteger("123456789012345678901234567890");
        String json = new JsonWriter().obj().kv("e", huge).end().build();
        assertEquals("{\"e\":\"123456789012345678901234567890\"}", json);
    }

    @Test
    void escaping() {
        String json = new JsonWriter().obj().kv("name", "a\"b\\c\nd").end().build();
        assertTrue(json.contains("\"a\\\"b\\\\c\\nd\""), json);
    }

    @Test
    void envelopeStructure() {
        String env = WebPanelJson.envelope("{\"foo\":1}", 1727073600000L);
        assertEquals("{\"schema\":1,\"ts\":1727073600000,\"data\":{\"foo\":1}}", env);
    }

    @Test
    void errorStructure() {
        String err = WebPanelJson.error(401, "Unauthorized");
        assertTrue(err.contains("\"schema\":1"), err);
        assertTrue(err.contains("\"error\":{\"code\":401,\"message\":\"Unauthorized\"}"), err);
    }

    @Test
    void posStructure() {
        String pos = WebPanelJson.pos("minecraft:overworld", 100, 64, -200);
        assertEquals("{\"dimension\":\"minecraft:overworld\",\"x\":100,\"y\":64,\"z\":-200}", pos);
    }

    @Test
    void nullDataRendersNull() {
        assertEquals("{\"schema\":1,\"ts\":1,\"data\":null}",
                WebPanelJson.envelope(null, 1L));
    }
}
