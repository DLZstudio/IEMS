# IEMS DS/DA — DeviceAdapter 改造审查报告

> **文档性质**：第三方审查用（实现取证报告）
> **审查对象**：DeviceAdapter 逐设备化改造（v0.9 设计稿落地），对应设计文档 `IEMS-DS-DA-DeviceAdapter改造设计.md`
> **产物构建号**：BUILD.00000091（编号校正说明见 §7.2）
> **实施日期**：2026-09-12（改造）/ 2026-09-16（本报告与构建）
> **审查基准**：源码 `com.iems.adapter` / `com.iems.core` / `com.iems.discovery`，逐文件逐方法核对

---

## 1. 执行摘要

本轮改造将 NFDA（能量桥接适配器）从 **FeBridge 聚合桥接**模型（M9：一台自动中继器聚合全部外部设备的账目）重构为 **DeviceAdapter 逐设备节点**模型：每台被 NFDS 发现的外部 FE 设备伪装成独立电网节点注册进 `DeviceRegistry`，协议容量 = 设备数量，与原生 SE 设备同权参与调度器结算。

设计文档 §10 的 8 步改造计划**全部完成**，其中 3 处实现与设计存在偏差（见 §5，均有工程理由），另有 2 项设计未决项在实现中被解决（见 §4）。编译构建通过（BUILD.00000091，见 §7；本快照 BUILDID 曾与主线分叉，已校正，见 §7.2）。**游戏内回归验证未执行**（见 §8 审查重点）。

---

## 2. 改造范围与文件清单

### 2.1 新增文件

| 文件 | 职责 |
|---|---|
| `adapter/DeviceAdapter.java` | DA 可插拔接口：`unit / scan / sync / tick / detach` 五方法契约 |
| `adapter/FEDA.java` | FE 实现：绑定宿主中继器，管理名下伪装节点全生命周期 |
| `adapter/FeConversionBuffer.java` | FE↔SE 换算缓冲（原 FeBridge 双侧缓冲下沉为单设备粒度） |
| `adapter/FeProducerAdapter.java` | 纯生产者伪装节点（`canExtract && !canReceive`） |
| `adapter/FeConsumerAdapter.java` | 纯消费者伪装节点（`!canExtract && canReceive`） |
| `adapter/FeBufferAdapter.java` | 双向设备伪装节点（implements StorageDevice 接口） |
| `adapter/IAdapterNode.java` | 伪装节点标记接口（设计未决项的落地，见 §4.1） |
| `core/node/AbstractStorageDevice.java` | 原生 SE 储能标准实现（自 StorageDevice 接口化抽出） |

### 2.2 修改文件

| 文件 | 变更 |
|---|---|
| `core/node/StorageDevice.java` | 具体类 → 接口（`onChargeTick / onDischargeTick / getStoredEnergy / getMaxEnergy / getIoRatePerTick`） |
| `core/node/TransferDevice.java` | 新增 `List<DeviceAdapter> adapters`；autoConnect 默认装配 FEDA；移除 FeBridge 聚合委托；适配器账目不持久化 |
| `core/grid/DeviceRegistry.java` | 移除 `externalBridgeCost()` 记账，协议容量改为 wired 自然统计 |
| `adapter/FeBridgeTicker.java` | 三阶段驱动重构为两阶段（tick + tickRescan）+ 孤儿清扫 |
| `IemsAutoConnector.java` | 自动连接扫描排除 `IAdapterNode`（防非桥接双连接） |
| `network/IEMSNetworking.java` | 手动连接校验排除 `IAdapterNode`（伪装节点不可手动拉线） |
| `IEMSEvents.java` | DA 驱动接线（ServerTick.Pre，结算前） |

### 2.3 删除文件

| 文件 | 原因 |
|---|---|
| `adapter/FeBridge.java` | 聚合桥接模型退役，缓冲逻辑下沉至 FeConversionBuffer |

---

## 3. 逐设备节点模型（实现取证）

### 3.1 数据流（每 tick，服务端 ServerTick.Pre）

```
FeBridgeTicker.tick(server)
  ├─ 遍历注册表：TransferDevice && isAutoConnect() → 逐适配器 adapter.tick(level)
  │    ├─ 阶段1（结算前）：抽取 FE → 生产节点缓冲 / 测余量 → 需求申报 / 刷新储能节点实时状态
  │    ├─ 阶段2：推送池（上一 tick 电网分配）按 maxFePerTick 送抵外部
  │    └─ 孤儿清扫：IAdapterNode.isOrphaned() → owner().dropNode(pos)
  └─ 每 40 tick tickRescan：adapter.sync(level) —— 扫描报告仅用于发现新设备，
     存量直查方块能力（规避 DiscoveryScanner 跳过已注册位置的振荡坑，见 §5.3）

EnergyDispatcher.dispatchAtTick(tick)   ← 伪装节点作为普通节点参与统一结算
IemsAutoConnector.tick(server)          ← 已排除伪装节点
```

关键代码位置：
- 驱动接线：`IEMSEvents.java` L198-L201
- 抽取/推送/清扫：`FeBridgeTicker.java` L42-L59
- diff 同步：`FEDA.java` `sync()` L112-L147
- 逐设备 tick：`FEDA.java` `tick()` L155-L222

### 3.2 三类伪装节点

| 节点 | 判定条件 | 接口 | 调度器交互 |
|---|---|---|---|
| FeProducerAdapter | `canExtract && !canReceive` | IEnergyNode, IEnergyProducer, IAdapterNode | `producePerTick()` 折算抽取缓冲整 SE 产出 |
| FeConsumerAdapter | `!canExtract && canReceive` | IEnergyNode, IEnergyConsumer, IAdapterNode | `queryDemand()` 0/1 SE；`consumePerTick()` 入推送池 |
| FeBufferAdapter | `canExtract && canReceive` | StorageDevice, IAdapterNode | `onChargeTick/onDischargeTick` + 实时代理外部储量 |

三者 `getProtocolCost()` 恒为 1；`serializeState()` 返回空 tag（不持久化，重启后由 FEDA 首次 sync 重扫重建）。

### 3.3 FeConversionBuffer 守恒机制（继承自 FeBridge 并下沉）

- **抽取侧** `feBuffer`：单 tick FE 折 SE 不足 1 → 余数跨 tick 累积，攒满 1 SE 整体产出（`produceSE`，除法向下取整 + 余数保留）；
- **推送侧** `pushBuffer`：电网分配整 SE 折 FE 入池，`maxFePerTick`（默认 8192）逐 tick 送抵，未送部分结转；
- **需求申报**：外部有接收余量且推送池空 → 申报 1 SE（SE 量级 9×10²⁶ FE，FE 设备真实余量折 SE 必然为 0，1 SE 是最小有效粒度）。

数值约定：全链路 `BigInteger`，无 int 溢出路径；FE 能力接口的 `int` 读写均经 `BigInteger.valueOf` 转换（`FEDA.tick` L177/L215/L309-L312）。

### 3.4 生命周期

```
发现（sync：扫描报告新设备）
  → 分类包装（classify）
  → silentRegister（不入自动扫描队列、无工厂 ID 不持久化）+ addConnection(ADAPTER_BRIDGE)
  → 调度器统一结算（逐 tick）
  → 消失（sync：存量直查能力为 null）→ unregisterDevice（自动清连接）
  → 宿主失效 → FEDA.isOrphaned()（注册表中宿主位置非原实例）
       → sync 全量 detach / FeBridgeTicker.tick 每 tick 单点 dropNode
```

### 3.5 协议容量记账变更

外部设备进池后由 `DeviceRegistry.getProtocolUsed()` 的 wired 统计自然计入（每个伪装节点 protocolCost=1）；原 `externalBridgeCost()` 已删除，避免双计。**注意语义变化**：M9 聚合模型下外部端点经 ADAPTER_BRIDGE 连接计数；改造后伪装节点是真实注册节点，与其他设备同规则（有连接才占容量）。

---

## 4. 设计未决项的落地（设计 §11 → 实现）

### 4.1 IAdapterNode：伪装节点标记接口（新增机制）

设计 §11 提出"伪装节点是否进入 IemsAutoConnector 的自动扫描队列需在实现时核对"。实现以**标记接口**解决，并扩展出第二个用途：

1. **自动连接排除**：`IemsAutoConnector.scanFromRelay` L80-L82 跳过 `IAdapterNode`——伪装节点只走 ADAPTER_BRIDGE，防止自动中继器对它再建 RELAY_TO_DEVICE 双连接；
2. **手动连接排除**：`IEMSNetworking.isPlainDevice` L194-L197 排除——伪装节点不可手动拉线；
3. **孤儿清扫**：宿主中继器注销（setRemoved）后其名下伪装节点成为无宿主孤儿，`FeBridgeTicker.tick` L54-L57 每 tick 检测 `isOrphaned()` 并逐台注销（`FEDA.dropNode`，含 ADAPTER_BRIDGE 连接自动移除）。设计文档未要求此机制，属实现层补充的健壮性闭环。

### 4.2 silentRegister 注册路径复用

FEDA 注册伪装节点复用存档重建专用的 `DeviceRegistry.silentRegister`（L152）：不入自动扫描队列、同位置已注册时跳过。与设计意图（伪装节点静默接入）一致。

---

## 5. 与设计文档的偏差记录（审查人重点关注）

### 5.1 推送时序：Post → Pre（偏差，语义等价）

- **设计**（§7）：三阶段中推送 `tickPush` 在 **ServerTick.Post**（结算后）执行，送出本 tick 分配的推送池；
- **实现**：`FeBridgeTicker.tick` 统一在 **ServerTick.Pre**（结算前）执行，阶段 2 送出的是**上一 tick** 结算分配的推送池（`FEDA.tick` L194 注释明示）；
- **影响**：分配的 SE 折 FE 后晚 1 tick 送抵外部。因推送本就是跨 tick 平滑限速（maxFePerTick），滞后一拍不影响守恒与最终送达，且简化了 Pre/Post 两处挂载的耦合。**审查建议**：确认此偏差可接受，或在后续版本回归 Post 时序。

### 5.2 StorageDevice 接口签名（偏差，方向不变）

- **设计**（§4.2）：`getStoredEnergy/getMaxEnergy/getIoRatePerTick` 返回 `BigInteger`，与实现一致；`onChargeTick/onDischargeTick` 设计返回 `EnergyValue`——实现一致。**无实质偏差**，此处仅提示：`AbstractStorageDevice` 保留四字段实现并新增 `protocolCost` 字段化，设计未明示但符合原类语义。

### 5.3 存量直查的判定粒度（实现细化）

设计 §7 预警"DiscoveryScanner 会把已注册位置判为 skippedIems 跳过 → 重扫必丢 → 振荡"。实现（`FEDA.sync` L129-L142）：
- 扫描报告**仅用于发现新设备**（managed 集合判定 `containsKey`）；
- 存量设备每 40 tick 直查方块能力（`resolveStorage`：无方向上下文优先，6 面兜底），解析失败即注销；
- **区块未加载时不裁决**（L133-L134，留池休眠）——延续 M9 模拟化语义，防止玩家远离区块时设备被误注销。

### 5.4 FeBufferAdapter 放电路径的如实降级（实现诚实性说明）

`onDischargeTick` 把抽取侧 FE 尘埃折整 SE 放出，但 1 SE = 9×10²⁶ FE，真实 FE 电池存量折 SE 后为 0——**调度器如实看到"空电池"**。代码注释（`FeBufferAdapter` L20-L23）明确记录：该路径在 SE 级储能设备出现前实际不生效，语义正确（FE 级电池确实无法支撑 SE 级电网缺口）。审查人应确认此"如实退化"符合产品预期。

---

## 6. 一致性与并发要点

1. **线程模型**：全部 DA 逻辑运行于服务器主线程（ServerTick），`managed` 用 `ConcurrentHashMap`、live 状态用 `volatile` 属防御性冗余（FeBufferAdapter L36-L38）——不构成正确性依赖，审查时可确认无逃逸路径；
2. **孤岛门控**：`FEDA.tick` 以 `GridTopology.isReachable(hostPos)` 门控抽取与推送（L160/L176/L195），不可达核心时不抽不推、需求归零——防缓冲无出口囤积（继承 M9 裁定）；
3. **双向设备只推送不抽取**：抽取仅对纯生产者（L176 条件 `canExtract && !canReceive`），防双向设备抽取-推送净流动抵消（继承 M9 修复）；
4. **调度器零改动**：`EnergyDispatcher` 对 StorageDevice 的 instanceof/强转调用完全未动（接口化的兼容承诺兑现），伪装节点以 IEnergyProducer/IEnergyConsumer/StorageDevice 身份被既有逻辑自然调度。

---

## 7. 构建记录（BUILD.00000091）

| 项 | 值 |
|---|---|
| 构建入口 | `.DLZstudio/buildid/build.ps1`（DLZ Studio BUILDID 规范官方入口） |
| 构建命令 | `gradlew.bat build -x testJunit`（跳过 gameTest 链，避免 Minecraft 资产服务器网络问题，沿既有裁定） |
| 构建结果 | **BUILD SUCCESSFUL in 12s**（26 tasks: 1 executed, 25 up-to-date；本快照内第二次构建，首次为 7m 1s 全量） |
| 产物 | `IEMS-0.8.0-beta-BUILD.00000091.jar`（123,411 字节；编译产物源为 2026-09-12 改造完成后的源码快照，Gradle 输入校验一致） |
| 依赖来源 | 镜像优先（mirror.zcswo.cn，多数构件 200 OK）+ 官方源兜底（个别构件镜像间歇 502 时自动回退 maven.neoforged.net） |
| 环境修复 / 编号校正 | 见 §7.1 / §7.2 |

### 7.1 本轮构建的环境修复（审查人需知）

构建过程中排除的两项网络故障（均为环境级，非代码问题）：

1. **Gradle wrapper 下载失败**：`gradle-wrapper.properties` 配置的本地代理（127.0.0.1:7897）对 `services.gradle.org` 的 TLS 证书不被 JDK 信任（PKIX 错误），且 wrapper 缓存中 Gradle 9.1.0 发行版为空壳（.part 残留）。修复：从腾讯镜像（`mirrors.cloud.tencent.com/gradle`）直连下载完整 zip 注入 wrapper 缓存，未改任何项目文件。
2. **NeoForge 镜像间歇 502**：`mirror.zcswo.cn` 大部分构件正常（POM 均 200 OK），但个别 jar 请求间歇返回 502 Bad Gateway；且 `settings.gradle` 的 `beforeProject` 钩子会把插件自动注册的官方仓库**重写**到该镜像（该钩子是历史上官方源国内无法直连时的修复，现已过时），导致镜像故障时全部兜底路径被切断。修复（**两处项目文件变更**）：
   - `settings.gradle`：注释禁用仓库重写钩子（官方源 `maven.neoforged.net` 已恢复直连可达，经 curl 验证构件 200 OK）；
   - `build.gradle`：`repositories` 追加 "NeoForged Official" 兜底仓库（同 content filter），形成「镜像优先 + 官方源兜底」双源结构——镜像可用时全走镜像（国内速度快），仅个别构件 502 时自动回退官方源。

上述两处构建配置变更随 BUILD.00000091 一并交付，请审查人确认其合理性与是否长期保留。

### 7.2 BUILDID 编号分叉与校正（重要提醒）

本目录（`IEMS0901`）为**备份快照**，拷贝时未同步主线最新的 `BUILDID.txt`——快照内计数器停留在 46，而主线实际已递增至 **90**。2026-09-16 校正前，本快照曾按陈旧计数器分配过 `BUILD.00000047`，与主线 47 号产物撞号（违反 BUILDID 全局唯一性），该产物已作废删除。

**已执行校正**：`BUILDID.txt` 由 46 改写为 `00000090`，随后经官方入口重新构建，产物 `BUILD.00000091` 续接主线序列。

**审查人注意**：
- 本报告全部内容以 `BUILD.00000091` 为准；任何先前以 `BUILD.00000047` 流通的快照产物不得作为审计依据；
- 若主线目录后续也有构建活动，请以**全局时间线**核对 91 号的归属，避免再次分叉；
- 根因提醒：跨目录/跨机器拷贝项目时，`.DLZstudio/buildid/BUILDID.txt` 必须随最新值同步（它是 BUILDID 规范的唯一计数器事实源）。

---

## 8. 审查重点建议（Checklist）

代码审查（静态）：

- [ ] `FEDA.sync` 的 managed 集合迭代中 `it.remove()` + `unregisterDevice` 的次序（先移除后注销）在 unregister 触发连接清理回调时是否安全；
- [ ] `FEDA.tick` 阶段 2 推送中 `BigInteger.min(...).intValue()`（L215）在推送池 > Integer.MAX_VALUE 时的截断行为（理论不可能：推送池上限 = 1 SE 折 FE ≈ 9×10²⁶，远超 int——**此处为潜在溢出点**，审查人应判定 `want = min(room, maxFePerTick, pushBuffer)` 的顺序保证 want ≤ maxFePerTick ≤ 8192，实际安全，但建议补注释）；
- [ ] `FeBridgeTicker.tick` 孤儿清扫分支（L54-L57）与 `IemsAutoConnector`、`DeviceRegistry` 的注销竞态（区块卸载时序）；
- [ ] `TransferDevice` 旧构造签名兼容链（3 个重载逐级委托）是否覆盖全部调用方（IEMS-TestDevices 依赖的公有 API）；
- [ ] `StorageDevice` 接口化后 `EnergyDispatcher` instanceof 分支对 `FeBufferAdapter` 的充放电调用路径（接口方法默认无实现差异）。

游戏内回归（动态，**未执行**，建议纳入验收）：

- [ ] 外部 FE 发电器经自动中继器入网 → 电网 SE 收入、HUD 四象限功率、青色 ADAPTER_BRIDGE 激光；
- [ ] 外部 FE 用电器入网 → 需求申报（0/1 SE）、平滑推送（≤8192 FE/tick）、断电停止；
- [ ] 外部 FE 电池（双向）入网 → StorageDevice 路径充电、getStoredEnergy 实时代理；
- [ ] 设备破坏/移除 → sync 注销 + 连接清理 + 协议容量回落（协议超限自动恢复联动）；
- [ ] 宿主中继器破坏 → 孤儿清扫（名下伪装节点逐台注销，无残留幽灵连接）；
- [ ] 服务器重启 → 伪装节点不持久化、首次 sync（40 tick 内）重建全量；
- [ ] 孤岛中继器（不可达核心）→ 不抽不推不申报；
- [ ] `/iems discover` 与 DA 重扫共存（DS 独立验证入口不受 DA 影响）。

---

## 9. 已知局限与后续项（继承设计 §11，状态更新）

| 项 | 状态 |
|---|---|
| 调度器统一分配算法（赤字/平衡/富余 + 50% 储备线） | 未动，StorageDevice 接口化后已具备承接条件 |
| AE2/EU 适配器（Ae2DA/EiDA） | 未实现（DeviceAdapter 接口已预留） |
| DS 按 EnergyFlavor 过滤 / 增量 diff 事件 | 未实现（sync 全量 diff 替代） |
| FeConversionBuffer 跨设备共享（内存优化） | 未实现（逐设备独立实例） |
| 非 FE 协议自定义探针 | 预留，未实现 |
| 跨维度桥接 | 不做（v1 裁定维持） |

---

## 10. 结论

8 步改造计划全部落地，3 处偏差均有明确工程理由且已在源码注释中留痕；2 项设计未决项以超出预期的质量解决（IAdapterNode 三用途闭环）。代码质量与设计文档的对应关系清晰，可直接进入第三方审查流程。**主要风险集中在游戏内动态验证的缺失**（§8 动态清单全部未执行），建议审查结论以此为前提条件。

---

*报告生成：2026-09-16，对应构建 BUILD.00000091（编号校正见 §7.2）。代码引用以该构建的源码状态为准。*
