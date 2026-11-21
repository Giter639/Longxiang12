# FocusDu · 专注有度 —— 客户端工程

![CI](https://github.com/Giter639/Longxiang12/actions/workflows/ci.yml/badge.svg)

> OPPO Android 学习过程反馈与专注管理助手 · 龙翔小分队
> R1 软件工程（骨架/番茄/提醒/报告）｜R2 需求与质量（口径/文案/回归）｜R3 采集与设备（取流/校准/权限）｜R4 人工智能（算法/状态机/答辩）
>
> **当前状态**：R1 全量完成 ✅｜R3 X11 取流 + 坐姿校准已接入 ✅｜R4 算法接入中（Mock 引擎驱动，无相机也可全链演示）｜CI 全绿（47 个验收单测）

## 一句话架构

单 Activity + Fragment 导航；引擎（番茄/提醒/报告/视觉接入）全部是进程级单例，
状态用 StateFlow 汇入 `AppSessionViewModel`（统一状态容器）；数据只有一条路 ——
埋点 `Tracker.log()` → Room → 指标引擎重算 → 报告。**没有第二条数据通路。**

```
CameraXStreamer(R3 已实装, 8fps) → VisionEngine(R4 待接入, 当前 Mock) → StateStreamDebouncer(防抖+断流兜底)
        → P07 实时小窗 / StateSpanLogger(持续段埋点) / RiskEventWire(演示接线→R4事件源替换)
        → ReminderEngine(分级提醒: 短时只记录/边缘触发/冷却≥3min/25min≤3次) → P08 / P06 横幅
PomodoroEngine(单调时钟+检查点恢复) → 前台服务 → FOCUS→BREAK 自动跳 P09 → 休息校验 RestValidator
        → FINISHED 自动跳 P12 → ReportGenerator(七区块卡片+下一步建议) → MetricsCalculator(埋点重算)
        → HistoryComparator(近7次中位, 样本<3降级) → P13 趋势折线图 / P14 对比
```

## 快速开始

0. **★ 工程必须放在纯英文路径**（如 `C:\dev\Longxiang12`）——中文路径下单测会因 AGP 已知 bug 报 ClassNotFoundException
1. Android Studio（Koala+，JDK 17+）打开 `FocusDu/`；
2. `FocusDu/local.properties` 写上你的 `sdk.dir`；
3. 双击仓库根目录 **`启动器.bat`**：自动拉起模拟器/连真机、点亮屏幕、打开 App（已装则秒开）；
4. 构建与测试：`./gradlew :app:assembleDebug :app:testDebugUnitTest`（CI 每次都会自动跑同一套）；
5. UI 文案全部在 `FocusDu/app/src/main/res/values/strings.xml`（R2 唯一入口）。

## 模块与任务对照

| 任务ID | 模块 | 位置 | 状态 |
| --- | --- | --- | --- |
| X02 | 单 Activity + 导航 + 统一状态容器 | `MainActivity` / `res/navigation` / `ui/common/AppSessionViewModel` | ✅ |
| T3-01/02 | 三种番茄模式 + 计时恢复（息屏 30min 误差<2s） | `pomodoro/PomodoroEngine` + `PomodoroService` | ✅ 实测 |
| T3-03 | 分级提醒引擎（只记录/边缘/冷却≥3min/25min≤3次） | `reminder/ReminderEngine`（P06 横幅触达） | ✅ 实测 |
| T3-04 | 休息页与恢复校验 | `ui/pages/P09RestPage` + `report/RestValidator` | ✅ 实测 |
| X18 | 状态机客户端接入（防抖+断流 3s 兜底） | `vision/StateStreamDebouncer` | ✅ 实测 |
| X11 | CameraX 取流与机型适配 | `vision/CameraStreamer`（`CameraXStreamer` 已绑定） | ✅ R3 已合入 |
| T1-02/03 | 校准采集（5–10s）与质量校验 | `vision/CalibrationSession`（P03 页已接） | ✅ R3 已合入 |
| T1-04 | 个人基线 | `vision/Baseline`（`baselineStore`，P04 演示写入） | 🟡 R4 待接 |
| T7-03 | Room 存储与容量滚动 | `data/db/` + `data/RollingCleaner` | ✅ |
| T6-01/02/03 | 报告七区块卡片 / 指标引擎 / 历史对比 | `report/`（`ReportGenerator.buildSections` + `MetricsCalculator` + `HistoryComparator`） | ✅ |
| T5-01/T7-01 | 提醒人格与引导文案 | `res/values/strings.xml` | 🟡 R2 文案待填 |
| T4-03/T4-05 | 限制应用拦截页 + 紧急放行 | `ui/pages/P10GuardInterceptPage` | 🟡 R3 待接 |
| T2-01~10 | 7 类视觉指标 + 六态融合 | `vision/protocol/VisionModels`（Mock 引擎当前驱动） | 🟡 R4 待接 |

## 三人接入指南

**R3（采集/设备）——X11 已完成 ✅**：`CameraXStreamer` 已实现并绑定，P03 校准采集链路已通（含质量校验，机型参数见 `docs/R3机型适配与验证.md`）。剩余：P10 拦截页逻辑（T4-03 触发<300ms / T4-05 紧急放行零误拦）、T4-01 权限引导补全、X12/X14 性能与兼容。

**R4（算法）——下一棒**：实现 `VisionEngine`（建议类名 `MediaPipeVisionEngine`，输出协议见 `docs/接口协议v0.md`），完成后把 `AppContainer.visionEngine` 一行换成真引擎即可。⚠️ 注意 `CameraStreamer` 接口在 X11 中已更新（新增 `status` 流、`start()` 需要 `LifecycleOwner`），喂帧逻辑以 `vision/CameraStreamer.kt` 当前代码为准。

**R2（需求/质量）**：不动代码。文案只改 `strings.xml`；口径定稿走 `docs/指标口径表.md`（4 个待确认点签字后改 `MetricDefs.kt` 常量并升版本号）。

**AI 协作**：仓库根 `AGENTS.md` / `CLAUDE.md` 定义了 AI 工具的协作工作流与边界。

## 硬规则（破坏一条 = 打回 PR）

1. **全 App 只有一个 Activity**；新页面 = 新 Fragment + nav_graph 一行 + strings 一行。
2. 页面状态只从 `AppSessionViewModel` 取；**禁止**在页面里直连数据库或 new 引擎。
3. 计时只用 `MonotonicClock`，禁止 `System.currentTimeMillis()` 参与任何时长计算。
4. 数据记录只走 `Tracker.log()`；报告只从埋点重算，不允许旁路统计。
5. 文案进 `strings.xml`，禁止代码写死中文（R2 唯一入口）。
6. 口径常量只在 `MetricDefs`；**接口协议（VisionSample/CameraStreamer）变更必须 R1+R4 双方在同一 PR 确认**。

## CI

每次 push / PR 自动：构建 APK + 跑全部 **47 个验收单测**（息屏恢复<2s、冷却 100%、口径重算一致、样本<3 降级、断流 3s、休息校验、校准采集等）。
`main` 分支保护：**CI 绿 + 至少 1 人 Approve + 手动合并**，三者缺一不可（含 ADMIN）。

## 已知边界

- 视觉判定仍是 Mock 引擎（R4 接入后自动切换，无需改 UI/报告代码）；
- P10 专注守护只有页面模板（R3 T4-03/T4-05 待接）；P11 人格与主题待 R2 文案；
- 视觉样本按 1 条/秒落盘（`MetricDefs.SAMPLE_PER_SEC`），与 X11 取流 8fps 是两个口径；
- 口径整体 DRAFT_v0.1（`docs/指标口径表.md`），D5 前定稿；冻结前报告数字不得写进对外材料。
