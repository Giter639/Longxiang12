# IMU 接口协议 v0（R4 · X22 交付物，R3 固件 X25 同步定稿）

> 状态：**正式 v0（2026-11-28 定稿）**。协议变更由 R4 定稿（同 AGENTS.md 硬规则 6），走正常 PR 流程，**固件与 App 两端必须同步改**。
> 落点：代码侧数据类 `imu/ImuModels.kt`（`ImuSample` / `ImuState` / `ImuBaseline`）；
> BLE 帧格式与 UUID 见 `firmware/esp32_focusdu_imu/README.md`（X25 固件即第一实现）。
> 进程内传对象（Flow），不做跨进程序列化。

## 0. BLE 传输层（X25 固件 → X23 App 取流）

| 项 | 值 | 说明 |
| --- | --- | --- |
| 设备名 | `FocusDu-IMU` | App 扫描展示用 |
| 服务 UUID | `6F0D0001-5A2E-4C1E-9B3A-46A5D2F10E01` | **App 按此过滤扫描** |
| 数据特征 | `6F0D0002-…` | Notify，20 字节定长帧，**25Hz（40ms）** |
| 控制特征 | `6F0D0003-…` | Write，v0 保留（采样率/量程配置 v1 开放） |
| MTU | 默认 23 恰好容纳（20 载荷 + 3 ATT 头） | 不协商 MTU |

### 帧格式（小端，20 字节）

| 字节 | 字段 | 含义 |
| --- | --- | --- |
| 0–3 | seq u32 | 递增帧号（断连查漏；固件重启归零，App 据此重对齐） |
| 4–5 | ax i16 | 加速度 X，**毫g**（±2g 量程，16384 LSB/g） |
| 6–7 | ay i16 | 加速度 Y，毫g |
| 8–9 | az i16 | 加速度 Z，毫g（静置水平 ≈ +1000） |
| 10–11 | gx i16 | 角速度 X，**0.01°/s**（±250°/s 量程，131 LSB/°/s） |
| 12–13 | gy i16 | 角速度 Y，0.01°/s |
| 14–15 | gz i16 | 角速度 Z，0.01°/s |
| 16 | temp i8 | 芯片温度 °C |
| 17 | flags u8 | bit0=本帧数据有效 |
| 18 | crc8 | CRC-8（poly 0x07，init 0x00，覆盖字节 0–17） |
| 19 | rsv | 保留 0x00 |

- App 端换算：`g = mg/1000`，`°/s = 原始值/100`；
- CRC 不符或 flags bit0=0 的帧**丢弃并计数**，不进入样本流；
- 断连：固件自动恢复广播，App 侧自动重连（autoConnect）；重连后以新 seq 重对齐。

## 1. 样本流 `ImuSample`（25Hz）

| 字段 | 类型 | 填充方 | 约束 |
| --- | --- | --- | --- |
| tsMs | long | App（接收时刻，单调时钟） | 设备端不计时；禁用墙钟 |
| seq | long | 固件 → App 透传 | 断连查漏 |
| axMg / ayMg / azMg | float | 固件（毫g） | 原始 6 轴 |
| gxDps / gyDps / gzDps | float | 固件（°/s） | 原始 6 轴 |
| pitchDeg / rollDeg | float | **X24 特征层** | 俯仰正=低头、横滚正=右倾；重力低通解算 |
| motionRms | float | **X24 特征层** | 500ms 滑窗合加速度 RMS（g）：静止≈0.02、乱动>0.3 |
| state | enum | **X24 判定层 / Mock** | 五态融合输出，客户端唯一消费 |
| conf | float | X24 判定层 | **<0.5 客户端丢弃**（与视觉同规） |

时序分工：固件只采集传输；App 端 X23 解包并补时间戳，X24 在 500ms 特征滑窗上算 pitch/roll/motionRms 并判定五态。客户端（R1）不做任何阈值判断。

## 2. 五态融合 `ImuState`

`NORMAL / HEAD_DOWN / LEAN / FIDGET / AWAY`（正常 / 低头 / 侧趴 / 乱动 / 离开）

- IMU 能判的盲区：低头/侧趴看姿态角，乱动看运动强度，离座看长时间静止；
- 偏近/困倦/离屏是**视觉专属**，IMU 不判、不猜；
- 滞回与最短保持时间在**算法端（X24）**做；客户端复用 `StateStreamDebouncer`（800ms 确认窗 + 3s 断流兜底），IMU 走独立 `imuDebouncer` 实例。

## 3. 五态 → 六态映射桥（客户端接线口径，X22 已实现并单测锚定）

`imu/ImuVisionBridge.mapState()`：

| ImuState | FusionState | 提醒类型归属 |
| --- | --- | --- |
| NORMAL | NORMAL | — |
| FIDGET | NORMAL | **不伪造视觉语义**；X24 若为乱动增补 RiskType 再放开 |
| HEAD_DOWN | HEAD_DOWN | POSTURE |
| LEAN | HEAD_DOWN | POSTURE（六态不区分低头/侧趴） |
| AWAY | AWAY | —（解除语义，对齐 RiskEventWire） |

映射改动必须同步改 `ImuVisionBridgeTest` 锚点。视觉专属字段（faceScale/光照/眼部）桥接时填中性值，不参与视觉判定。

## 4. 基线（X24）

绑定页/校准页静坐采集 5s → `ImuBaseline(pitchDeg, rollDeg, motionRms)` → `ImuEngine.setBaseline()` 注入（与视觉 T1-04 同机制，null 表示无基线用默认阈值）。

## 5. 错误与降级语义

- 固件 I²C 读失败**不发送全零帧**、flags bit0=0（不把坏数据伪装成有效状态，延续 X06 口径）；
- App 3s 无有效样本（conf≥0.5）→ `imuDebouncer` 转 Interrupted，UI 提示「检测中断」；
- 硬件不在位时 `MockImuEngine` 兜底驱动全链（对齐 VisionEngineFactory 的降级演示模式）；
- 引擎绑定在 `AppContainer` 单点完成，X23 真实现替换 Mock 时下游零改动。

## 6. 数据通路约束（架构红线）

埋点（STATE_SPAN）与提醒（RiskEventWire）**不在 X22/X23 各自接入**：视觉与 IMU 双源必须经 X26 融合仲裁统一接线后才写同一管线，避免双计。X24 状态机定稿时一并明确仲裁规则。

## 7. 性能约束（给 X23/X24 的硬指标）

- BLE 帧率 25Hz（40ms 间隔），App 解包开销可忽略；
- 特征滑窗 500ms（约 12 样本）；判定输出频率与样本流一致，滞回防跳变；
- 全部计算在端侧，禁止联网；
- 任何协议改动：固件 `.ino` + 本文档 + `ImuModels.kt` 三处同 PR 同步。
