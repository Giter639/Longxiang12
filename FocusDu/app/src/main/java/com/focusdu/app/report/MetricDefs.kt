package com.focusdu.app.report

/**
 * 指标口径表 —— DRAFT v0.1。
 * ★ 任务卡卡点：口径必须在 D5 前与 R2（X04 三张清单）定稿；D8 之后改口径 = 报告全部重算。
 * 任何修改走 PR，改完升版本号并通知 R2 同步口径表。
 */
object MetricDefs {
    const val VERSION = "DRAFT_v0.1"

    /** 高风险状态集合（STATE_SPAN 的 detail 命中即计入高风险时长） */
    val HIGH_RISK_STATES = setOf("NEAR", "HEAD_DOWN", "DROWSY", "OFF_SCREEN", "AWAY", "LIGHT_ANOMALY")

    /** 久坐单独成桶（构成表里单列「久坐」） */
    const val SEDENTARY_STATE = "SEDENTARY"

    /** 有效休息：休息段内离屏/起身持续 ≥60s 算一次有效休息 */
    const val EFFECTIVE_REST_MIN_MS = 60_000L
    const val REST_AWAY_STATES_DESC = "AWAY,OFF_SCREEN"
    val REST_AWAY_STATES = setOf("AWAY", "OFF_SCREEN")

    /** 提醒改善：提醒后 60s 内风险解除算改善（docx「干预效果」） */
    const val IMPROVE_WINDOW_MS = 60_000L

    /**
     * 数据完整度基准：视觉样本【存储采样率】= 1 条/秒（VisionSampleRecorder 按此落盘）。
     * 注意与 X11 取流帧率（8fps）区分：取流帧率是算法输入密度，这里是报告口径的采样密度。
     */
    const val SAMPLE_PER_SEC = 1

    /**
     * 口径草案：
     * - 专注时长 = Σ FOCUS_END.metricMs（不含暂停）
     * - 有效学习时长 = 专注时长 − 高风险时长
     * - 风险率 = 高风险时长 / 专注时长
     * - 提醒改善率 = 60s 内恢复的提醒数 / 提醒总数（无提醒时记 NaN，报告展示「—」）
     * - 数据完整度 = 视觉样本数 / (专注秒数 × 存储采样率 1/s)，封顶 1.0
     */
}
