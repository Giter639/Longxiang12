# FocusDu · 专注有度 —— 客户端骨架工程（R1 · X02 交付物）

> 任务卡定位：**X02 客户端架构与页面框架**。验收：**D2 起 3 人都能在骨架上加页面**。
> 配套 X01 交付物（分支/PR/Review/版本规范）见 `docs/协作规范.md`。

## 一句话架构

单 Activity + Fragment 导航；引擎（番茄/提醒/报告/视觉接入）全部是进程级单例，
状态用 StateFlow 汇入 `AppSessionViewModel`（统一状态容器）；数据只有一条路 ——
埋点 `Tracker.log()` → Room → 指标引擎重算 → 报告。**没有第二条数据通路。**

```
CameraStreamer(R3实现) → VisionEngine(R4实现) → StateStreamDebouncer(X18防抖+断流兜底)
        → P07 实时小窗 / StateSpanLogger(持续段埋点) / RiskEventWire(演示接线)
        → ReminderEngine(T3-03 分级提醒) → P08 / PomodoroEngine.onSevereRisk(健康优先提前休息)
PomodoroEngine(T3-01/02, 单调时钟+检查点恢复) → 前台服务 → 轮次结束 → ReportGenerator(T6-01)
        → MetricsCalculator(T6-02 埋点重算) → HistoryComparator(T6-03 样本<3不硬出结论) → P12/P13/P14
```

## 快速开始

0. **★ 先决条件：把本工程放到纯英文路径**（如 `C:\dev\FocusDu`）。中文路径下 APK 可以构建，但**单元测试会因 AGP 已知 bug（issuetracker 95744 一族）报 ClassNotFoundException** —— 已实测。全组都从英文路径工作，一天能省掉一堆玄学问题。
1. Android Studio（Koala+，JDK 17+）打开本目录；
2. `local.properties` 写上你的 `sdk.dir`（本仓库已带一份，换机器要改）；
3. `./gradlew :app:assembleDebug` 或直接 Run —— 首次构建用腾讯镜像拉 Gradle 8.9；
4. 跑单测：`./gradlew :app:testDebugUnitTest`（Pomodoro/提醒/口径/对比/断流 5 组验收锚点）；
5. 日常测试：双击仓库根目录的 `启动器.bat` —— 自动拉起模拟器/连真机、点亮屏幕、打开 App（已装则秒开）。

装上就能跑：P05 首页是**页面索引**，P06 开一轮番茄，Mock 视觉引擎会让
P07 出现六态流转 → P08 出现真实提醒决策（含冷却/压制）→ 结束后 P12 出报告 →
P13/P14 出趋势与对比。**全程无相机、无算法也能演示。**

## 目录结构与任务对照

| 任务ID | 模块 | 位置 |
| --- | --- | --- |
| X02 | 单 Activity + 导航 + 统一状态容器 + 埋点接口 + SDK 封装 | `MainActivity` / `res/navigation` / `ui/common/AppSessionViewModel` / `track/Tracker` / `vision/` |
| T3-01 | 三种番茄模式 | `pomodoro/PomodoroMode` |
| T3-02 | 计时与中断恢复（息屏 30min 误差<2s） | `pomodoro/PomodoroEngine` + `PomodoroService`（单调时钟+检查点） |
| T3-03 | 分级提醒引擎（只记录/边缘提示/冷却≥3min/上限3次） | `reminder/ReminderEngine`（验收有单测） |
| T3-04 | 休息页与恢复校验 | `ui/pages/P09RestPage` |
| T7-03 | Room 存储与容量滚动 | `data/db/` + `data/RollingCleaner` |
| X18 | 状态机客户端接入（防抖显示+断流兜底） | `vision/StateStreamDebouncer` |
| T6-01/02/03 | 报告生成 / 指标引擎 / 历史对比 | `report/`（口径 `MetricDefs` **DRAFT_v0.1，D5 前与 R2 定稿**） |
| X00/T2-10 | 算法接口协议 v0 | `vision/protocol/VisionModels` + `docs/接口协议v0.md` |
| X11 | 取流接口（R3 实现） | `vision/CameraStreamer` |

## 三人接入指南（改绑定，不改调用方）

**R4（算法）**：写 `MediaPipeVisionEngine : VisionEngine`（输出 `VisionSample`，字段即协议 v0），
然后只改 `AppContainer.visionEngine` 一行绑定。D3 晚前交不出来，客户端继续用 Mock 不空转。
风险事件源替换：真引擎就绪后用算法事件替换 `RiskEventWire`（骨架演示接线）。

**R3（采集/设备）**：写 `CameraXStreamer : CameraStreamer`（目标 8fps≥30min），改
`AppContainer.cameraStreamer` 绑定；权限引导/拦截页逻辑写在 P01/P10 模板里；
机型参数表放 `docs/` 并在 PR 里链接。

**R2（需求/质量）**：不动代码。文案只改 `res/values/strings.xml`；
口径表定稿 = 改 `report/MetricDefs.kt` 里的常量与注释（走 PR，R1 复核）；
页面清单以 `docs/页面清单.md` 为代码侧镜像，你的"三张清单"仍是唯一权威。

## 硬规则（破坏一条 = 打回 PR）

1. **全 App 只有一个 Activity**；新页面 = 新 Fragment + nav_graph 一行 + strings 一行。
2. 页面状态只从 `AppSessionViewModel` 取；**禁止**在页面里直连数据库或 new 引擎。
3. 计时只用 `MonotonicClock`，禁止 `System.currentTimeMillis()` 参与任何时长计算。
4. 数据记录只走 `Tracker.log()`；报告只从埋点重算，不允许旁路统计。
5. 文案进 `strings.xml`，禁止代码写死中文（R2 唯一入口）。
6. 口径常量只在 `MetricDefs`；改口径必须 PR + 升版本号 + 通知全组（D8 后冻结）。

## 骨架验证记录（D0 交付时）

- `:app:assembleDebug` ✅ 出 APK（`app-debug.apk`，中文/英文路径均验证）；
- `:app:testDebugUnitTest` ✅ **27 个用例全绿**（纯英文路径下验证；中文路径受上述 AGP bug 影响只影响单测，不影响打包）；
- 无相机、无算法依赖：装上 APK 从 P05 索引进 P06 开番茄即可走通全链路演示。

## 已知边界（骨架期如实声明）

- 提醒目前是页内决策列表 + 通知栏计时，弹层/边缘提示样式待 P08 精修（T5-01 文案到位后）；
- 专注守护（P10）只有页面模板，读取前台应用/通知事件由 R3 实现（T4-01/T4-03）；
- 视觉样本落盘按 1 条/秒采样（`MetricDefs.SAMPLE_PER_SEC`），与 X11 的 8fps 取流帧率是两个口径；
- 口径整体为 DRAFT_v0.1，报告数字在口径定稿前**不得**写进任何对对外材料（任务卡 R2 卡点 3）。
