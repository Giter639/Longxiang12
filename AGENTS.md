# AGENTS.md —— AI 协作工作流（每次开工必读）

四人小组（R1 软件工程 / R2 需求质量 / R3 采集设备 / R4 算法）协作开发「专注有度」App。
**AI 编码助手必须严格执行以下三步流程，不许跳步：开工读卡 → 按规范干活 → 完工写卡。**

## 第 1 步：开工前，先读任务卡

读仓库根目录 `任务卡.xlsx`：找到自己的 sheet（R1–R4 各一个），只看自己的任务行，
重点三列：**完成情况（L 列）、验收标准（J 列）、依赖/卡点（K 列）**。

- 状态为「阻塞」的任务：先停手，当天在群里说清卡在哪、需要谁，等解除再继续；
- 「依赖/卡点」列写明的前置任务没完成时，不要提前开工；
- 验收标准是量化指标，做完必须自测达标才算完成。

## 第 2 步：干活时，遵守工程硬规则

- 架构与 6 条硬规则见 `FocusDu/README.md`（单 Activity、状态只从 AppSessionViewModel 取、
  计时只用 MonotonicClock、数据只走 Tracker.log()、文案进 strings.xml、口径只改 MetricDefs）；
- 分支/PR/发版规范见 `FocusDu/docs/协作规范.md`；
- 别人的领域不要动：R2 只改文案与文档，R3 只动采集/设备层，R4 只动算法引擎，
  客户端接入点只改 `AppContainer` 的一行绑定。

## 第 3 步：完工后，立即更新任务卡

把对应任务的「完成情况」列改为：**未开始 / 进行中 / 已完成 / 阻塞**（只允许这四个值），
连同代码一起提交 PR；PR 模板的「验收标准」一栏必须引用任务卡里的量化指标。
**不写卡 = 这条任务等于没做。**

### 任务卡读写方法（python + openpyxl）

```python
import openpyxl
wb = openpyxl.load_workbook("任务卡.xlsx")
ws = wb["一 R1 软件工程"]  # R2/R3/R4 对应「二 R2 机械制造」「三 R3 电子信息」「四 R4 人工智能」
# 表头在第 3 行，数据从第 4 行起：A序号 | B任务ID | C类型 | D任务名称 | E具体要做什么
#                            | F涉及页面 | G时间 | H人天 | I交付物 | J验收标准 | K依赖/卡点 | L完成情况

# 读：打印自己所有任务的状态
for row in ws.iter_rows(min_row=4):
    if row[0].value and row[1].value:  # 跳过空行/汇总行
        print(row[1].value, row[3].value, "→", row[11].value)

# 写：按任务 ID 改状态
for row in ws.iter_rows(min_row=4):
    if row[1].value == "T3-03":
        row[11].value = "已完成"
wb.save("任务卡.xlsx")
```

## 提交规范（摘要，详见 FocusDu/docs/协作规范.md）

- 从 `dev` 切分支：`feature/<任务ID>-<短名>`；commit 格式 `<任务ID>: <做了什么>`；
- PR 到 `dev`，CI 的 `build-test` 必须绿，至少 1 人 Review——**任意有写权限的队友都可以批准，PR 作者除外**（涉及口径/协议的改动仍需 R1+R2 或 R1+R4 双方确认）；
- `dev` 每天收工前合入 `main` 出当日构建；`main` 只接受 `dev` 的 PR。

## 文档地图

| 文档 | 用途 |
| --- | --- |
| `任务卡.xlsx`（仓库根目录） | 45 条任务、四人分工、每日进度的唯一权威 |
| `FocusDu/README.md` | 架构、硬规则、三人接入指南 |
| `FocusDu/docs/协作规范.md` | 分支模型、PR/Review 清单、发版 |
| `FocusDu/docs/接口协议v0.md` | 算法↔客户端接口协议（R4 对接） |
| `FocusDu/docs/页面清单.md` | 18 页面状态镜像 |
| `FocusDu/docs/指标口径表.md` | 指标口径 DRAFT_v0.1（D5 前定稿） |
