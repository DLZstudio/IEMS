# IEMS DS/DA — DeviceAdapter 改造设计

> 版本：v0.9 设计稿（M9 后置演进）
> 关联文档：`IEMS架构与NFDS-NFDA设计.md`、`IEMS开发白皮书-v0.8.0-beta.md`
> 目标：StorageDevice 接口化、逐设备适配节点、DeviceAdapter 可插拔接口抽取。

## 1. 背景与目标

IEMS 需接入大量非 SE 原生设备（FE、AE、GE、Fabric 移植能源等），但不能为每种能源体系
单独编写一套电网逻辑。DS/DA 系统将**设备发现**与**能量适配**解耦：

- **DS（Device Scanner）**：全局单例、被动扫描、只读无副作用——发现指定范围内外部设备，
  按能量体系分类返回清单。**不注册、不建连、不换算**。

- **DA（Device Adapter）**：每个传输设备内部持有、可插拔——将 DS 发现的设备伪装成
  `IEnergyNode` 注册进电网调度器，SE ↔ 目标单位双向转换。

本轮改造完成三件事：

1. **StorageDevice 接口化**——双向外部设备（电池）以统一储能契约接入调度器；
2. **逐设备节点**——每个外部设备一个注册节点，协议容量 = 设备数量，与原生设备同权；
3. **DeviceAdapter 可插拔接口**——把硬编码 `FeBridge` 抽象为接口，为 AE/EU 等预留。

## 2. 决策记录

| 决策项         | 结论                                       | 理由                                             |
| :---------- | :--------------------------------------- | :--------------------------------------------- |
| Buffer 接口形态 | **StorageDevice 接口化**（非双接口伪装）            | 调度器原生支持充放电语义；一个物理设备一个逻辑身份，避免协议容量/统计/渲染拆分       |
| 节点粒度        | **逐设备节点**（非聚合）                           | "协议容量=设备数量"是 IEMS 核心语义；实例自治是底层哲学；DS 已发现每设备独立属性 |
| DA 可插拔      | **DeviceAdapter 接口 + 具体实现（FEDA）**        | 为 AE/EU 预留扩展点                                  |
| 协议容量        | 每外部设备节点独立占 1，与原生 SE 设备同权                 | 补充确认                                           |
| 批量扫描        | 传输设备每 40 tick 请求 DS；DA 自行 diff 增删        | 存量设备直接探测，不依赖扫描报告                               |
| 缓冲复用        | 小功率设备可共享 `FeConversionBuffer`（可选优化，本轮不做） | 减少内存开销                                         |

## 3. 架构分层

```
EnergyDispatcher（调度器：统一分配策略）
        │ 只认节点接口（IEnergyProducer / IEnergyConsumer / StorageDevice）
        ▼
DeviceAdapter（设备适配器：可插拔，每传输设备持有）
        │ 伪装节点注册进 DeviceRegistry（逐设备）
        ▼
DS DiscoveryScanner（设备发现：只读扫描，返回清单）
```

调度器不关心节点是 SE 原生还是 DA 适配来的；DA 只是把 SE 指令翻译成外部协议
（FE/GE/AE）的执行器。

## 4. StorageDevice 接口化

### 4.1 现状

`StorageDevice` 是具体类（`implements IEnergyNode`），字段：deviceName / protocolCost /
maxEnergy / ioRatePerTick / storedEnergy，方法：onChargeTick / onDischargeTick /
getStoredEnergy / getMaxEnergy / getIoRatePerTick / serializeState / restoreState。

- 全工程**无** **`new StorageDevice(...)`**——纯预留类，接口化零迁移成本；

- 唯一消费方：`EnergyDispatcher` 的 `instanceof StorageDevice`（L302）+ 强转
  （L358/L376）+ onChargeTick/onDischargeTick 调用（L359/L377）；

- getters 源码零引用（仅白皮书定义）。

### 4.2 目标结构

```java
public interface StorageDevice extends IEnergyNode {
    EnergyValue onChargeTick(EnergyValue surplus);   // 调度器传入盈余，返回实际充入
    EnergyValue onDischargeTick(EnergyValue deficit); // 调度器传入缺口，返回实际放出
    BigInteger getStoredEnergy();
    BigInteger getMaxEnergy();
    BigInteger getIoRatePerTick();
}
```

现有字段实现抽成 `AbstractStorageDevice`（implements StorageDevice，保存四字段 +
storedEnergy + serialize/restore），作为原生储能标准实现保留。

### 4.3 兼容性

- `EnergyDispatcher` 的 instanceof / 强转 / 调用**全部不动**（接口兼容）；

- 调度器 chargeStorages/dischargeStorages 逻辑不变。

## 5. DeviceAdapter 可插拔接口

### 5.1 接口定义

```java
public interface DeviceAdapter {
    /** 适配的能源体系单位（FE/AE/GE…）。 */
    EnergyUnit unit();

    /** 周期扫描：请求 DS 扫描自身覆盖范围，返回本适配器关注类型的设备清单。 */
    List<DiscoveredDevice> scan(ServerLevel level, BlockPos center, int radius);

    /**
     * 同步适配节点：对比扫描清单与已注册节点，新增注册、消失注销。
     * 存量设备直接探测方块存在性，不依赖扫描报告（规避 DiscoveryScanner 的
     * skippedIems 跳过已注册位置问题）。
     */
    void sync(ServerLevel level);

    /** 每 tick 驱动：对名下节点的外部能力做实际读写（抽出/送抵/测余量）。 */
    void tick(ServerLevel level);

    /** 注销全部名下节点（宿主被移除/失效时）。 */
    void detach();
}
```

### 5.2 FEDA（FE 适配器，本轮实现）

`FEDA implements DeviceAdapter` 是当前 `FeBridge` + `FeBridgeTicker` 逐设备化的产物：

- 内部持有 `Map<GlobalPos, IEnergyNode> managed`（伪装节点）；

- `scan` 委托 `IEMSAPI.scanForDevices`（= DS），按设备能力分类：

  - `canExtract && !canReceive` → `FeProducerAdapter`（只出）；

  - `!canExtract && canReceive` → `FeConsumerAdapter`（只入）；

  - `canExtract && canReceive` → `FeBufferAdapter`（双向，implements StorageDevice）；

- `sync` 做 diff：新设备 → `IEMSAPI.registerDevice(pos, node)` + `addConnection(relay, pos, ADAPTER_BRIDGE)`；
  消失设备 → `IEMSAPI.unregisterDevice(pos)`（自动清连接）；存量设备 → 直查方块能力；

- `tick` 每 tick：抽取 FE 喂入生产节点缓冲、测 free 喂入消费节点需求、
  把消费节点推送缓冲按 maxFePerTick 送抵外部。

### 5.3 TransferDevice 集成

```java
// 构造签名演进：autoConnect == true 时默认装配 FE 适配器
public class TransferDevice implements IEnergyNode, IEnergyProducer, IEnergyConsumer {
    private final List<DeviceAdapter> adapters;   // 可插拔，如 [FEDA(), Ae2DA()]
    public TransferDevice(..., boolean autoConnect, ..., List<DeviceAdapter> extraAdapters) {
        this.adapters = autoConnect ? List.of(new FEDA(...), ...) : List.of();
    }
}
```

- `FeBridge` 字段退役；producePerTick/queryDemand/consumePerTick 的聚合委托
  随逐设备节点落地而移除（外部设备各自成为注册节点，由调度器直接结算）；

- 桥接账目（feBuffer/pushBuffer）下沉到各伪装节点内的 `FeConversionBuffer`；

- 持久化：伪装节点**不持久化**（无工厂 ID），重启后由 FEDA 首次重扫重建。

## 6. 逐设备伪装节点

### 6.1 FeConversionBuffer（FE↔SE 换算缓冲）

把原 `FeBridge` 的缓冲逻辑下沉为单设备粒度（可共享实例，可选优化）：

- **抽取侧 feBuffer**：单 tick 抽取 FE 折算 SE 不足 1 时余数跨 tick 累积，攒满 1 SE 产出；

- **推送侧 pushBuffer**：电网分配的整 SE 折算 FE 入池，以 maxFePerTick 逐 tick 送抵，
  余量结转（守恒）；

- **需求申报**：pushBuffer 空且外部有接收余量时申报 1 SE（最小粒度）。

### 6.2 三个适配节点

| 节点                  | 实现                             | 关键行为                                                                                           |
| :------------------ | :----------------------------- | :--------------------------------------------------------------------------------------------- |
| `FeProducerAdapter` | `IEnergyNode, IEnergyProducer` | `producePerTick()` 折算抽取缓冲；`getProtocolCost()=1`                                                |
| `FeConsumerAdapter` | `IEnergyNode, IEnergyConsumer` | `queryDemand()`=0/1 SE；`consumePerTick()` SE→FE 入推送缓冲                                          |
| `FeBufferAdapter`   | `StorageDevice`（接口）            | `onChargeTick/onDischargeTick` 做 SE→FE / FE→SE；`getStoredEnergy()/getMaxEnergy()` **实时代理外部能力** |

要点：

1. 全工程 `BigInteger`/`EnergyValue` 约定，速率字段从能力读 `int` 后转 `BigInteger`；
2. `getStoredEnergy()` 必须**实时代理外部能力**（外部设备自身在耗/充），不本地缓存；
3. `onChargeTick/onDischargeTick` 落实回外部能力；
4. 伪装节点保持"纯数值 + 外部能力由 DA tick 驱动"（不持 Level 引用，延续现有架构）。

## 7. FeBridgeTicker 重构（→ FEDA 驱动）

现有三阶段驱动（tickPull / tickRescan / tickPush）演化为 FEDA 的 tick/sync：

| 阶段 | 现行为                            | 改造后                                               |
| :- | :----------------------------- | :------------------------------------------------ |
| 抽取 | 对 relay 全部 targets 抽 FE、测 free | 逐设备：抽 FE 喂生产节点缓冲、测 free 喂需求（`FEDA.tick`）          |
| 重扫 | 每 40 tick 全量重扫 + 连接同步          | `FEDA.sync`：diff 增删注册节点 + ADAPTER\_BRIDGE 连接；存量直查 |
| 推送 | 对 relay 的 pushBuffer 逐目标送出     | 逐设备：把各消费节点推送缓冲按 maxFePerTick 送抵（`FEDA.tick`）      |

**关键坑（必须处理）**：`DiscoveryScanner.scan` 会把**已注册位置**判为 `skippedIems`
跳过。逐设备节点落地后，外部设备一旦注册，下一次重扫就"看不到"它 → 误判消失 →
注销 → 振荡。**解法：DA 自行维护 managed 集合；扫描报告只用于找新设备，存量靠直查。**

**孤岛门控保留**：中继器不可达核心时不抽不推（防缓冲无出口囤积）。

## 8. 协议容量记账变更

- 外部设备进池后由 `DeviceRegistry.getProtocolUsed()` 正常循环统计（wired 位置）；

- **移除** **`externalBridgeCost()`**（避免双计）；

- 每个伪装节点 `getProtocolCost() = 1`（与原生 SE 设备同权）。

## 9. 生命周期

1. **发现**：FEDA 每 40 tick 调 DS 扫描自身覆盖范围；
2. **适配**：按能力分类包装成 FeProducerAdapter / FeConsumerAdapter / FeBufferAdapter；
3. **注册**：`IEMSAPI.registerDevice(pos, node)`（无工厂 ID → 不持久化）+ 建 ADAPTER\_BRIDGE；
4. **结算**：调度器按统一规则分配 SE，FEDA 在 tick 中执行外部能力读写；
5. **注销**：设备消失（方块移除/能力丢失）→ `IEMSAPI.unregisterDevice(pos)`（自动清连接）；
   宿主失效 → `FEDA.detach()` 全量注销。

## 10. 改造步骤（本轮）

| #  | 内容                                                             | 涉及文件                              |
| :- | :------------------------------------------------------------- | :-------------------------------- |
| 1  | StorageDevice 接口化 + 抽 AbstractStorageDevice                    | `core/node/StorageDevice.java`（新） |
| 2  | DeviceAdapter 接口 + FeConversionBuffer                          | `adapter/`（新）                     |
| 3  | 三个伪装节点：FeProducerAdapter / FeConsumerAdapter / FeBufferAdapter | `adapter/`（新）                     |
| 4  | FEDA 实现（含 diff sync / 逐设备 tick）                                | `adapter/FEDA.java`（新）            |
| 5  | FeBridgeTicker 改造（驱动 FEDA 或退役）                                 | `adapter/FeBridgeTicker.java`     |
| 6  | TransferDevice 改持 List\<DeviceAdapter>，移除 FeBridge 聚合委托        | `core/node/TransferDevice.java`   |
| 7  | DeviceRegistry 移除 externalBridgeCost                           | `core/grid/DeviceRegistry.java`   |
| 8  | 构建验证                                                           | —                                 |

## 11. 边界与未决项

- **调度器统一分配算法**（赤字/平衡/富余 + 50% 储备线）为独立演进项，不在本轮
  （当前调度器仍是供给/需求预算模型，StorageDevice 接口化后已具备承接条件）；

- AE2/EU 适配器（Ae2DA/EiDA）仅定义接口，不实现；

- DS 按 EnergyFlavor/协议类型过滤、增量 diff 复用等列为后续优化；

- 伪装节点是否进入 IemsAutoConnector 的自动扫描队列需在实现时核对
  （避免自动中继对伪装节点建立非桥接连接）。

