# IEMS 开发白皮书 v0.8.0-beta

面向 Minecraft NeoForge 的综合能源管理系统

**发布机构：** 等离子工作室 (DLZstudio)
**适用版本：** IEMS 0.8.0-beta（对应 Minecraft 1.21.1, NeoForge 21.1.x, Java 21+）
**文档性质：** 内部架构规范 / 开发指南 / 最终裁定标准
**归档日期：** 2026年8月16日

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
    ├── 持有 IEMS 逻辑实例 (CoreDevice / TransferDevice / StorageDevice)
    ├── 在构造器 / onLoad() 中 new 实例并调用 IEMSAPI.register()
    ├── 在 saveAdditional() 中持久化参数
    └── 在 loadAdditional() 中重建实例 (可选择性读取NBT)
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
| protocolCost | int | 协议容量占用 |
| maxConnectionDistance | int | 最大连接距离 (格) |
| autoConnect | boolean | 是否启用自动连接 |
| whitelist | List\<String\> | 白名单 (方块ID) |
| blacklist | List\<String\> | 黑名单 (方块ID) |

运行时方法：

| 方法 | 说明 |
|------|------|
| void updateStrategy(IConnectionStrategy) | 运行时替换连接策略 |
| boolean isConnectedToCore() | 查询是否已接入电网 |
| CompoundTag serializeState() | 序列化状态供持久化 |
| void restoreState(CompoundTag) | 从NBT恢复状态 |

#### 3.1.3 StorageDevice（储能节点）

职责：电网中的能量缓存节点，负责充放电。

构造参数：

| 参数 | 类型 | 说明 |
|------|------|------|
| deviceName | String | 设备名称 |
| protocolCost | int | 协议容量占用 |
| maxEnergy | BigInteger | 储能上限 (SE) |
| ioRatePerTick | BigInteger | 每 Tick 最大充放电速率 |

核心方法：

| 方法 | 说明 |
|------|------|
| EnergyValue onChargeTick(EnergyValue) | 调度器调用，传入盈余，返回实际充入量 |
| EnergyValue onDischargeTick(EnergyValue) | 调度器调用，传入缺口，返回实际放出量 |

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
new TransferDevice(...)  ← 参数由开发者决定
    ↓
IEMSAPI.registerDevice(pos, instance)
    ↓
IEMS 将该位置加入设备池
    ↓
触发 BFS 重扫，判断是否可达核心
    ↓
saveAdditional() 将参数写入 NBT

【区块卸载】
设备从设备池移除 (但连接数据保留)
    ↓
实例被 GC 回收

【区块加载】
loadAdditional() 被调用
    ↓
开发者选择：
  ├─ 读取 NBT → 用保存的参数 new 实例 (常规行为)
  └─ 不读 NBT → 用新参数 new 实例 (量子力学玩法)
    ↓
IEMSAPI.registerDevice(pos, instance)
    ↓
查询当前电网快照 → 获取正确的供电状态
    ↓
如果 gridShutdown == true → 不供电

【设备破坏】
unregisterDevice() 被调用
    ↓
从设备池移除
    ↓
触发 BFS 重扫，更新电网快照
```

### 4.3 趣味玩法："量子力学中继器"

利用"实例可重建"特性，开发者可以实现区块加载时随机生成属性的设备：

```java
@Override
protected void loadAdditional(CompoundTag tag) {
    super.loadAdditional(tag);

    // 不读取 NBT，每次加载随机生成
    int distance = 100 + random.nextInt(400);
    int cost = 1 + random.nextInt(3);

    this.logic = new TransferDevice("薛定谔的中继器", cost, distance, true, null, null);
    IEMSAPI.registerDevice(worldPosition, this.logic);
}
```

玩家远离后再回来，中继器属性已变。这完全合法，因为连接数据存的是坐标，不受实例重建影响。

---

## 五、电网拓扑系统

### 5.1 核心常加载

核心区块强制常加载，确保电网状态查询永远可用：

- 核心注册时：Level.setChunkForced(true)
- 核心注销时：Level.setChunkForced(false)
- 服务器重启时：从 grid_state.dat 恢复核心位置，重新设置常加载

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
    private final Set<BlockPos> mainNetwork;      // 核心可达设备
    private final List<Set<BlockPos>> orphanNetworks; // 孤岛集群
    private final Set<Connection> pendingConnections; // 边界连接
    private final boolean gridShutdown;           // 关停状态
    private final BlockPos corePos;               // 核心位置 (恒有效)
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

- 分帧调度：设备池按 ID 哈希分片，每 Tick 只处理 1/20 的设备
- 功率累计：核心自发电每 Tick 累加，每秒结算一次实际注入

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
- 关停时所有设备断电，激光变红
- 恢复时自动重新供电

---

## 八、开发者 API

### 8.1 核心接口

```java
public interface IEMSAPI {
    // 设备注册
    void registerDevice(BlockPos pos, IEnergyNode node);
    void unregisterDevice(BlockPos pos);

    // 核心管理 (内部保证单例)
    void registerCore(CoreDevice core);
    void unregisterCore();

    // 能量查询
    BigInteger getCurrentEnergy();
    BigInteger getTotalCapacity();

    // 协议查询
    BigInteger getProtocolUsed();
    BigInteger getProtocolTotal();

    // 功率源注册 (外部发电机接入)
    void registerPowerInput(String id, BigInteger rate);
    void unregisterPowerInput(String id);
    void registerPowerOutput(String id, BigInteger rate);
    void unregisterPowerOutput(String id);
}
```

### 8.2 自定义设备接入

实现以下接口即可绕过三大基类接入电网：

```java
public interface IEnergyProducer {
    EnergyValue producePerTick();
}

public interface IEnergyConsumer {
    EnergyValue consumePerTick(EnergyValue available);
}
```

### 8.3 外部模组的最小实现

```java
public class MyRelayBlockEntity extends BlockEntity {
    private TransferDevice logic;

    public MyRelayBlockEntity(BlockPos pos, BlockState state) {
        super(MyRelayEntityType.get(), pos, state);
        this.logic = new TransferDevice("我的中继器", 1, 500, true, null, null);
        IEMSAPI.registerDevice(pos, this.logic);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("deviceName", logic.getDeviceName());
        tag.putInt("protocolCost", logic.getProtocolCost());
        tag.putInt("maxDistance", logic.getMaxDistance());
    }

    @Override
    protected void loadAdditional(CompoundTag tag) {
        super.loadAdditional(tag);
        String name = tag.getString("deviceName");
        int cost = tag.getInt("protocolCost");
        int distance = tag.getInt("maxDistance");
        this.logic = new TransferDevice(name, cost, distance, true, null, null);
        IEMSAPI.registerDevice(worldPosition, this.logic);
    }
}
```

---

## 九、版本规划

| 里程碑 | 版本 | 核心目标 |
|--------|------|----------|
| M1 | 0.8.0-beta-M1 | 三大基类 + IEMSAPI 骨架 |
| M2 | 0.8.0-beta-M2 | GridTopology + BFS 全局遍历 |
| M3 | 0.8.0-beta-M3 | EnergyDispatcher + 每 Tick 调度 |
| M4 | 0.8.0-beta-M4 | 协议容量系统 + 超限关停 |
| M5 | 0.8.0-beta-M5 | NFDS/NFDA 适配器 + FE 桥接 |
| M6 | 0.8.0-beta-M6 | 核心常加载 + 连接持久化 |
| M7 | 0.8.0-beta-M7 | 渲染系统 + 激光连接 |
| M8 | 0.8.0-beta-M8 | 指令系统 + HUD |
| M9 | 0.8.0-beta-M9 | Web JSON 数据接口 |
| M10 | 0.8.0-beta | 完整文档 + 正式发布 |

---

## 十、附录

### 附录 A：存储路径

| 数据类型 | 路径 |
|----------|------|
| 连接数据 | 世界存档/data/DLZstudio/IEMS/connections.dat |
| 电网状态 | 世界存档/data/DLZstudio/IEMS/grid_state.dat |
| 配置文件 | config/DLZstudio/IEMS/iems.toml |
| 日志 | logs/iems/ |

### 附录 B：连接数据格式

```json
{
  "connections": [
    {
      "start": [x1, y1, z1],
      "end": [x2, y2, z2],
      "type": "RELAY_TO_RELAY",
      "dimension": "overworld"
    }
  ],
  "corePos": [x, y, z],
  "coreId": "iems:core_provider"
}
```

---

文档结束
等离子工作室 (DLZstudio) © 2026 — IEMS v0.8.0-beta
