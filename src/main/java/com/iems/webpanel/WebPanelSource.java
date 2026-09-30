package com.iems.webpanel;

/**
 * Web 面板数据源（M10 §9 映射表）。
 * <p>
 * 服务器与数据获取解耦：{@link WebPanelServer} 只负责路由/信封/鉴权/错误码，
 * 本接口的实现负责从 {@code IEMSAPI} 门面取数并序列化为 data JSON 片段。
 * 测试可用假实现替换，无需 Minecraft 运行时。
 * </p>
 */
public interface WebPanelSource {

    /** /api/status data JSON。 */
    String status();

    /** /api/devices data JSON。 */
    String devices();

    /** /api/connections data JSON。 */
    String connections();

    /** /api/topology data JSON。 */
    String topology();

    /** /api/metrics data JSON。 */
    String metrics();

    /**
     * POST /api/grid/active：开启/关闭电网。
     *
     * @return true = 已执行；false = 无核心（前置条件不满足，服务器回 409）
     */
    boolean setGridActive(boolean active);

    /** GET / 根路径入口页（HTML）。 */
    String rootPage();
}
