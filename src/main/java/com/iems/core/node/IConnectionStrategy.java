package com.iems.core.node;

/**
 * 连接策略：决定 TransferDevice 如何评估/建立连接。
 * <p>
 * M5 提供具体实现（如 TEM 波自动连接、白名单/黑名单过滤）。
 * </p>
 */
public interface IConnectionStrategy {

    /** 策略标识名。 */
    String getName();
}
