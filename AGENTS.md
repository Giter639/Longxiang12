# AGENTS.md —— AI 协作工作流与工程手册（每次开工必读，一站式）

四人小组（R1 软件工程 / R2 需求质量 / R3 采集设备 / R4 算法）协作开发「专注有度」App。
**AI 编码助手必须严格执行以下三步流程，不许跳步：开工读卡 → 按规范干活 → 完工写卡。**
本文件是唯一的完整手册：所有技术约束、接入细节、踩坑记录都内联在此，不要求再读其他文档。

## 第 1 步：开工前，先读任务卡

读仓库根目录 `任务卡.xlsx`：找到自己的 sheet（R1–R4 各一个），只看自己的任务行，
重点三列：**完成情况（L 列）、验收标准（J 列）、依赖/卡点（K 列）**。

- 状态为「阻塞」的任务：先停手，当天在群里说清卡在哪、需要谁，等解除再继续；
- 「依赖/卡点」列写明的前置任务没完成时，不要提前开工；
- 验收标准是量化指标，做完必须自测达标才算完成。

### 任务卡读写方法（python + openpyxl）

```python
import openpyxl
wb = openpyxl.load_workbook("任务卡.xlsx")
ws = wb["一 R1 软件工程"]  # R2/R3/R4 对应「二 R2 机械制造」「三 R3 电子信息」「四 R4 人工智能」
# 表头在第 3 行，数据从第 4 行起：A序号 | B任务ID | C类型 | D任务名称 | E具体要做什么
#                            | F涉及页面 | G时间 | H人天 | I交付物 | J验收标准 | K依赖/卡点 | L完成情况

# 读：打印自己所有任务的状态
for row in ws.iter_rows(min_row=4):
    if row[0].value and row[1].value:
        print(row[1].value, row[3].value, "→", row[11].value)

# 写：按任务 ID 改状态
for row in ws.iter_rows(min_row=4):
    if row[1].value == "T3-03":
        row[11].value = "已完成"
wb.save("任务卡.xlsx")
```

## 第 2 步：干活时，工程约束（内联全文，必须逐条遵守）

### 架构（一句话）

单 Activity + Fragment 导航（`res/navigation/nav_graph.xml`，页面 P01–P14）；
引擎（番茄/提醒/报告/视觉接入）是进程级单例，集中在 `FocusDu/app/src/main/java/com/focusdu/app/AppContainer.kt`；
状态用 StateFlow 汇入 `ui/common/AppSessionViewModel`（统一状态容器）；数据只有一条路：
埋点 `Tracker.log()` → Room → `MetricsCalculator` 重算 → 报告。**没有第二条数据通路。**

### 六条硬规则（破坏一条 = PR 被打回）

1. **全 App 只有一个 Activity**（`MainActivity`）；新页面 = 新 Fragment + nav_graph 一行 + strings 一行。
2. 页面状态只从 `AppSessionViewModel` 取（activityViewModels）；**禁止**在页面里直连数据库或 new 引擎。
3. 计时只用 `core/MonotonicClock`（SystemClock.elapsedRealtime），**禁止** `System.currentTimeMillis()` 参与任何时长计算。
4. 数据记录只走 `Tracker.log()`；报告只从埋点重算（`MetricsCalculator` 是纯函数），不允许旁路统计。
5. 文案进 `res/values/strings.xml`，禁止代码写死中文（R2 唯一入口）。
6. 口径常量只在 `report/MetricDefs.kt`；**口径改动必须 R1+R2 双方在同一 PR 确认**；接口协议（VisionSample/CameraStreamer）变更由 **R4 定稿**（R1 已于 2026-11-22 豁免双签），走正常 PR 流程即可。

### 各角色接入点与细节（只动自己的领域）

**R3（采集/设备）**：
- ✅ 已完成：`vision/CameraStreamer.kt` 内的 `CameraXStreamer`（8fps 取流，已绑定 AppContainer）、`vision/CalibrationSession.kt`（T1-02 校准采集 + T1-03 质量校验，P03 页已接）；
- ✅ 已完成（X23）：`imu/ble/BleImuEngine` BLE 取流（扫描/GATT/断连重连，内部组合 X24 判定层）+ `ImuFrameParser` 帧解析 + P15 设备绑定页，已绑定 AppContainer（无蓝牙自动回退 Mock）；真机验收需烧录 X25 固件（firmware README）；
- ✅ 代码已接入：P10 权限、前台应用检测、悬浮窗软拦截和紧急放行（`guard/`）；真机延迟和紧急功能仍需按任务卡验证；
- 🔲 待真机：X12/X14 性能、兼容与现场验收；
- 机型参数表在 `FocusDu/docs/R3机型适配与验证.md`。

**R4（算法）**：
- ✅ 已完成：`vision/MediaPipeVisionEngine`（FaceLandmarker LIVE_STREAM + NV21 解码 + 判定层调度）、
  `vision/FaceFeatureMapper`（纯映射，单测锚定欧拉角往返/faceScale/blendshape/亮度）、模型资产 `assets/face_landmarker.task`、
  判定层 `vision/algo/`（基线/距离/姿态/久坐/光照/离屏/眨眼/困倦/分级/六态融合）；
- **MediaPipeVisionEngine 已是默认绑定**；`AppContainer` 保留一行切换 Mock 的注释入口；
- 真机联调时：验证 `setBaseline` 注入、真机标定 `FaceFeatureMapper` 的 PITCH/YAW/FORWARD 符号常量，并补齐精度报告；
- ⚠️ 肩线检测降级：FaceLandmarker 无肩部关键点，`shoulderTiltDeg` 恒 0，T2-02 肩线子项待 MediaPipe Pose 增强；
- ✅ 已完成（X24/X26）：`imu/ImuStateClassifier`（IMU 规则状态机，五态判定）+ `imu/VisionImuArbiter`（视觉×IMU 主备切换仲裁）——
  双源经仲裁输出单一状态流喂 StateStreamDebouncer，埋点/提醒无双计；视觉断流/遮挡时 IMU 兜底（规则与参数见 docs/接口协议IMU-v0.md 第 6/8 节）；
- 算法事件源（PostureDetector.activeEvent / SittingMonitor.sedentaryEvent）就绪后，用事件流替换 `reminder/RiskEventWire.kt`（当前是骨架演示接线）。

**R2（需求/质量）**：
- 文案只改 `res/values/strings.xml`（三套人格文案 T5-01、引导 T7-01 都在此）；
- 口径定稿走 `FocusDu/docs/指标口径表.md`（4 个待确认点签字后改 `MetricDefs.kt` 常量并升版本号）；
- 不写代码；发现代码问题开 Issue 或在群里说，不要自己改。

### 测试要求（CI 是硬闸门）

- 改任何代码后必须本地跑：`cd FocusDu && ./gradlew :app:testDebugUnitTest`，当前 **106 个测试全绿**才算完成；
- 测试是验收锚点：`PomodoroEngineTest`（息屏恢复<2s）、`ReminderEngineTest`（冷却/上限）、`MetricsCalculatorTest`（口径重算）、`RestValidatorTest`、`ReportAdvisorTest`、`CalibrationSessionTest` 等——改引擎行为必须同步更新/新增测试；
- CI（`.github/workflows/ci.yml`）在每个 PR 上跑同一套；分支保护要求 **CI 绿 + 至少 1 人 Approve + 手动合并**，三者缺一不可（对 ADMIN 同样生效）。

### 环境注意事项

- **签名口径（X06 补充，2026-11-28）**：发版/给队友的 APK 一律 **release 签名**（CN=FocusDu Longxiang）；debug 密钥每台机器不同，debug 包覆盖别人安装会报「签名不一致」。CI 从 Actions secrets（`ANDROID_KEYSTORE_B64` 等 4 个）取签名材料，本机材料在 `FocusDu/keystore/`（不入库）；**debug 包只本机调试用，勿发队友**。

- **仓库必须克隆到纯英文路径**：中文路径下单元测试会因 AGP 已知 bug 报 ClassNotFoundException（APK 打包不受影响）；
- `FocusDu/local.properties` 每台机器自己配 `sdk.dir`（已 gitignore）；
- 双击根目录 `启动器.bat` 一键构建、拉起 `PixelTest`/真机、安装最新 APK 并打开 App；它是 ASCII 批处理入口，实际逻辑在 `FocusDu/tools/launch_focusdu.ps1`；
- `settings.gradle.kts` 有 `isCi` 切换：CI 环境直连官方源（runner 访问阿里云会超时），本地走阿里云镜像优先——改动仓库顺序要同时验证两种环境；
- 首次跑测试前确认 JDK 17+（`java -version`）。

## 第 3 步：完工后，立即更新任务卡

把对应任务的「完成情况」列改为：**未开始 / 进行中 / 已完成 / 阻塞**（只允许这四个值），
连同代码一起提交 PR；PR 模板的「验收标准」一栏必须引用任务卡里的量化指标。
**不写卡 = 这条任务等于没做。**

## 提交规范（现实流程，务必照做）

- **一任务一分支一 PR**：从最新 `main` 切 `git checkout -b <类型>/<任务ID>-<短名>`（如 `feature/X11-camera-capture`、`docs/update-readme`）；
- commit 格式：`<任务ID>: <做了什么>`；
- **PR 提交到 `main`**（不要开到 dev——dev 分支已弃用日常流程）；
- PR 必须过三关：CI 绿 → 至少 1 人 Approve → 手动点 Merge；
- 大改动拆成小 PR；纯文档改动也走 PR（CI 只跑构建测试，很快）。

## 踩坑记录（真实发生过，AI 不要重踩）

1. **workflow 文件必须存在于默认分支（main）才会被触发**——只在 feature 分支加 yml 永远不跑；引导进 main 时需临时解分支保护，推完立即恢复；
2. GitHub runner（美国）访问 `maven.aliyun.com` 会超时，Gradle 遇网络错误**不会** failover 到后续仓库——所以 settings.gradle.kts 必须保留 `isCi` 切换；
3. Kotlin DSL 的 `pluginManagement {}` 块独立求值，块外声明的变量在块内不可见（isCi 要在块内声明）；
4. 中文路径 + AGP：APK 能打、单测类加载报 ClassNotFoundException；
5. Windows 批处理：含中文的 `.bat` 必须 **GBK 编码 + CRLF 换行**（UTF-8 会把注释解析成命令）；
6. Git Bash 的路径转换（MSYS）会破坏 adb 参数和 `git show 分支:路径` 的冒号语法——脚本里设 `MSYS_NO_PATHCONV=1`；
7. Kotlin DSL `pluginManagement` 独立求值（同 3）；Room/依赖卡住先 `./gradlew clean` 再怀疑代码——**增量编译可能给出旧字节码**。

## 文档地图

| 文档 | 用途 |
| --- | --- |
| `任务卡.xlsx`（仓库根） | 45 条任务、四人分工、每日进度的唯一权威 |
| `FocusDu/README.md` | 架构图、任务对照表、状态总览（人类看的入口） |
| `FocusDu/docs/协作规范.md` | 分支模型、PR/Review 清单、发版节奏 |
| `FocusDu/docs/接口协议v0.md` | 算法↔客户端协议（R4 对接依据） |
| `FocusDu/docs/接口协议IMU-v0.md` | IMU↔客户端协议（X22/X25 定稿，X23 对接依据；BLE 帧表以固件 README 为准） |
| `FocusDu/docs/页面清单.md` | P01–P14 页面状态镜像 |
| `FocusDu/docs/指标口径表.md` | 指标口径 DRAFT_v0.1（4 个待确认点，D5 定稿） |
| `FocusDu/docs/R3机型适配与验证.md` | 机型参数与采集验证记录 |
