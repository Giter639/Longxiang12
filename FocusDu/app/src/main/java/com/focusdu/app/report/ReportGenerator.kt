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

    /** 报告结构化区块（P12 卡片化渲染用），对齐 docx 报告内容表七区块 */
    data class ReportSection(val title: String, val lines: List<String>)

    suspend fun buildSections(sessionId: Long): List<ReportSection> {
        val events = db.trackEventDao().bySession(sessionId).map {
            TrackEvent(it.sessionId, it.atMs, it.type, it.refId, it.metricMs, it.detail)
        }
        val sampleCount = db.visionSampleDao().countForSession(sessionId)
        val m = MetricsCalculator.calculate(events, sampleCount)
        val sections = mutableListOf<ReportSection>()

        sections += ReportSection("本轮摘要", listOf(
            "专注时长 ${fmtMin(m.focusMs)}",
            "有效学习时长 ${fmtMin(m.effectiveStudyMs)}",
            "高风险时长 ${fmtMin(m.highRiskMs)}",
            "提醒 ${m.reminderCount} 次",
        ))

        sections += ReportSection("风险构成（占比 ${(m.riskRate * 100).roundToInt()}%）", listOf(
            "近距离用眼 ${fmtMin(m.nearMs)}",
            "持续低头 ${fmtMin(m.headDownMs)}",
            "困倦 ${fmtMin(m.drowsyMs)}",
            "光照异常 ${fmtMin(m.lightAnomalyMs)}",
            "久坐 ${fmtMin(m.sedentaryMs)}",
        ))

        sections += ReportSection("干预效果", if (m.reminderCount == 0) listOf(
            "本轮无提醒触发（风险未持续达到阈值或已被压制）"
        ) else listOf(
            "提醒 ${m.reminderCount} 次",
            "60 秒内恢复 ${m.improvementCount} 次（改善率 ${(m.improvementRate * 100).roundToInt()}%）",
        ))

        val restCheck = tracker.sessionEvents(sessionId)
            .lastOrNull { it.type == EventTypes.REST_VALIDATED }?.detail
        sections += ReportSection("休息恢复", buildList {
            add("休息 ${fmtMin(m.breakMs)}，有效休息 ${m.effectiveRestCount} 次")
            restCheck?.let { add("恢复校验：$it") }
        })

        val current = db.reportDao().recent(10).firstOrNull { it.sessionId == sessionId }
        if (current != null) {
            val history = db.reportDao().recent(10).filter { it.sessionId != sessionId }
            val cmp = HistoryComparator.compare(current, history)
            sections += ReportSection("历史对比", if (cmp.ready) cmp.rows.map { r ->
                val ref = r.medianLast7
                "${r.name}: 本轮 ${fmtVal(r.current)}（近7中位 ${ref?.let { fmtVal(it) } ?: "—"}）"
            } else listOf(cmp.reason))
        }

        sections += ReportSection("数据可信度", buildList {
            add("有效样本 ${m.sampleCount}/${m.expectedSamples}，完整度 ${(m.completeness * 100).roundToInt()}%")
            m.missingDataFlags.forEach { add("缺失：$it") }
        })

        sections += ReportSection("下一步建议", ReportAdvisor.advise(m))
        return sections
    }

    private fun fmtVal(v: Double): String =
        if (v == v.toLong().toDouble()) "${v.toLong()}" else "%.1f".format(v)

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
        val tips = ReportAdvisor.advise(m)
        appendLine("下一步建议：${tips.joinToString("；") { it }}")
        if (m.missingDataFlags.isNotEmpty()) {
            appendLine("缺失项：${m.missingDataFlags.joinToString("、")}")
        }
    }
}
