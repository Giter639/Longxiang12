package com.focusdu.app.report

import com.focusdu.app.data.db.ReportEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6-03 验收的自动化锚点：样本 <3 次提示数据不足，不硬出结论；近 7 次取中位数。
 */
class HistoryComparatorTest {

    private fun report(
        sessionId: Long,
        effectiveMin: Long,
        riskRate: Double,
        improvementRate: Double = 0.8,
        restCount: Int = 1,
        generatedAt: Long,
    ) = ReportEntity(
        sessionId = sessionId, generatedAtWall = generatedAt, summaryText = "",
        focusMs = effectiveMin * 60_000, effectiveMs = effectiveMin * 60_000,
        riskRate = riskRate, reminderCount = 2, improvementRate = improvementRate,
        effectiveRestCount = restCount, completeness = 1.0,
    )

    @Test
    fun `样本少于3次 不硬出结论`() {
        val current = report(3, 30, 0.2, generatedAt = 3_000L)
        val result = HistoryComparator.compare(current, listOf(report(1, 25, 0.3, generatedAt = 1_000L)))
        assertFalse(result.ready)
        assertTrue(result.reason.contains("少于 3 次"))
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `满3次可对比 且近7次中位数正确`() {
        val current = report(10, 30, 0.2, generatedAt = 10_000L)
        // 7 次历史：有效学习 10..16 分钟 → 中位 13
        val history = (1..7L).map { report(it, effectiveMin = 9 + it, riskRate = 0.4, generatedAt = it * 1_000L) }
        val result = HistoryComparator.compare(current, history)

        assertTrue(result.ready)
        val effectiveRow = result.rows.first { it.name.contains("有效学习") }
        assertEquals(30.0, effectiveRow.current, 1e-9)
        assertEquals(16.0, effectiveRow.previous!!, 1e-9)          // 上一轮 = 时间最近一次
        assertEquals(13.0, effectiveRow.medianLast7!!, 1e-9)       // 10..16 中位 = 13
        assertEquals(-1, HistoryComparator.direction(effectiveRow)) // 学习时长上升 → 变好
    }

    @Test
    fun `超过7次只取最近7次`() {
        val current = report(20, 30, 0.2, generatedAt = 20_000L)
        val history = (1..9L).map { report(it, effectiveMin = 100 + it, riskRate = 0.3, generatedAt = it * 1_000L) }
        val result = HistoryComparator.compare(current, history)
        val row = result.rows.first { it.name.contains("有效学习") }
        // 最近 7 次 = 103..109 → 中位 106
        assertEquals(106.0, row.medianLast7!!, 1e-9)
    }

    @Test
    fun `风险率上升判为变差`() {
        val current = report(4, 20, 0.5, generatedAt = 4_000L)
        val history = listOf(
            report(1, 20, 0.2, generatedAt = 1_000L),
            report(2, 20, 0.3, generatedAt = 2_000L),
        )
        val result = HistoryComparator.compare(current, history)
        assertTrue(result.ready)
        val riskRow = result.rows.first { it.name.contains("风险") }
        assertEquals(1, HistoryComparator.direction(riskRow))   // 50% vs 25% 中位 → 上升 → 变差
    }
}
