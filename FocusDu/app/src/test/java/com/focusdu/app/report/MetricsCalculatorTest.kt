package com.focusdu.app.report

import com.focusdu.app.core.EventTypes
import com.focusdu.app.core.TrackEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6-02 指标引擎验收的自动化锚点：与埋点重算误差 <1%（按构造成立，重算两次逐字段相等）。
 */
class MetricsCalculatorTest {

    private fun event(
        atMs: Long, type: String, refId: String? = null, metricMs: Long = 0, detail: String? = null,
    ) = TrackEvent(sessionId = 1, atMs = atMs, type = type, refId = refId, metricMs = metricMs, detail = detail)

    private fun fixture() = listOf(
        event(0, EventTypes.FOCUS_START),
        event(300_000, EventTypes.STATE_SPAN, refId = "FOCUS", metricMs = 300_000, detail = "NEAR"),        // 偏近 5min
        event(500_000, EventTypes.STATE_SPAN, refId = "FOCUS", metricMs = 120_000, detail = "HEAD_DOWN"),   // 低头 2min
        event(600_000, EventTypes.REMINDER_SHOWN, refId = "r1"),
        event(640_000, EventTypes.RISK_CLEARED, refId = "r1", metricMs = 40_000),                           // 60s 内恢复 → 改善
        event(700_000, EventTypes.REMINDER_SHOWN, refId = "r2"),
        event(800_000, EventTypes.RISK_CLEARED, refId = "r2", metricMs = 100_000),                          // 超窗 → 不改善
        event(1_200_000, EventTypes.FOCUS_END, metricMs = 1_200_000),                                        // 专注 20min
        event(1_200_001, EventTypes.BREAK_START),
        event(1_300_000, EventTypes.STATE_SPAN, refId = "BREAK", metricMs = 70_000, detail = "AWAY"),       // 起身 70s → 有效休息
        event(1_400_000, EventTypes.BREAK_END, metricMs = 200_000),
    )

    @Test
    fun `各指标按口径正确`() {
        // 20min × 存储采样率1/s = 1200 预期样本；给 1200 → 完整度 100%
        val m = MetricsCalculator.calculate(fixture(), sampleCount = 1_200)

        assertEquals(1_200_000L, m.focusMs)
        assertEquals(420_000L, m.highRiskMs)
        assertEquals(780_000L, m.effectiveStudyMs)
        assertEquals(0.35, m.riskRate, 1e-9)
        assertEquals(300_000L, m.nearMs)
        assertEquals(120_000L, m.headDownMs)
        assertEquals(2, m.reminderCount)
        assertEquals(1, m.improvementCount)
        assertEquals(0.5, m.improvementRate, 1e-9)
        assertEquals(200_000L, m.breakMs)
        assertEquals(1, m.effectiveRestCount)
        assertEquals(1.0, m.completeness, 1e-9)
        assertTrue(m.missingDataFlags.isEmpty())
    }

    @Test
    fun `重算结果与首次完全一致`() {
        val m1 = MetricsCalculator.calculate(fixture(), 1_200)
        val m2 = MetricsCalculator.calculate(fixture().shuffled(), 1_200)   // 乱序重算（内部排序）
        assertEquals(m1, m2)   // 逐字段相等 → 误差 0% < 1%
    }

    @Test
    fun `无数据时给出缺失标记且完整度为0`() {
        val m = MetricsCalculator.calculate(emptyList(), 0)
        assertEquals(0L, m.focusMs)
        assertEquals(0.0, m.completeness, 1e-9)
        assertTrue(m.missingDataFlags.contains("视觉样本缺失"))
        assertTrue(m.missingDataFlags.contains("无专注时长数据"))
        assertEquals(-1.0, m.improvementRate, 1e-9)   // 无提醒 → 显示为「—」
    }

    @Test
    fun `样本不足时完整度按比例且封顶1`() {
        val m = MetricsCalculator.calculate(fixture(), sampleCount = 600)   // 一半样本
        assertEquals(0.5, m.completeness, 1e-9)
    }
}
