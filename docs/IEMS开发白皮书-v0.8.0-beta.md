# IEMS 开发白皮书 v0.8.0-beta

面向 Minecraft NeoForge 的综合能源管理系统

**发布机构：** 等离子工作室 (DLZstudio)
**适用版本：** IEMS 0.8.0-beta（对应 Minecraft 1.21.1, NeoForge 21.1.x, Java 21+）
**文档性质：** 内部架构规范 / 开发指南 / 最终裁定标准
**归档日期：** 2026年8月16日（M8 修订：2026年8月22日，同步至 BUILD.00000013 实际实现）

---

## 一、项目愿景与定位

### 1.1 项目背景

随着 Minecraft 科技模组生态的不断膨胀，各模组间的能源体系（FE、AE、GE 等）割裂严重，玩家被迫在不同电网间铺设大量线缆，管理复杂度随设备规模呈指数级上升。IEMS（Integrated Energy Management System）旨在从根本上解决这一问题。

### 1.2 核心定义

IEMS 是一个能源管理框架模组。它不直接提供发电或储能的核心方块，而是通过一套统一的电网抽象层，将不同模组的设备纳入同一个高精度能量网络中管理。

IEMS 的核心价值：

- 统一抽象：将 FE、AE、GE 等多种能量单位统一转换为 SE（Standard Energy）
- 可视化拓扑：通过激光连接直观展示电网结构
- 协议容量机制：基于设备数量的容量上限，倒逼拓扑优化
- 高精度运算：BigInteger 能量运算，支持超大数值精确表示

### 1.3 设计哲学：实例自治

IEMS 的核心设计原则借鉴了分布式系统中"实例自治"的思想：

每个设备实例是完全独立的。它不持有对其他实例的引用，不依赖其他实例的状态，只通过 IEMS 调度器与电网交互。

这意味着：

- 每个设备实例可以独立 new、独立销毁、独立修改参数
- 实例间无共享可变状态，天然支持多核并行处理
- "量子力学中继器"类玩法成为可能——每次区块加载时，设备可以表现为完全不同的属性

---

## 二、架构总览

IEMS 采用 **逻辑-表现完全分离** 的架构。逻辑层以纯 POJO 形式存在，表现层由外部模组提供。

```
外部模组 (设备提供方)
│
└── Block / BlockEntity (注册到 Minecraft)
    │
    ├── 持有 IEMS 逻辑实例 (CoreDevice / TransferDevice / StorageDevice / DimensionGate)
    ├── 构造器 / loadAdditional() 中 new 实例 (纯 POJO，无需 Level)
    ├── onLoad() 中调用 IEMSAPI.registerDevice() 注册 (仅服务端，见 §4.2)
    ├── saveAdditional() 中持久化参数 (或直接调 serializeState())
    └── setRemoved() / onChunkUnloaded() 中注销 (连接数据保留在 iems_grid.dat)
                    │
                    ▼
┌─────────────────────────────────────────────────────────────┐
│                    IEMS 逻辑层                             │
│                                                           │
│  ┌───────────────────────────────────────────────────────┐ │
│  │           CoreDevice (唯一实例，常加载)              │ │
│  │  • 电网身份锚点                                     │ │
│  │  • 协议容量总量管理                                 │ │
│  │  • 能量池总入口/出口                                │ │
│  └────────────────────┬─────────────────────────────────┘ │
│                       │ 持有/调度                        │
│  ┌────────────────────┴─────────────────────────────────┐ │
│  │              设备池 (Registry)                       │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │ │
│  │  │ Transfer    │  │ Storage     │  │ 自定义设备  │ │ │
│  │  │ (N个实例)   │  │ (M个实例)   │  │ (任意)      │ │ │
│  │  └─────────────┘  └─────────────┘  └─────────────┘ │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                           │
│  ┌───────────────────────────────────────────────────────┐ │
│  │               底层服务层 (内部单例)                   │ │
│  │  • GridTopology (BFS/拓扑快照)                       │ │
│  │  • EnergyDispatcher (每Tick能量分配 + 分帧调度)      │ │
│  │  • ProtocolCalculator (容量计算/超限检测)            │ │
│  │  • NFDS/NFDA (FE/GE/AE ↔ SE 设备发现与适配)          │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、核心组件详述

### 3.1 三大基类

外部模组开发者通过 new 直接实例化即可完成设备接入。

#### 3.1.1 CoreDevice（核心门面）

职责：电网的身份锚点，唯一实例。注册时自动将所在区块设为常加载。

构造参数：

| 参数 | 类型 | 说明 |
|------|------|------|
| coreName | String | 核心名称 |
| protocolLimit | BigInteger | 协议容量上限 |
| energyCapacity | BigInteger | 核心能量总容量 (SE) |
| powerGenRate | BigInteger | 每 Tick 自发电量 (SE/tick) |

核心方法：

| 方法 | 说明 |
|------|------|
| String getFormattedStatus() | 返回格式化电网状态 |
| void setGridActive(boolean) | 开启/关闭电网 |
| void forceRescan() | 强制触发完整 BFS 重扫 |
| void setProtocolLimit(BigInteger) | 运行时修改协议容量 |
| BigInteger getCurrentEnergy() | 获取电网当前总能量 |

常加载机制：注册时自动调用 Level.setChunkForced()，核心区块永不卸载；注销时解除。

#### 3.1.2 TransferDevice（传输节点）

职责：电网中的连接节点，负责激光连接与路由。每个实例持有独立的连接参数。

构造参数：

| 参数 | 类型 | 说明 |
|------|------|------|
| deviceName | String | 设备名称 |
| protocolCost | BigInteger | 协议容量占用（支持动态/负值释放容量） |
| maxConnectionDistance | int | 最大连接距离 (格) |
| autoConnect | boolean | 是否启用自动连接 |
| whitelist | List\<String\> | 白名单 (方块ID) |
| blacklist | List\<String\> | 黑名单 (方块ID) |
| anchorOffset | Vec3 | (可选) 激光连接口偏移，默认方块中心 (0.5, 0.5, 0.5) |

> 多方块结构 / 异形模型设备应使用带 `anchorOffset` 的完整构造器（或覆写 `getAnchorOffset()`），
> 指定实际连接口位置；锚点在建连时固化进 Connection 并同步到客户端渲染。

运行时方法：

| 方法 | 说明 |
|------|------|
| void updateStrategy(IConnectionStrategy) | 运行时替换连接策略 |
| void setPosition(GlobalPos) | 绑定设备位置（拓扑计算用） |
| boolean isConnectedToCore() | 查询是否已接入电网 |
| Vec3 getAnchorOffset() | 连接口世界偏移（相对方块原点） |
| CompoundTag serializeState() | 序列化状态供持久化 |
| void restoreState(CompoundTag) | 从NBT恢复状态 |

#### 3.1.3 StorageDevice（储能节点）

职责：电网中的能量缓存节点，负责充放电。

构造参数：

| 参数 | 类型 | 说明 |
|------|------|------|
| deviceName | String | 设备名称 |
| protocolCost | BigInteger | 协议容量占用 |
| maxEnergy | BigInteger | 储能上限 (SE) |
| ioRatePerTick | BigInteger | 每 Tick 最大充放电速率 |

核心方法：

| 方法 | 说明 |
|------|------|
| EnergyValue onChargeTick(EnergyValue) | 调度器调用，传入盈余，返回实际充入量 |
| EnergyValue onDischargeTick(EnergyValue) | 调度器调用，传入缺口，返回实际放出量 |
| BigInteger getStoredEnergy() | 当前储能 |

> 调度顺序（M5+ 修复批次）：电网盈余时**先充电储能**再满足消费（防核心容量溢出丢能量）；
> 短缺时**先放电储能**补缺，再按消费优先级分配。储能放出未被消费的部分回流核心池。

#### 3.1.4 DimensionGate（跨维度桥接门）

职责：成对配对（相同 PairID）后跨维度桥接电网，连接端点用 GlobalPos，消耗较大协议容量。

构造参数（简化构造器）：

| 参数 | 类型 | 说明 |
|------|------|------|
| deviceName | String | 设备名称 |
| protocolCost | BigInteger | 协议容量占用（跨维度成本更高） |
| pairId | String | 配对 ID（16 位十六进制大写，构造时校验格式） |

完整构造器额外参数：`autoBroadcast`（自动广播能量）、`maxPeers`（最大对端数，0=无限制）、
`structureSize`（多方块尺寸）、`frameBlocks`（框架方块谓词）、`coreBlock`（结构核心）、
`dimensionScale` + `useCustomScale`（距离换算比例：下界 8、末地 1）。

核心方法：

| 方法 | 说明 |
|------|------|
| boolean addPeer(GlobalPos) | 添加配对端（超 maxPeers 返回 false，不抛异常） |
| boolean canAcceptPeer() | 是否还能接受新对端 |
| void removePeer(GlobalPos) | 移除配对端 |
| boolean isPaired() | 是否已有配对 |
| double getDimensionScale() | 维度距离换算比例 |

---

## 四、设备生命周期与身份模型

### 4.1 核心原则：位置即身份

IEMS 不追踪实例的对象身份，只追踪位置上的设备存在性。

```
连接数据存的是坐标 → 连接持久化与实例解耦
实例可以随时重建 → 区块加载时 new 新实例即可
电网状态通过坐标查询 → 不依赖实例引用
```

### 4.2 完整生命周期

```
【初次放置】
玩家放置方块 → BlockEntity 构造器被调用
    ↓
new TransferDevice(...)  ← 参数由开发者决定（纯 POJO，此时尚无 Level）
    ↓
onLoad() 被调用（服务端）：
IEMSAPI.registerDevice(GlobalPos.of(level.dimension(), worldPosition), instance)
    ↓
IEMS 将该位置加入设备池（GlobalPos 为键）
    ↓
触发 BFS 重扫，判断是否可达核心
    ↓
saveAdditional() 将参数写入 NBT（可直接用 logic.serializeState()）

【区块卸载】
onChunkUnloaded() → unregisterDevice(GlobalPos)
    ↓
设备从设备池移除 (但连接数据保留在 iems_grid.dat)
    ↓
实例被 GC 回收

【区块加载】
BlockEntity 重建：构造器 → loadAdditional() (从 NBT 读取参数)
    ↓
开发者选择：
  ├─ 读取 NBT → 用保存的参数 new 实例 (常规行为)
  └─ 不读 NBT → 用新参数 new 实例 (量子力学玩法)
    ↓
onLoad() 再次注册（区块加载后设备自动回归电网）
    ↓
查询当前电网快照 → 获取正确的供电状态
    ↓
如果 gridShutdown == true → 不供电

【设备破坏】
setRemoved() → unregisterDevice(GlobalPos)
    ↓
从设备池移除
    ↓
触发 BFS 重扫，更新电网快照
```

#### 推荐的实例创建方式（重要）

设备实例的创建与注册必须遵守**「构造期只 new，加载期才注册」**：

| 阶段 | 钩子 | 该做什么 | 原因 |
|------|------|----------|------|
| 实例化 | 构造器 / `loadAdditional()` | 只 `new` 逻辑实例（纯 POJO） | 设备类不依赖 Level，构造器中 BE 的 `level` 字段为 null |
| 注册 | `onLoad()` | `registerDevice(GlobalPos.of(level.dimension(), worldPosition), logic)` | GlobalPos 需要维度键，必须等 Level 就绪 |
| 注销 | `setRemoved()`（破坏）+ `onChunkUnloaded()`（卸载） | `unregisterDevice(GlobalPos)` | 破坏与卸载都不会调用对方钩子，两处都要写 |

两条硬性规则：

1. **注册/注销必须加 `!level.isClientSide` 守卫**——BlockEntity 在客户端与服务端各有一份，
   客户端实例混入设备池会污染电网状态。
2. **禁止在构造器中注册**——构造器中拿不到 Level，无法构造 GlobalPos；
   且构造器在两侧都会执行，注册必然出错。

### 4.3 趣味玩法："量子力学中继器"

利用"实例可重建"特性，开发者可以实现区块加载时随机生成属性的设备：

```java
@Override
protected void loadAdditional(CompoundTag tag) {
    super.loadAdditional(tag);

    // 不读取 NBT，每次加载随机生成
    int distance = 100 + random.nextInt(400);
    BigInteger cost = BigInteger.valueOf(1 + random.nextInt(3));

    this.logic = new TransferDevice("薛定谔的中继器", cost, distance, true, null, null);
}

@Override
public void onLoad() {
    super.onLoad();
    if (level != null && !level.isClientSide) {
        // 注册统一在 onLoad：区块卸载后重载，随机属性自动生效
        IEMSAPI.registerDevice(GlobalPos.of(level.dimension(), worldPosition), this.logic);
    }
}
```

玩家远离后再回来，中继器属性已变。这完全合法，因为连接数据存的是坐标，不受实例重建影响。

---

## 五、电网拓扑系统

### 5.1 核心常加载

核心区块强制常加载，确保电网状态查询永远可用：

- 核心注册时：Level.setChunkForced(true)
- 核心注销时：Level.setChunkForced(false)
- 服务器重启时：核心由其 BlockEntity NBT 在区块加载后重注册（onLoad），自动恢复常加载；
  显式连接数据从 `data/iems_grid.dat`（GridSavedData）恢复注入拓扑

### 5.2 BFS 全局遍历

每次电网状态变更时，执行完整的全局 BFS 遍历，生成电网拓扑快照。

触发条件：

- 核心注册 / 注销
- 设备注册 / 注销
- 连接增删
- 协议容量变更

BFS 算法（两阶段）：

```
第一阶段：从核心出发，遍历所有可达设备
    │
    ├── 核心位置常加载，保证起始点可用
    ├── 遍历连接时若遇到未加载区块 → 临时加载以查询设备
    ├── 记录所有可达设备到 mainNetwork
    └── 记录边界连接 (连接存在但远端未加载)

第二阶段：识别孤岛网络
    │
    ├── 获取所有已注册设备
    ├── 排除 mainNetwork 中的设备
    └── 在剩余设备中 BFS 分组 → 得到 orphanNetworks
```

### 5.3 电网快照数据结构

```java
public class GridSnapshot {
    private final Set<GlobalPos> mainNetwork;        // 核心可达设备（含核心自身）
    private final List<Set<GlobalPos>> orphanNetworks; // 孤岛集群
    private final Set<Connection> pendingConnections; // 边界连接（远端未注册/未加载）
    private final boolean gridShutdown;              // 关停状态
    private final GlobalPos corePos;                 // 核心位置 (无核心时为 null)
}
```

### 5.4 TEM 波自动连接 (原"自动连接"机制)

广播塔向水平面下方半球形空间辐射 TEM 波，自动发现并连接设备：

| 规则 | 说明 |
|------|------|
| 辐射方向 | 水平面以下半球形区域 |
| 遮挡判定 | 连续不透明固体 > 3米 → 完全阻隔 |
| 水平远处 | 效率极低，视为不可连接 |
| 正上方 | 效率极低，视为不可连接 |

NFDS 负责发现设备，NFDA 负责将发现结果纳入自动连接范围，推送能量时自动 SE→FE 转换。

---

## 六、能量系统

### 6.1 能量单位体系

| 单位 | 换算 | 说明 |
|------|------|------|
| FE | 1 | Forge Energy，基准 |
| AE | 1 FE | Applied Energistics |
| GE | 9×10¹⁸ FE | General Energy |
| SE | 9×10²⁶ FE | Standard Energy (内部标准) |

### 6.2 供能周期：每 Tick

- 分帧调度：帧大小动态计算（设备总数 1/10，下限 8），每 Tick 只处理一帧设备，避免大电网阻塞服务器线程
- 同 Tick 幂等守卫：调度器按服务器 tick 计数去重，重复调用不会重复结算
- 功率累计：核心自发电每 Tick 累加；盈余先充储能再满足消费，短缺先放储能再按优先级分配

### 6.3 NFDS/NFDA 适配器

定位：独立工具类，自动扫描外部 FE 设备并桥接到 IEMS 电网。

工作流程：

1. NFDS 扫描广播塔周围设备（方向性 TEM 波 + 遮挡判定）
2. NFDA 将发现的设备纳入自动连接范围
3. 能量推送时，NFDA 自动将 SE 转换为 FE 输出

与距离衰减的关系：无距离衰减。物理距离挑战由激光拓扑解决，NFDA 只做单位转换。

---

## 七、协议容量系统

### 7.1 核心机制

| 设备类型 | 协议占用 |
|----------|----------|
| CoreDevice | 0 |
| TransferDevice | 1 (可自定义) |
| StorageDevice | 1 (可自定义) |
| 自定义设备 | 自行声明 |

### 7.2 超限处理

- protocolUsed > protocolTotal → 电网关停
- 关停时所有设备断电，激光变红，调度器停止能量分配
- **协议关停自动恢复**：用量回到限值内（拆除/降级设备）时自动重新供电
- **手动关停不被自动恢复**：`/iems shutdown` 或 `IEMSAPI.setGridActive(false)` 关停后，
  设备增删触发的容量检查不会自动复电，须显式 `/iems restart`（M8 语义精化）
- `IEMSAPI.isProtocolShutdown()` 可查询当前关停是否由协议超限引起（供面板/指令显示原因）

---

## 八、开发者 API

### 8.1 IEMSAPI 门面（静态类）

`IEMSAPI` 是 `final` 类 + 静态方法（非接口），外部模组只依赖此类即可完成全部接入：

```java
public final class IEMSAPI {
    // 设备注册（位置即身份，GlobalPos 为键）
    static void registerDevice(GlobalPos pos, IEnergyNode node);
    static void unregisterDevice(GlobalPos pos);

    // 核心管理（全局唯一，跨维度仅一个）
    static void registerCore(GlobalPos pos, CoreDevice core);
    static void unregisterCore();

    // 能量查询
    static BigInteger getCurrentEnergy();   // 电网当前总能量 (SE)
    static BigInteger getTotalCapacity();   // 电网总容量 (SE)

    // 协议查询
    static BigInteger getProtocolUsed();
    static BigInteger getProtocolTotal();

    // 电网开关（M8）
    static void setGridActive(boolean active);
    static boolean isProtocolShutdown();

    // 连接管理（锚点自动取自设备 getAnchorOffset()，未注册回退方块中心）
    static void addConnection(GlobalPos a, GlobalPos b, ConnectionType type);
    static void addConnection(GlobalPos a, GlobalPos b, ConnectionType type,
                              Vec3 anchorA, Vec3 anchorB); // 显式指定锚点
    static void removeConnection(GlobalPos a, GlobalPos b, ConnectionType type);

    // 拓扑
    static GridSnapshot getSnapshot();          // 只读快照
    static boolean isDeviceConnected(GlobalPos); // 是否在核心可达网络
    static void forceRescan();                   // 强制完整 BFS 重扫

    // 功率源注册 (外部发电机/负载接入，速率 SE/tick)
    static void registerPowerInput(String id, BigInteger rate);
    static void unregisterPowerInput(String id);
    static void registerPowerOutput(String id, BigInteger rate);
    static void unregisterPowerOutput(String id);
}
```

`ConnectionType`：`RELAY_TO_RELAY`（同维度中继）/ `DIMENSION_BRIDGE`（跨维度桥接）。

### 8.2 自定义设备接入

实现以下接口即可绕过内置基类接入电网。所有节点接口继承自 `IEnergyNode` 基契约：
`getDeviceName()` / `getProtocolCost()`（BigInteger，可为动态或负值）/ `serializeState()` /
`restoreState(CompoundTag)`，可选覆写 `getAnchorOffset()`（连接口偏移）与
`refreshProtocolCost()`（每次 BFS 前刷新动态成本）。

```java
public interface IEnergyProducer extends IEnergyNode {
    BigInteger producePerTick();          // 本 Tick 产出 SE（0 = 无产出）
    default int getPriority() { return 0; } // 数值越小越优先保活
}

public interface IEnergyConsumer extends IEnergyNode {
    BigInteger queryDemand();             // 纯查询本 Tick 需求（不得有副作用！）
    BigInteger consumePerTick(BigInteger budget); // 执行消费，返回实际消耗 ≤ budget
    default int getPriority() { return 0; } // 数值越小越优先供电
}
```

> **两阶段消费契约（V-03 修复后）**：调度器先调 `queryDemand()` 汇总全网需求，
> 再调 `consumePerTick(budget)` 执行扣减。若在 `queryDemand()` 中产生副作用
> （如直接扣内部缓存），会在一个 Tick 内被扣两次。旧版单方法接口已废弃。

### 8.3 外部模组的最小实现（推荐模板）

```java
public class MyRelayBlockEntity extends BlockEntity {
    private TransferDevice logic;

    public MyRelayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        // ① 构造器只 new 纯 POJO（此时 level 为 null，不能注册）
        this.logic = new TransferDevice("我的中继器", BigInteger.ONE, 500, true, null, null);
    }

    @Override
    protected void loadAdditional(CompoundTag tag) {
        super.loadAdditional(tag);
        // ② 从 NBT 重建实例（量子玩法可在此忽略 tag 用新参数）
        this.logic = new TransferDevice(
                tag.getString("deviceName"),
                new BigInteger(tag.getString("protocolCost")),
                tag.getInt("maxDistance"), true, null, null);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // ③ 注册统一在 onLoad，且仅服务端
        if (level != null && !level.isClientSide) {
            IEMSAPI.registerDevice(GlobalPos.of(level.dimension(), worldPosition), logic);
        }
    }

    @Override
    public void setRemoved() {
        // ④ 方块被破坏时注销
        if (level != null && !level.isClientSide) {
            IEMSAPI.unregisterDevice(GlobalPos.of(level.dimension(), worldPosition));
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        // ⑤ 区块卸载时注销（连接数据保留在 iems_grid.dat，重载自动回归）
        if (level != null && !level.isClientSide) {
            IEMSAPI.unregisterDevice(GlobalPos.of(level.dimension(), worldPosition));
        }
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("deviceName", logic.getDeviceName());
        tag.putString("protocolCost", logic.getProtocolCost().toString());
        tag.putInt("maxDistance", logic.getMaxConnectionDistance());
    }
}
```

### 8.4 指令系统（M8）

| 指令 | 权限 | 行为 |
|------|------|------|
| `/iems status` | 所有人 | 核心/运行状态/关停原因/能量/容量/协议/主网设备数/孤岛数/连接数/核心坐标 |
| `/iems protocol` | 所有人 | 协议容量明细（used / limit / 是否超限） |
| `/iems scan` | 所有人 | 强制 BFS 重扫 + 结果摘要 |
| `/iems shutdown` | level 2 | 手动关停电网（不被协议检查自动恢复） |
| `/iems restart` | level 2 | 手动重启电网 |

### 8.5 HUD（M8）

客户端顶部能量条（明日方舟终末地风格圆角条），数据来自 `GridSyncPayload`
（40 tick 同步一次）→ `ClientGridCache`，三态显示：无核心（灰）/ 关停（红）/ 正常
（能量 SE + 协议 used/limit）。

外部模组建立连接时可启用拉线模式，实时显示与目标点距离、超距自动断开：

```java
// 开始拉线（客户端调用）
EnergyOverlayRenderer.setConnectingMode(true, GlobalPos.of(level.dimension(), anchor), 500);
// 结束拉线
EnergyOverlayRenderer.setConnectingMode(false, null, 0);
```

---

## 九、版本规划

| 里程碑 | 版本 | 核心目标 | 状态 |
|--------|------|----------|------|
| M1 | 0.8.0-beta-M1 | 三大基类 + IEMSAPI 骨架 | ✅ |
| M2 | 0.8.0-beta-M2 | GridTopology + BFS 全局遍历 | ✅ |
| M3 | 0.8.0-beta-M3 | EnergyDispatcher + 每 Tick 分帧调度 | ✅ |
| M4 | 0.8.0-beta-M4 | 协议容量动态化（refreshProtocolCost） | ✅ |
| M5 | 0.8.0-beta-M5 | Code Review 修复批次（能量结算/方向性/空集合等） | ✅ |
| M6 | 0.8.0-beta-M6 | 核心区块常加载 + DeviceRegistry 回调机制 | ✅ |
| M7 | 0.8.0-beta-M7 | 渲染系统 + 激光连接（GridSyncPayload/锚点/跨维度光晕） | ✅ |
| M8 | 0.8.0-beta-M8 | 指令系统 + HUD + P0/P1 修复批次（连接持久化/调度契约/生命周期清理） | ✅ |
| M9 | 0.8.0-beta-M9 | Web 监控面板（JSON 数据接口） | 规划中 |
| M10 | 0.8.0-beta | 完整文档 + 单测基线 + 正式发布 | 规划中 |

> NFDS/NFDA 适配器（FE 桥接）原计划 M5，实际执行中顺延至 M9+；
> 单元测试基线（V-13）为 M10 发布门槛。

---

## 十、附录

### 附录 A：存储路径

| 数据类型 | 路径 | 状态 |
|----------|------|------|
| 连接数据 | 世界存档/data/iems_grid.dat（GridSavedData，NBT） | ✅ 已实现 |
| 核心/设备实例 | 各自 BlockEntity NBT（区块加载后 onLoad 重注册） | ✅ 已实现 |
| 配置文件 | config/DLZstudio/IEMS/iems.toml | 规划中（V-14） |
| 日志 | logs/iems/ | 规划中（当前走标准 slf4j） |

> 原设计的 `data/DLZstudio/IEMS/connections.dat` 受 SavedData 命名限制改为
> `data/iems_grid.dat`，功能等价（连接 + 锚点全量持久化）。

### 附录 B：连接数据格式（iems_grid.dat，NBT）

```
iems_grid.dat (CompoundTag)
├── connections: List<CompoundTag>   // 显式连接全量
│   ├── start: { dimension: "minecraft:overworld", x, y, z }
│   ├── end:   { dimension: "minecraft:the_nether", x, y, z }
│   ├── type:  "RELAY_TO_RELAY" | "DIMENSION_BRIDGE"
│   └── 锚点: startAnchor/endAnchor (x, y, z 偏移，建连时固化)
└── 连接增删即 markDirty，存档保存时由拓扑全量重写
```

核心、设备、维度门对端不写入该文件——由各自 BlockEntity NBT 重注册自动重建；
iems_grid.dat 只存「显式连接」（含锚点），与「实例可重建」设计一致。

---

文档结束
等离子工作室 (DLZstudio) © 2026 — IEMS v0.8.0-beta
