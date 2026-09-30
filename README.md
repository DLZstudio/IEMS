# 综合能源管理系统 (Integrated Energy Management System, IEMS)

![版本](https://img.shields.io/badge/版本-0.8.0--beta-blue)
![开发代号](https://img.shields.io/badge/开发代号-IEMS0930-purple)
[![NEOFORGE](https://img.shields.io/badge/NeoForge-21.1%2B-orange)](https://neoforged.net/)
[![MINECRAFT](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.minecraft.net/)

> 为大型整合包设计的能源管理**框架**模组：统一能量抽象、高精度 BigInteger 运算、激光拓扑可视化与 Web 监控面板。设备与核心由外部模组接入，IEMS 负责把它们编入同一张电网。

**开发团队：** 等离子工作室 (DLZstudio)

**模组 ID：** `iems`

**开发代号：** IEMS0930

**开源协议：** MIT License

---

## 📖 目录

- [模组简介](#模组简介)
- [方块介绍](#方块介绍)
- [玩法指南](#玩法指南)
- [连接系统](#连接系统)
- [协议容量系统](#协议容量系统)
- [Web 监控面板](#web-监控面板)
- [能量汇率配置](#能量汇率配置)
- [渲染配置](#渲染配置)
- [持久化存储](#持久化存储)
- [高级玩法](#高级玩法)
- [技术细节](#技术细节)
- [开发者 API](#开发者-api)
- [构建与开发](#构建与开发)
- [依赖要求](#依赖要求)
- [常见问题](#常见问题)
- [调试命令](#调试命令)
- [更新日志](#更新日志)
- [致谢](#致谢)

---

## 模组简介

IEMS 0.8.0-beta 是对旧版 IEMS 的**完全重写**（逻辑层与表现层彻底分离）：

- **能源管理框架** — 本模组不直接提供发电/储能核心，核心与设备由外部模组通过 API 接入（如 ZCSMSS、IEMS-TestDevices）
- **统一能量抽象** — FE / AE 等单位统一换算为 SE（Standard Energy），BigInteger 高精度运算，超大数值无精度损失
- **激光连接可视化** — 设备间激光束实时展示电网拓扑，通电黄色 / 断电红色 / 桥接青色（可配置）
- **手动拉线 + 自动组网** — Shift+右键拉线建连（HUD 实时测距），广播塔范围内自动扫描连接，外部 FE 设备自动桥接
- **协议容量管理** — 基于设备数量的电网负载上限，超限自动关停保护
- **Web 监控面板** — 浏览器实时查看电网状态、设备、拓扑与历史指标（JDK 内置 HttpServer，零第三方依赖）
- **模拟化持久化** — 电网数据存于 `iems_grid.dat`，重启后由设备工厂重建，**无区块加载也持续运行**
- **开放 API** — 稳定的 `IEMSAPI` 静态门面 + `IIemsInteractable` 拉线契约，供外部模组接入

> ⚠️ **重要：** 本模组是**框架**，不提供核心方块！需要安装提供核心的模组（如 ZCSMSS 或测试用 IEMS-TestDevices）才能组成完整电网。

---

## 方块介绍

### 核心方块

> 📌 由其他 Mod 通过 `CoreDevice` 接入提供（如 ZCSMSS、IEMS-TestDevices 的测试核心）

- 电网身份锚点，能量池总入口/出口，协议容量管理者
- 注册时所在区块自动设为**常加载**（注销时解除）
- 唯一实例；电网的 BFS 可达性判定起点

### 能源传输中继器 (Energy Transfer Relay)

- 长距离信号中继与电力传输，**手动拉线**设备（不参与自动扫描）
- 连接距离：**500 格**
- 激光锚点：方块中心 (0.5, 10.75, 0.5)（塔顶）
- 激光颜色：黄色（通电）/ 红色（断电）
- 占用协议容量：**1**

### 能源广播塔 (Energy Broadcast Tower)

- 短距离无线传输，**自动 + 手动**双模式
- 连接距离：**50 格**
- 信号源位置：塔顶 (0.5, 8.0, 0.5)
- 放置后自动扫描并连接范围内的设备（含中继器、其他广播塔）
- 必须直接或间接连接核心才会真正供电（孤岛连接渲染为红色激光）
- 占用协议容量：**1**
- 自带 7 级光照，黑暗环境可见

### 外部 FE 设备（自动桥接）

- IEMS 自动发现范围内**任意 NeoForge FE 设备**（其他科技模组的发电机/用电器/储能）
- 每 20 tick（1 秒）重扫一次，发现即自动建立桥接连接（青色激光，可配置为常规色）
- 无需对方模组做任何适配

---

## 玩法指南

### 新手入门

**1. 理解能源单位**

所有能量以 SE（Standard Energy）为统一单位，BigInteger 精确存储。外部 FE 设备的能量由桥接层自动换算。

**2. 电网工作原理**

```
┌──────────────────────────────────────────┐
│        核心（由其他 Mod 提供）              │
│         能量池 · 协议容量 · BFS 起点        │
│                    │                      │
│      ┌─────────────┼─────────────┐        │
│      ↓             ↓             ↓        │
│   ┌──────┐     ┌──────┐     ┌────────┐   │
│   │中继器 │────│广播塔 │────│FE 设备  │   │
│   │500 格│     │50 格  │     │自动桥接 │   │
│   └──────┘     └──────┘     └────────┘   │
└──────────────────────────────────────────┘
```

**3. 建网流程**

1. 安装提供核心的模组并放置核心
2. 放置中继器 / 广播塔（或直接利用现有 FE 设备）
3. 对准设备 **Shift + 右键** 进入拉线模式（HUD 显示实时距离）
4. 对准目标设备 **Shift + 右键** 完成连接
5. 广播塔范围内设备自动组网；FE 设备自动桥接
6. 浏览器打开 `http://127.0.0.1:28567/` 监控电网

### 设备供能状态

- 连接到核心（直接或经由中继器/广播塔）的设备自动供能
- 电网关停时（协议容量超限或手动关停）所有设备断电，激光变红
- 未接入核心但两端注册的连接（孤岛连接）渲染为**红色激光**

---

## 连接系统

### 手动拉线模式

**进入方式：**
- 对准实现了拉线契约的设备按 **Shift + 右键**

**拉线过程：**
- HUD 实时显示拉线距离与最大距离
- 超过最大距离（+10 格缓冲）自动收线并提示
- **维度改变（穿传送门 / 跨维度 TP）立即自动收线**——旧坐标在新维度无意义
- 退出世界自动清理拉线状态

**完成连接：**
- 对准目标设备再按 **Shift + 右键**
- 客户端发送连接请求，**服务端二次校验**（端点合法、同维度、距离、重复、中继规则）后建连
- 点击起点自身或非设备方块 → 取消拉线

### 有效连接规则

| 连接类型 | 是否有效 | 条件 |
|---------|---------|------|
| 核心 → 中继器 | ✅ | 距离内，核心常加载 |
| 核心 → 广播塔 | ✅ | 距离内 |
| 中继器 → 中继器 | ✅ | 500 格内 |
| 中继器 → 广播塔 | ✅ | 取两端较小距离限制 |
| 广播塔 → 广播塔 | ✅ | 50 格内，自动或手动 |
| 广播塔 → FE 设备 | ✅ | 自动桥接（ADAPTER_BRIDGE） |
| 跨维度连接 | ❌ | 客户端/服务端双重拒绝（维度桥除外） |
| 未接入核心的孤岛互连 | ⚠️ | 允许创建，渲染红色激光，不供电 |

### 连接距离限制

> ⚠️ 距离限制取连接**两端设备声明距离中的较小值**。未声明时使用框架默认 500 格。

| 设备组合 | 最大距离 |
|---------|---------|
| 中继器 ↔ 中继器 | 500 格 |
| 广播塔 ↔ 广播塔 | 50 格 |
| 广播塔 ↔ 中继器 | 50 格 |
| 桥接（FE 设备 ↔ 设备） | 跟随锚定设备 |

### 激光显示

| 状态 | 颜色 | 说明 |
|------|------|------|
| 通电连接 | 黄色 | 功率流动带动画 |
| 断电 / 孤岛连接 | 红色 | 电网关停或未接入核心 |
| FE 桥接 | 青色（默认） | `colorizeAutoConnect = true` 时与常规连接区分 |
| FE 桥接 | 黄/红 | `colorizeAutoConnect = false` 时与常规连接同色 |

**渲染特性：**
- 光束宽度随距离做屏幕空间最小宽度补偿（远处不会细到消失）
- 双面渲染，任意观察角度可见
- 跨维度孤岛连接不渲染（无法计算方向向量）
- 两端任一在渲染距离内即绘制（长连接不会被视锥整体剔除）

---

## 协议容量系统

### 什么是协议容量？

协议容量是电网能够支持的设备连接数量上限。

| 设备 | 占用容量 |
|------|---------|
| 中继器 | 1 |
| 广播塔 | 1 |
| 外部接入设备 | 由设备声明 |
| 核心 | 0（不占用）|

**上限：** 由核心注册时声明（`protocolLimit`），运行时可调。

### 超限保护

当已用容量超过总容量时：
1. 电网自动关停
2. 所有设备断电，激光变红
3. `/iems status` 显示关停原因

**恢复方法：**
- 拆除部分设备（触发重扫后自动恢复）
- 或由核心模组运行时上调容量

---

## Web 监控面板

### 访问方式

1. 启动游戏并加载世界（面板随服务器启停）
2. 浏览器访问：`http://127.0.0.1:28567/`
3. 首次启动自动生成随机 Token（写入配置文件）

### 鉴权

- **全部 `/api/*` 端点需要 Token**：请求头 `X-IEMS-Token: <token>` 或查询参数 `?token=<token>`
- `/health` 与 `/`（首页）免鉴权
- Token 位于 `config/DLZstudio/IEMS/APIserve.toml`

### 配置文件

```toml
# config/DLZstudio/IEMS/APIserve.toml
[APIserve]
enabled = true          # 是否启用 Web 面板
host = "127.0.0.1"      # 绑定地址；远程访问改为 "0.0.0.0"（务必同时改 Token 并配 TLS 反代）
port = 28567            # 监听端口
token = "a1B2c3D4..."   # API Token（首次启动随机生成）
```

### 端点总览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 存活探针（不套信封） |
| GET | `/api/status` | 电网总览（核心位置/能量/容量/协议/关停状态） |
| GET | `/api/devices` | 设备列表（含核心，逐设备属性） |
| GET | `/api/connections` | 连接列表（可达主网 + 边界连接） |
| GET | `/api/topology` | 拓扑快照（主网/孤岛/边界三视角聚合） |
| GET | `/api/metrics` | 时序指标（环形缓冲，能量/容量/协议/设备数采样） |
| POST | `/api/grid/active` | 手动开启/关闭电网（映射 `IEMSAPI.setGridActive`） |

### 数据信封

```json
{
  "schema": 1,
  "ts": 1727073600000,
  "data": { }
}
```

- 能量/容量/速率等 BigInteger 字段以**十进制字符串**传输（避免精度丢失）
- 维度使用资源键字符串（如 `"minecraft:overworld"`）
- 无核心时能量返回 `"0"`、`corePos` 为 `null`

### curl 示例

```bash
# 只读端点
curl -H "X-IEMS-Token: <token>" http://127.0.0.1:28567/api/status

# 写端点（关闭电网）
curl -X POST -H "X-IEMS-Token: <token>" \
     -H "Content-Type: application/json" \
     -d '{"active": false}' \
     http://127.0.0.1:28567/api/grid/active
```

> 💡 Windows PowerShell 中内联 JSON 会被参数传递破坏引号，建议把请求体写入文件后用 `--data-binary @body.json` 提交。

---

## 能量汇率配置

SE（电网内部标准单位）与 FE 的汇率决定了「多大的能量缺口才值得动用 FE 级设备」，可按整合包的能量量级调整。

`config/DLZstudio/IEMS/Energy.toml`：

```toml
# IEMS 能量汇率配置
[energy]
# 1 SE 折算多少 FE（即 SE:FE = 1:N）。
# 默认 10；取值范围 [1, 900000000000000000000000000]（上限即旧版固定值，可一键恢复旧行为）。
# 修改后在游戏内执行 /iems reload 即时生效（无需重启游戏/退出世界）。
# 注意：改动会重新解释电网中已有的 SE 数值（1 SE 的 FE 当量被重定义），请谨慎调整。
fePerSe = 10
```

- **默认 1 SE = 10 FE**：适配主流 FE 设备量级，FE 桥接设备可正常参与电网吞吐
- **下限 1**（1 SE = 1 FE）、**上限 9×10²⁶**（旧版硬编码默认值，设为此值即恢复旧行为）
- 越界值自动收敛到上下限，非数字回退默认值
- **手动重载**：改完文件后在游戏内执行 `/iems reload`（权限等级 2）即时生效，**无需重启游戏，也无需退出世界**
- 编辑时若删掉整个键，则保留当前生效值而非回退默认（局部编辑不会误伤）

### `/iems reload`

一条命令重载全部 IEMS 配置文件（手动触发，不做自动轮询）：

| 配置文件 | 重载效果 |
| --- | --- |
| `Energy.toml` | 重新解析汇率并注入换算器，即时生效 |
| `APIserve.toml` | 重建 Web 面板，应用 `enabled` / `host` / `port` / `token` |
| `Renderer.toml` | 使客户端渲染配置缓存失效（本机客户端下次读取生效；专用服务器上的远程客户端需重进世界） |

> `/iems reload` 需要权限等级 2（与 `/iems shutdown`、`/iems restart` 一致）。

> ⚠️ 汇率是 SE 与 FE 的**定义**，改动会立即重新解释电网中所有已存在的 SE 数值（核心储能、设备容量等）。设备之间的 SE 比例关系不变、结算依然守恒，但对外呈现的 FE 当量会整体缩放——请按整合包能量量级一次性定好，避免中途频繁调整。

> ⚠️ 汇率调高时，FE 量级设备的真实速率折算后可能不足 1 SE/tick；桥接层已按「一 tick 送抵量」自适应申报粒度，因此无论汇率高低都不会出现吞吐被压到 `fePerSe` FE/tick 的情况。

---

## 渲染配置

`config/DLZstudio/IEMS/Renderer.toml`：

```toml
# IEMS 渲染配置
[renderer]
colorizeAutoConnect = true   # true：自动桥接连接用青色；false：与常规连接同色（黄/红）
```

- **零 IO 惰性缓存**：配置按需读取并缓存，退出世界自动失效
- 修改配置后**重新进入世界即生效**，无需重启游戏

---

## 持久化存储

### 电网数据保存

**存储位置：**

```
世界存档/data/iems_grid.dat
```

**持久化内容：**
- 设备列表（按工厂 ID 索引，含位置/类型/参数）
- 核心信息
- 全部连接（含孤岛连接与桥接连接）

### 模拟化重建（M9）

- 设备以**工厂 ID + 参数**形式持久化，而非依赖 BlockEntity 存活
- 服务器启动时遍历 `iems_grid.dat`：工厂重建 → `restoreState` → 电网恢复
- **设备所在区块未加载时电网依然持续模拟运行**（BlockEntity 只是外观/视图）
- 核心所在区块注册时自动常加载

### 自动保存时机

- 连接创建 / 删除时
- 世界保存时
- 服务器停止时

### 数据安全性

- 设备破坏时自动注销并清理连接
- 区块卸载**不再注销设备**（BUILD.00000110 修复：`setRemoved` 误注销核心导致重启丢连接）
- 存档重启后连接完整保留

---

## 高级玩法

### 组网技巧

- **高度优势：** 广播塔建在高处扩大有效覆盖
- **中继器骨干：** 长距离用中继器（500 格）做骨干链路，广播塔（50 格）做末端覆盖
- **FE 设备复用：** 现有科技模组设备直接自动桥接，无需拆改
- **孤岛预布线：** 未连核心的设备可先互连（红色激光提示未供电），核心就位后全网上电
- **核心选址：** 核心区块常加载，放在主基地中心减少骨干长度

### 能源管理策略

- **峰谷平衡：** 用电高峰靠储能设备放电
- **容量规划：** 预留 20% 协议容量余量，超限即全网关停
- **监控预警：** `/api/metrics` 提供时序采样，可接外部仪表盘
- **分区供电：** 重要设备独立核心/子网

---

## 技术细节

### 架构：逻辑-表现完全分离

```
外部模组 (设备提供方)
│
└── Block / BlockEntity (注册到 Minecraft，仅外观/视图)
    │
    ├── 构造器/loadAdditional() 中 new 逻辑实例 (纯 POJO，无需 Level)
    ├── onLoad() 中 IEMSAPI.attachOrRegisterDevice() 接入 (仅服务端)
    └── saveAdditional() 持久化参数
                    │
                    ▼
┌─────────────────────────────────────────────┐
│              IEMS 逻辑层 (com.iems)          │
│                                             │
│  CoreDevice (唯一实例，常加载)                │
│  设备池 DeviceRegistry (N 实例)              │
│                                             │
│  GridTopology (BFS/拓扑快照)                 │
│  EnergyDispatcher (每Tick能量分配+分帧调度)   │
│  ProtocolCalculator (容量计算/超限检测)       │
│  FEDA (FE 设备发现与桥接适配)                │
└─────────────────────────────────────────────┘
```

**设计哲学：实例自治**——每个设备实例完全独立，不持有其他实例引用，只通过调度器与电网交互；支持独立 new / 销毁 / 改参数，天然支持多核并行。

### 模块划分

| 包 | 职责 |
|----|------|
| `com.iems.api` | 公开门面（`IEMSAPI` / `IIemsInteractable` / `SubSeAccumulator`） |
| `com.iems.core` | 电网内核（grid / node / 调度 / 协议） |
| `com.iems.adapter` | 外部能量适配（`FEDA` FE 桥接） |
| `com.iems.network` | C2S/S2C 载荷（连接请求、全量同步、关停同步） |
| `com.iems.webpanel` | Web 面板（JDK HttpServer，端口 28567） |
| `com.iems.example` | 示例设备（中继器/广播塔，仅依赖 api 门面） |

### 线程模型

- 电网全部状态由**服务端 tick 线程独占**
- 外部线程（Web 面板 HTTP 线程等）通过 `IEMSAPI.runOnServerThread()` / `callOnServerThread()` 提交任务，杜绝数据竞争
- Web 面板线程池随世界重载手动停止，防止线程泄漏

### 网络同步

- `GridSyncPayload` — 全量拓扑同步（主网/孤岛/桥接三列表，尺寸校验防 OOM）
- `ConnectionRequestPayload` — C2S 拉线建连请求（服务端二次校验 + 邻近检查 + 频率限制）
- 关停状态 / 协议容量随快照同步，HUD 三态显示

### 激光渲染细节

- Billboard 宽度方向：宽 = 光束方向 × (相机→光束)，侧视不消失
- 屏幕空间最小宽度：`max(0.03, 距离 × 0.0015)`，远处光束不细化消失
- 相邻条带间退化三角形分隔，杜绝"幽灵斜线"
- 客户端缓存（`ClientGridCache`）处理主网 + 孤岛 + 桥接三列表，含四象限统计与 EMA 平滑

---

## 开发者 API

### 快速开始

IEMS 以 **Jar-in-Jar / libs 依赖**方式接入外部模组：

**Gradle 依赖（Groovy DSL）：**

```groovy
dependencies {
    // 将 IEMS 构建产物复制到 libs/ 后：
    implementation files('libs/IEMS-0.8.0-beta-BUILD.00000114.jar')
}
```

**neoforge.mods.toml 依赖声明：**

```toml
[[dependencies.yourmod]]
    modId="iems"
    type="required"
    versionRange="[0.8.0-beta,)"   # ⚠️ 必须带 -beta 后缀；"[0.8.0,)" 会被语义比较判为不满足导致崩溃
    ordering="AFTER"
    side="BOTH"
```

### 设备接入：三大逻辑基类

外部模组的 BlockEntity 持有逻辑实例（纯 POJO）：

```java
// 1. 模组加载期注册工厂（M9 持久化重建的关键）
IEMSAPI.registerDeviceFactory("mymod:my_device", MyDevice::new);

// 2. BlockEntity.onLoad()（仅服务端）接入
GlobalPos pos = new GlobalPos(level.dimension(), blockPos);
MyDevice device = new MyDevice("我的设备");
IEMSAPI.attachOrRegisterDevice(pos, device, "mymod:my_device");
// 带 factoryId 的设备持久化到 iems_grid.dat，重启后无区块加载也由工厂重建

// 3. BlockEntity.setRemoved() 注销
IEMSAPI.unregisterDevice(pos);
```

**核心接入：**

```java
CoreDevice core = new CoreDevice("我的核心",
        BigInteger.valueOf(1000),   // protocolLimit 协议容量上限
        BigInteger.valueOf(100000), // energyCapacity 能量总容量 (SE)
        BigInteger.ZERO);           // powerGenRate 每 Tick 自发电量
IEMSAPI.attachOrRegisterCore(pos, core, "mymod:my_core");
// 注册时所在区块自动常加载；注销时解除
```

### 拉线契约：IIemsInteractable

BlockEntity 实现本接口即可参与框架内置的 Shift+右键拉线交互：

```java
public class MyDeviceBlockEntity extends BlockEntity implements IIemsInteractable {
    @Override
    public boolean canBeConnectionEndpoint() {
        return true;
    }

    @Override
    public int getMaxConnectionDistance() {
        return 500;  // 未声明时框架默认 500 格
    }
}
```

- 纯**客户端发现机制**（`instanceof` 识别），服务端不依赖此接口做安全判定
- 拉线 HUD、超距收线、维度变化收线全部由框架处理
- 服务端建连走 `ConnectionRequestPayload` 二次校验（端点/同维度/距离/重复/中继规则）

### IEMSAPI 静态门面（方法总览）

| 分类 | 方法 | 说明 |
|------|------|------|
| 线程安全 | `runOnServerThread(Runnable)` | 外部线程异步提交任务 |
| 线程安全 | `callOnServerThread(Supplier, timeoutMs)` | 外部线程同步等待结果 |
| 工厂 | `registerDeviceFactory(id, factory)` | 注册设备工厂（M9 重建） |
| 设备 | `attachOrRegisterDevice(pos, node, factoryId)` | 接入设备（带工厂则持久化） |
| 设备 | `unregisterDevice(pos)` | 注销设备 |
| 核心 | `attachOrRegisterCore(pos, core, factoryId)` | 接入核心（区块常加载） |
| 核心 | `unregisterCore(pos)` | 注销核心 |
| 查询 | `getCurrentEnergy()` / `getTotalCapacity()` | 能量池 (BigInteger) |
| 查询 | `getProtocolUsed()` / `getProtocolTotal()` | 协议容量 |
| 查询 | `getSnapshot()` | 拓扑快照（主网/孤岛/边界） |
| 查询 | `isDeviceConnected(pos)` | 设备是否接入主网 |
| 连接 | `addConnection(a, b, type)` / `removeConnection(...)` | 连接管理 |
| 控制 | `setGridActive(boolean)` / `forceRescan()` | 电网开关 / 强制重扫 |
| 功率 | `registerPowerInput(id, rate)` / `unregisterPowerInput(id)` | 功率源注册 |
| 发现 | `scanForDevices(level, center, radius)` | 周边设备扫描 |
| 适配 | `registerEnergyFlavor(flavor)` | 注册能量风味 |

### Web 面板 HTTP API（完整）

要点：

- 全部 `/api/*` 需 Token（`X-IEMS-Token` 头或 `?token=` 参数）
- 统一信封 `{schema, ts, data}`，BigInteger 字符串化
- 错误码：`400`（参数/JSON 错误）、`401`（未授权）、`404`（路由不存在）、`405`（方法不允许）、`409`（无核心等状态冲突）、`500`（内部错误）

### 参考实现：IEMS-TestDevices

测试模组 `IEMS-TestDevices`（modid `iemstest`）演示了全部接入姿势：

| 设备 | 演示内容 |
|------|---------|
| `test_core` 测试核心 | `CoreDevice` 接入 + 协议容量声明 |
| `auto_relay` / `manual_relay` | 自动/手动中继设备（`TransferDevice`） |
| `fe_battery` | FE 储能方块（被 FEDA 自动桥接） |
| `arc_furnace` 电弧炉 | 带 GUI/Menu 的用电设备完整示例 |

---

## 构建与开发

### BUILDID 构建系统

本模组使用 DLZ Studio BUILDID 规范构建，**唯一合法构建入口**：

```
.DLZstudio/buildid/
├── BUILDID.txt    # 迭代计数器（8 位零填充）
├── build.ps1      # Windows 构建入口
└── build.sh       # Unix/Linux/macOS 构建入口
```

**Windows：**

```powershell
# ⚠️ 先设置 JAVA_HOME（脚本环境不继承）
$env:JAVA_HOME="D:\Java\jdk-21"
$env:Path="D:\Java\jdk-21\bin;"+$env:Path
powershell -ExecutionPolicy Bypass -File .\.DLZstudio\buildid\build.ps1
```

**Unix：**

```bash
./.DLZstudio/buildid/build.sh
```

**发布模式**（版本号同步 `neoforge.mods.toml`）：
- `build.ps1 -Release` / `build.sh --release`

**产物命名：** `IEMS-0.8.0-beta-BUILD.00000114.jar`（普通）/ `{modid}-{mcver}-neoforge-{semver}.{n}`（发布）

### 测试

```powershell
.\gradlew test        # webpanel + client 单测（含 RendererConfigTest 等）
.\gradlew compileJava # 快速编译检查
```

### 环境要求

- JDK 21
- NeoForge 21.1.x（依赖范围 `[21.1.0,)`）
- 测试运行时 classpath 已同步至纯 ASCII 目录（规避中文路径 GBK 解码问题）

---

## 依赖要求

### 必需依赖

| 模组 | 版本 | 说明 |
|------|------|------|
| NeoForge | 21.1.0+ | 模组加载器（依赖范围 `[21.1.0,)`） |
| Minecraft | 1.21.1 | — |

### 内置组件

| 组件 | 说明 |
|------|------|
| GeckoLib 4.8.4 | **Jar-in-Jar 内嵌**（`META-INF/jarjar/`），玩家无需单独安装 |

### 推荐搭配

| 模组 | 说明 |
|------|------|
| **ZCSMSS**（矩阵子节点服务器） | 提供核心方块，使用 IEMS API，完美联动 |
| **IEMS-TestDevices** | 官方测试模组：测试核心/中继/FE 电池/电弧炉 |
| 任意 FE 科技模组 | 设备由 FEDA 自动发现桥接 |

### 安装步骤

1. 安装 NeoForge 21.1+
2. 下载 IEMS JAR 放入 `mods` 文件夹
3. （可选）放入提供核心的模组（ZCSMSS 或 IEMS-TestDevices）
4. 启动游戏

---

## 常见问题（FAQ）

**Q：为什么没有核心方块？**

A：IEMS 是能源管理**框架**，核心由外部模组提供（如 ZCSMSS），避免功能重复。测试可安装 IEMS-TestDevices。

**Q：如何知道核心是否工作？**

A：`/iems status` 查看电网状态，或访问 Web 面板 `http://127.0.0.1:28567/`（需 Token）。

**Q：重启存档后连接丢了？**

A：BUILD.00000110 已修复（区块卸载误注销核心）。电网数据存于 `iems_grid.dat`，重启完整保留；若仍有丢失请确认 BUILD ≥ 110。

**Q：激光变红了？**

A：两种情况——电网关停（协议容量超限或手动 `/iems shutdown`），或孤岛连接（未接入核心的连接，一直红但不供电）。

**Q：拉线拉到一半传送走了会怎样？**

A：维度变化（传送门/跨维度 TP）立即自动收线并提示；退出世界也会清理拉线状态。

**Q：其他模组的 FE 设备怎么接入？**

A：无需任何操作——FEDA 每 20 tick 自动扫描并桥接范围内 FE 设备（青色激光）。

**Q：不喜欢青色桥接激光？**

A：修改 `config/DLZstudio/IEMS/Renderer.toml` 中 `colorizeAutoConnect = false`，重进世界生效。

**Q：想调整 SE 与 FE 的汇率？**

A：修改 `config/DLZstudio/IEMS/Energy.toml` 中 `fePerSe`（默认 10，范围 1 ~ 9×10²⁶），然后在游戏内执行 `/iems reload` 即时生效，**无需重启**。注意汇率变化会立即重新解释电网中已有的 SE 数值。

**Q：Web 面板无法访问？**

A：检查：游戏是否已加载世界、`APIserve.toml` 中 `enabled` 是否为 true、端口 28567 是否被占用、防火墙。远程访问需改 `host = "0.0.0.0"` 并**务必**更换 Token、配置反向代理 TLS。

**Q：外部模组依赖 IEMS 时版本范围怎么写？**

A：`"[0.8.0-beta,)"`。**不能**写 `"[0.8.0,)"`——语义比较中 `0.8.0-beta < 0.8.0`，会因强制依赖不满足而崩溃拒载。

**Q：如何开发自己的设备？**

A：见本文档 [开发者 API](#开发者-api) 一节，参考 IEMS-TestDevices 源码（电弧炉是带 GUI 的完整示例）。

---

## 调试命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/iems status` | 无 | 电网状态（核心/能量/主网/孤岛/连接/关停原因） |
| `/iems protocol` | 无 | 协议容量使用量与超限状态 |
| `/iems scan` | 无 | 强制 BFS 重扫并报告结果 |
| `/iems shutdown` | 2 | 手动关停电网（设备增删不自动恢复） |
| `/iems restart` | 2 | 恢复供电 |
| `/iems reload` | 2 | 重载全部 IEMS 配置文件（Energy / APIserve / Renderer） |

---

## 更新日志

### IEMS0930 (2026-09-30, BUILD.00000114)

**开发代号 IEMS0930 起用；本条目对应 0.8.0-beta 重写主线最新构建。**

**修复（连接持久化）：**
- ✅ 区块卸载时 `setRemoved` 误注销核心设备导致重启丢失核心连接——现在卸载路径打标记跳过注销，方块真正消失仍正常注销
- ✅ 孤岛过滤的核心端豁免：与核心断连后连接渲染为红色激光而非直接消失（对齐旧版语义）

**修复（激光渲染）：**
- ✅ 相邻条带间退化三角形分隔，消除"幽灵斜线"
- ✅ 渲染健壮性系列：billboard 宽度方向（侧视不消失）、屏幕空间最小宽度（远处不细化）、双面渲染（防角度性整条消失）、跨维度异常连接防御

**修复（光照）：**
- ✅ 示例设备天光柱直穿（`propagatesSkylightDown` 覆写 + 机制文档化），新放置设备不再出现"纯黑模型"

**新增（渲染配置）：**
- ✅ `Renderer.toml` 的 `colorizeAutoConnect` 开关：FE 桥接青色激光可关闭
- ✅ 渲染配置零 IO 惰性缓存，重进世界生效

**新增（能量汇率配置）：**
- ✅ `Energy.toml` 的 `fePerSe` 汇率项（默认 1 SE = 10 FE，范围 1 ~ 9×10²⁶）
- ✅ `/iems reload` 指令：手动重载全部配置（Energy / APIserve / Renderer），无需重启游戏
- ✅ FE 桥接需求申报按「一 tick 送抵量」自适应，低汇率下吞吐不再被压到 `fePerSe` FE/tick
- ✅ 移除旧版遗留的 GE 单位（工程内零引用；新版单位体系为 FE / AE / SE）

**修复（WebPanel / 线程安全）：**
- ✅ HTTP 线程与 tick 线程对 `CoreDevice` 的数据竞争——外部线程任务队列化（H-01）
- ✅ 世界重载时线程池泄漏（M-01）、指标环形缓冲跨会话串扰（M-02）
- ✅ C2S 连接请求邻近检查 + 频率限制（H-02）、`GridSyncPayload` 尺寸校验防客户端 OOM（M-03）
- ✅ 多宿主重叠半径下 FEDA 静默注册跳过时不再创建桥接（M-04）
- ✅ 拉线态跨维度/退出世界状态残留（M-06）

### 0.8.0-beta 重写主线（2026-08 ~ 2026-09）

- **M1-M4** 电网内核：设备注册、BFS 拓扑、协议容量、能量调度
- **M5-M7** 网络同步与激光渲染：全量快照同步、孤岛/桥接渲染、四象限统计
- **M8/M8.1** 拉线交互：`IIemsInteractable` 契约、C2S 请求与服务端二次校验
- **M9 模拟化** 设备工厂持久化，重启后无区块加载持续运行
- **M10 WebPanel** JDK HttpServer 面板（28567 端口、Token 鉴权、指标环形缓冲）
- **M11 审查修复** 潜在问题报告 H/M/L 系列最小化修复 + 渲染配置
- 外部 FE 桥接（FEDA）首次打通：IEMS 与 NeoForge FE 设备成功互联（BUILD.00000091 起）

---

## 致谢

- **GeckoLib 团队** — 优秀的 3D 模型渲染库（Jar-in-Jar 内嵌分发）
- **NeoForge 团队** — 强大的模组加载器
- **北山_Besson** — 提供部分设备模型
- **[EI] 终末地工业** — 部分模型参考来源

---

## 许可证

[MIT License](https://opensource.org/licenses/MIT)

本模组开源，欢迎贡献和二次开发！

内嵌的 GeckoLib 4.8.4 同样以 MIT License 分发，其声明见嵌套 JAR 内的 LICENSE 文件。

---

*等离子工作室 (DLZstudio) © 2026 · 开发代号 IEMS0930*
