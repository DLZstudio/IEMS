package com.iems.webpanel;

import java.math.BigInteger;

/**
 * 极简 JSON 写出器（零第三方依赖）。
 * <p>
 * Web 面板专用：不做解析，只做受控的拼接输出，保证：
 * 字符串转义（引号/反斜杠/控制符）、数值以十进制字符串输出、
 * 对象键按调用顺序排列。所有方法返回 {@code this} 支持链式调用。
 * </p>
 * <p>
 * 状态机：{@code commaPending}（当前上下文是否已有值，下一个并列项需加逗号）
 * + {@code keyPending}（刚写完键，下一个 token 是它的值，不加逗号）。
 * </p>
 */
public final class JsonWriter {

    private final StringBuilder sb = new StringBuilder(256);
    private boolean commaPending;
    private boolean keyPending;

    /** 开始一个对象 {@code {}}。 */
    public JsonWriter obj() {
        beforeValue();
        sb.append('{');
        commaPending = false;
        keyPending = false;
        return this;
    }

    /** 结束当前对象 {@code }}（视为一个已完成的值）。 */
    public JsonWriter end() {
        sb.append('}');
        valueWritten();
        return this;
    }

    /** 开始一个数组 {@code []}。 */
    public JsonWriter arr() {
        beforeValue();
        sb.append('[');
        commaPending = false;
        keyPending = false;
        return this;
    }

    /** 结束当前数组 {@code ]}（视为一个已完成的值）。 */
    public JsonWriter endArr() {
        sb.append(']');
        valueWritten();
        return this;
    }

    /**
     * 对象键（自动补引号与冒号；键不做转义——所有键均为受控字面量）。
     * 调用前须处于对象上下文且刚写完上一个值（或对象刚起始）。
     */
    public JsonWriter key(String k) {
        if (commaPending) {
            sb.append(',');
        }
        commaPending = false;
        keyPending = true;
        sb.append('"').append(k).append("\":");
        return this;
    }

    /** 字符串值（自动转义）。 */
    public JsonWriter val(String v) {
        beforeValue();
        sb.append('"').append(escape(v == null ? "" : v)).append('"');
        valueWritten();
        return this;
    }

    /** 长整数值。 */
    public JsonWriter val(long v) {
        beforeValue();
        sb.append(v);
        valueWritten();
        return this;
    }

    /** 整数值。 */
    public JsonWriter val(int v) {
        beforeValue();
        sb.append(v);
        valueWritten();
        return this;
    }

    /** 布尔值。 */
    public JsonWriter val(boolean v) {
        beforeValue();
        sb.append(v);
        valueWritten();
        return this;
    }

    /** 浮点值（有限数直出，非有限数输出 0）。 */
    public JsonWriter val(float v) {
        beforeValue();
        sb.append(Float.isFinite(v) ? Float.toString(v) : "0");
        valueWritten();
        return this;
    }

    /**
     * BigInteger 值：以<b>十进制字符串</b>输出（SE 可超 long，前端按字符串解析）。
     */
    public JsonWriter val(BigInteger v) {
        beforeValue();
        sb.append('"').append(v == null ? "0" : v.toString()).append('"');
        valueWritten();
        return this;
    }

    /** 原始 JSON 片段（已序列化的对象/数组/值），原样嵌入。 */
    public JsonWriter raw(String json) {
        beforeValue();
        sb.append(json);
        valueWritten();
        return this;
    }

    /** 输出完成。 */
    public String build() {
        return sb.toString();
    }

    /** 便捷：对象键值对（键：字符串值）。 */
    public JsonWriter kv(String k, String v) {
        return key(k).val(v);
    }

    /** 便捷：对象键值对（键：BigInteger 字符串值）。 */
    public JsonWriter kv(String k, BigInteger v) {
        return key(k).val(v);
    }

    /** 便捷：对象键值对（键：原始 JSON 片段）。 */
    public JsonWriter kvRaw(String k, String json) {
        return key(k).raw(json);
    }

    private void valueWritten() {
        if (keyPending) {
            keyPending = false;
        }
        commaPending = true;
    }

    /** 写值前处理：上一项已完成且当前非"键待值"状态（数组并列项）时补逗号。 */
    private void beforeValue() {
        if (commaPending && !keyPending) {
            sb.append(',');
        }
    }

    /** JSON 字符串转义：引号/反斜杠/换行/回车/制表/控制字符 → \\uXXXX。 */
    public static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
