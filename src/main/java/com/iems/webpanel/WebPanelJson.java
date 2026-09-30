package com.iems.webpanel;

/**
 * Web 面板 JSON 信封与错误体（M10 §4 / §8）。
 * <p>
 * 所有业务端点统一信封 {@code {"schema":1,"ts":<epochMs>,"data":<...>}}；
 * 错误体 {@code {"schema":1,"ts":<epochMs>,"error":{"code":..,"message":".."}}}。
 * </p>
 */
public final class WebPanelJson {

    /** 数据模型版本（破坏性变更时递增）。 */
    public static final int SCHEMA = 1;

    private WebPanelJson() {
    }

    /** 标准信封（时间戳取当前时间）。 */
    public static String envelope(String dataJson) {
        return envelope(dataJson, System.currentTimeMillis());
    }

    /** 标准信封（显式时间戳，供测试断言）。 */
    public static String envelope(String dataJson, long ts) {
        return new JsonWriter().obj()
                .key("schema").val(SCHEMA)
                .key("ts").val(ts)
                .key("data").raw(dataJson == null ? "null" : dataJson)
                .end()
                .build();
    }

    /** 错误体。 */
    public static String error(int code, String message) {
        return new JsonWriter().obj()
                .key("schema").val(SCHEMA)
                .key("ts").val(System.currentTimeMillis())
                .key("error").obj()
                .key("code").val(code)
                .kv("message", message)
                .end()
                .end()
                .build();
    }

    /** GlobalPos → JSON 对象片段。 */
    public static String pos(String dimension, int x, int y, int z) {
        return new JsonWriter().obj()
                .kv("dimension", dimension)
                .key("x").val(x)
                .key("y").val(y)
                .key("z").val(z)
                .end()
                .build();
    }
}
