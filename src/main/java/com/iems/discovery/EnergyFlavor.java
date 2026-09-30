package com.iems.discovery;

import com.iems.core.energy.EnergyUnit;

/**
 * 模组能量体系的身份映射条目（NFDS 身份轴）。
 * <p>
 * 回答「它是谁家的设备」：namespace → 单位 + 显示名。
 * 外部模组经 {@code IEMSAPI.registerEnergyFlavor} 声明自家体系
 * （如 {@code botania → Botania / FE}）；未注册的 namespace 默认 FE、
 * 以 namespace 本身作为显示名。
 * </p>
 *
 * @param namespace   模组命名空间（如 "appliedenergistics2"）
 * @param unit        该体系的能量单位（决定 NFDA 桥接换算基准）
 * @param displayName 身份显示名（如 "Applied Energistics"）
 */
public record EnergyFlavor(String namespace, EnergyUnit unit, String displayName) {
}
