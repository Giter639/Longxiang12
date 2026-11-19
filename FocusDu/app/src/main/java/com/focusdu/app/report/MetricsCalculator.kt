package com.focusdu.app.report

import com.focusdu.app.core.EventTypes
import com.focusdu.app.core.TrackEvent
import kotlin.math.roundToInt

/** 一轮的全部指标（口径见 MetricDefs，DRAFT v0.1） */
data class RoundMetrics(
    val focusMs: Long,
    val effectiveStudyMs: Long,
    val highRiskMs: Long,
    val riskRate: Double,
    // 风险构成
    val nearMs: Long,
    val headDownMs: Long,
    val drowsyMs: Long,
    val lightAnomalyMs: Long,
    val sedentaryMs: Long,
    // 干预效果
    val reminderCount: Int,
    val improvementCount: Int,
    val improvementRate: Double,        // 无提醒时 = -1.0 表示「—」
    // 休息恢复
    val breakMs: Long,
    val effectiveRestCount: Int,
    val effectiveRestMs: Long,
    // 数据可信度
    val sampleCount: Int,
    val expectedSamples: Int,
    val completeness: Double,
    val missingDataFlags: List<String>,
)

/**
 * T6-02 指标计算引擎：纯函数，只吃埋点事件流。
 * 「与埋点重算误差 <1%」按构造成立 —— 报告就是从同一份埋点算出来的；
 * 实时小窗展示的中间值也用同一个函数重入计算，不存在两套口径。
 */
object MetricsCalculator {

    fun calculate(events: List<TrackEvent>, sampleCount: Int): RoundMetrics {
        val ordered = events.sortedBy { it.atMs }
        var focusMs = 0L
        var nearMs = 0L
        var headDownMs = 0L
        var drowsyMs = 0L
        var lightAnomalyMs = 0L
        var sedentaryMs = 0L
        var highRiskMs = 0L
        var breakMs = 0L
        var effectiveRestCount = 0
        var effectiveRestMs = 0L
        var reminderCount = 0
        var improvementCount = 0
        val missing = mutableListOf<String>()

        for (e in ordered) {
            when (e.type) {
                EventTypes.FOCUS_END -> focusMs += e.metricMs
                EventTypes.GIVE_UP -> focusMs += e.metricMs   // 放弃轮次已学的时长同样计入专注
                EventTypes.BREAK_END -> breakMs += e.metricMs
                EventTypes.STATE_SPAN -> {
                    val state = e.detail ?: continue
                    when (e.refId) {
                        "FOCUS" -> {
                            if (state in MetricDefs.HIGH_RISK_STATES) highRiskMs += e.metricMs
                            when (state) {
                                "NEAR" -> nearMs += e.metricMs
                                "HEAD_DOWN" -> headDownMs += e.metricMs
                                "DROWSY" -> drowsyMs += e.metricMs
                                "LIGHT_ANOMALY" -> lightAnomalyMs += e.metricMs
                                MetricDefs.SEDENTARY_STATE -> sedentaryMs += e.metricMs
                            }
                        }
                        "BREAK" -> {
                            if (state in MetricDefs.REST_AWAY_STATES && e.metricMs >= MetricDefs.EFFECTIVE_REST_MIN_MS) {
                                effectiveRestCount++
                                effectiveRestMs += e.metricMs
                            }
                        }
                    }
                }
                EventTypes.REMINDER_SHOWN -> reminderCount++
                EventTypes.RISK_CLEARED -> if (e.metricMs in 0..MetricDefs.IMPROVE_WINDOW_MS) improvementCount++
                EventTypes.REST_VALIDATED -> effectiveRestCount++
                EventTypes.PERMISSION_STATE -> if (e.detail?.endsWith("拒绝") == true) missing += e.detail!!
            }
        }

        // 休息段内起身（离屏/离开）也是有效休息证据，前面已按 BREAK 段统计；补上 FOCUS 段的 SEDENTARY 无需计入休息
        val effectiveStudy = (focusMs - highRiskMs).coerceAtLeast(0)
        val riskRate = if (focusMs > 0) highRiskMs.toDouble() / focusMs else 0.0
        val improvementRate = if (reminderCount > 0) improvementCount.toDouble() / reminderCount else -1.0

        val expected = (focusMs / 1000 * MetricDefs.SAMPLE_PER_SEC).toInt()
        val completeness = if (expected > 0) (sampleCount.toDouble() / expected).coerceIn(0.0, 1.0) else 0.0
        if (sampleCount == 0) missing += "视觉样本缺失"
        if (focusMs == 0L) missing += "无专注时长数据"

        return RoundMetrics(
            focusMs = focusMs,
            effectiveStudyMs = effectiveStudy,
            highRiskMs = highRiskMs,
            riskRate = riskRate,
            nearMs = nearMs,
            headDownMs = headDownMs,
            drowsyMs = drowsyMs,
            lightAnomalyMs = lightAnomalyMs,
            sedentaryMs = sedentaryMs,
            reminderCount = reminderCount,
            improvementCount = improvementCount,
            improvementRate = improvementRate,
            breakMs = breakMs,
            effectiveRestCount = effectiveRestCount,
            effectiveRestMs = effectiveRestMs,
            sampleCount = sampleCount,
            expectedSamples = expected,
            completeness = (completeness * 100).roundToInt() / 100.0,
            missingDataFlags = missing.distinct(),
        )
    }

    /** 报告页/文档用的分钟显示 */
    fun toMinutes(ms: Long): Long = ms / 60_000
}
