package com.focusdu.app.report

import com.focusdu.app.data.db.ReportEntity
import kotlin.math.abs

/**
 * T6-03 与上一轮及近 7 次对比。
 * 验收：样本 <3 次提示数据不足，不硬出结论。
 */
object HistoryComparator {

    data class MetricRow(
        val name: String,
        val current: Double,
        val previous: Double?,
        val medianLast7: Double?,
        val betterWhenLower: Boolean,
    )

    data class ComparisonResult(
        val ready: Boolean,        // false = 样本不足，UI 只展示本轮数据
        val reason: String,
        val rows: List<MetricRow>,
    )

    /** @param history 除去本轮之外的历史报告（按时间倒序传入亦可，内部会处理） */
    fun compare(current: ReportEntity, history: List<ReportEntity>): ComparisonResult {
        val total = 1 + history.size
        if (total < 3) {
            return ComparisonResult(
                ready = false,
                reason = "完整轮次共 $total 次，少于 3 次 —— 按口径约定不硬出对比结论，再完成 ${3 - total} 轮后开启趋势对比",
                rows = emptyList(),
            )
        }
        val sorted = history.sortedBy { it.generatedAtWall }
        val previous = sorted.last()
        val last7 = sorted.takeLast(7)

        return ComparisonResult(
            ready = true,
            reason = "对比对象：上一轮与近 ${last7.size} 次中位数",
            rows = listOf(
                row("有效学习时长(分)", current.effectiveMs / 60_000.0, previous, last7) { it.effectiveMs / 60_000.0 },
                row("高风险占比(%)", current.riskRate * 100, previous, last7) { it.riskRate * 100 },
                row("提醒改善率(%)", pct(current.improvementRate), previous, last7) { pct(it.improvementRate) },
                row("有效休息(次)", current.effectiveRestCount.toDouble(), previous, last7) { it.effectiveRestCount.toDouble() },
            ),
        )
    }

    private fun row(
        name: String,
        current: Double,
        previous: ReportEntity,
        last7: List<ReportEntity>,
        pick: (ReportEntity) -> Double,
    ) = MetricRow(
        name = name,
        current = current,
        previous = pick(previous),
        medianLast7 = median(last7.map(pick)),
        betterWhenLower = name.contains("占比"),
    )

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun pct(rate: Double): Double = if (rate < 0) 0.0 else rate * 100

    /** 方向判定：>0 = 变差，<0 = 变好，0 = 持平（容差 5%） */
    fun direction(row: MetricRow): Int {
        val reference = row.medianLast7 ?: row.previous ?: return 0
        if (abs(reference) < 1e-9) return 0
        val delta = (row.current - reference) / reference
        if (abs(delta) <= 0.05) return 0
        val worse = if (row.betterWhenLower) delta > 0 else delta < 0
        return if (worse) 1 else -1
    }
}
