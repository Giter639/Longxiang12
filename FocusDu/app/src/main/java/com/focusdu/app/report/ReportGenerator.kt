package com.focusdu.app.report

import com.focusdu.app.core.EventTypes
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.core.TrackEvent
import com.focusdu.app.data.RollingCleaner
import com.focusdu.app.data.db.AppDatabase
import com.focusdu.app.data.db.ReportEntity
import com.focusdu.app.track.Tracker
import kotlin.math.roundToInt

/**
 * T6-01 番茄结束自动生成报告：聚合本轮埋点 → 本地生成 → 落库 → 清理原始视觉样本。
 * 离线全流程可用（无任何网络代码）；验收「结束 3s 内出报告」由 REPORT_GENERATED
 * 埋点的 metricMs 记录耗时，D9 联调时核对。
 */
class ReportGenerator(
    private val db: AppDatabase,
    private val tracker: Tracker,
    private val clock: MonotonicClock,
    private val cleaner: RollingCleaner,
) {
    suspend fun generate(sessionId: Long): ReportEntity? {
        val t0 = clock.nowMs()
        val events = db.trackEventDao().bySession(sessionId)
        if (events.isEmpty()) return null
        val sampleCount = db.visionSampleDao().countForSession(sessionId)

        val m = MetricsCalculator.calculate(events.map {
            TrackEvent(it.sessionId, it.atMs, it.type, it.refId, it.metricMs, it.detail)
        }, sampleCount)

        val entity = ReportEntity(
            sessionId = sessionId,
            generatedAtWall = System.currentTimeMillis(),
            summaryText = buildSummary(m),
            focusMs = m.focusMs,
            effectiveMs = m.effectiveStudyMs,
            riskRate = m.riskRate,
            reminderCount = m.reminderCount,
            improvementRate = m.improvementRate,
            effectiveRestCount = m.effectiveRestCount,
            completeness = m.completeness,
        )
        db.reportDao().upsert(entity)

        tracker.log(
            TrackEvent(sessionId, clock.nowMs(), EventTypes.REPORT_GENERATED, metricMs = clock.nowMs() - t0)
        )
        cleaner.onReportDone(sessionId)   // 报告落库后原始视觉样本即释放（T7-03 容量策略）
        return entity
    }

    suspend fun latest(): ReportEntity? = db.reportDao().recent(1).firstOrNull()

    suspend fun recent(n: Int): List<ReportEntity> = db.reportDao().recent(n)

    /** 时长展示：不足 1 分钟也可见（骨架演示轮多为几十秒） */
    private fun fmtMin(ms: Long): String = "${ms / 60_000}分${(ms % 60_000) / 1000}秒"

    private fun buildSummary(m: RoundMetrics): String = buildString {
        appendLine("口径版本：${MetricDefs.VERSION}")
        appendLine("专注时长：${fmtMin(m.focusMs)}｜有效学习：${fmtMin(m.effectiveStudyMs)}")
        appendLine("高风险占比：${(m.riskRate * 100).roundToInt()}%（很近 ${fmtMin(m.nearMs)} · 低头 ${fmtMin(m.headDownMs)} · 困倦 ${fmtMin(m.drowsyMs)} · 光照异常 ${fmtMin(m.lightAnomalyMs)} · 久坐 ${fmtMin(m.sedentaryMs)}）")
        if (m.improvementRate < 0) {
            appendLine("提醒：本轮无提醒触发")
        } else {
            appendLine("提醒：共 ${m.reminderCount} 次，60s 内恢复 ${m.improvementCount} 次（改善率 ${(m.improvementRate * 100).roundToInt()}%）")
        }
        appendLine("休息：共 ${fmtMin(m.breakMs)}，有效休息 ${m.effectiveRestCount} 次（累计 ${fmtMin(m.effectiveRestMs)}）")
        appendLine("数据可信度：样本 ${m.sampleCount}/${m.expectedSamples}，完整度 ${(m.completeness * 100).roundToInt()}%")
        if (m.missingDataFlags.isNotEmpty()) {
            appendLine("缺失项：${m.missingDataFlags.joinToString("、")}")
        }
    }
}
