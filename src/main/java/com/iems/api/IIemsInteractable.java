package com.iems.api;

/**
 * IEMS 手动拉线交互标记接口（M8.1）。
 * <p>
 * 外部模组的 BlockEntity 实现本接口后，即可参与框架内置的
 * Shift+右键拉线交互（{@code ConnectionInteractionHandler}）：
 * </p>
 * <ul>
 *   <li>Shift+右键设备 → 进入拉线模式（HUD 显示实时距离，超距自动取消）；</li>
 *   <li>再 Shift+右键另一台设备 → 向服务端请求建立连接（服务端二次校验）；</li>
 *   <li>Shift+右键非设备方块 / 再点起点 → 取消拉线。</li>
 * </ul>
 * <p>
 * 本接口是纯客户端发现机制（客户端通过 {@code instanceof} 识别可交互设备），
 * 服务端不依赖此接口做安全判定（连接请求在服务端重新校验设备注册与距离）。
 * </p>
 */
public interface IIemsInteractable {

    /** 框架默认拉线最大距离（格）：逻辑设备未声明距离时使用。 */
    int DEFAULT_MAX_DISTANCE = 500;

    /** 本设备是否可作为手动连接的端点。 */
    boolean canBeConnectionEndpoint();

    /** 从本设备起线时的最大允许距离（格）。 */
    int getMaxConnectionDistance();
}
