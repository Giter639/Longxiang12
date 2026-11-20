package com.focusdu.app.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6-01「下一步建议」规则生成器的自动化锚点：建议可解释、可触发、不越界（≤3 条） */
class ReportAdvisorTest {

    private fun metrics(
        focusMs: Long = 25 * 60_000L,
        riskRate: Double = 0.1,
        nearMs: Long = 0, headDownMs: Long = 0, drowsyMs: Long = 0, lightAnomalyMs: Long = 0,
        reminderCount: Int = 0, improvementRate: Double = -1.0,
        breakMs: Long = 0, effectiveRestCount: Int = 0,
        completeness: Double = 1.0,
    ) = RoundMetrics(
        focusMs = focusMs, effectiveStudyMs = focusMs, highRiskMs = 0, riskRate = riskRate,
        nearMs = nearMs, headDownMs = headDownMs, drowsyMs = drowsyMs,
        lightAnomalyMs = lightAnomalyMs, sedentaryMs = 0,
        reminderCount = reminderCount, improvementCount = 0, improvementRate = improvementRate,
        breakMs = breakMs, effectiveRestCount = effectiveRestCount, effectiveRestMs = 0,
        sampleCount = 100, expectedSamples = 100, completeness = completeness,
        missingDataFlags = emptyList(),
    )

    @Test
    fun `高风险占比过高 建议缩短番茄`() {
        val tips = ReportAdvisor.advise(metrics(riskRate = 0.6))
        assertTrue(tips.any { it.contains("缩短") || it.contains("提前休息") })
    }

    @Test
    fun `低头占比最高 给出姿态建议`() {
        val tips = ReportAdvisor.advise(metrics(riskRate = 0.35, headDownMs = 8 * 60_000L))
        assertTrue(tips.any { it.contains("低头") })
    }

    @Test
    fun `光照异常 给出光照建议`() {
        val tips = ReportAdvisor.advise(metrics(riskRate = 0.2, lightAnomalyMs = 3 * 60_000L))
        assertTrue(tips.any { it.contains("光照") })
    }

    @Test
    fun `提醒改善率低 建议调整频率`() {
        val tips = ReportAdvisor.advise(metrics(reminderCount = 4, improvementRate = 0.25))
        assertTrue(tips.any { it.contains("提醒频率") })
    }

    @Test
    fun `休息没离开屏幕 建议起身`() {
        val tips = ReportAdvisor.advise(metrics(breakMs = 5 * 60_000L, effectiveRestCount = 0))
        assertTrue(tips.any { it.contains("起身") })
    }

    @Test
    fun `完整度低 建议检查机位`() {
        val tips = ReportAdvisor.advise(metrics(completeness = 0.4))
        assertTrue(tips.any { it.contains("完整度") })
    }

    @Test
    fun `表现很好 给出正向反馈`() {
        val tips = ReportAdvisor.advise(metrics(riskRate = 0.08, breakMs = 5 * 60_000L, effectiveRestCount = 1))
        assertTrue(tips.any { it.contains("很好") || it.contains("保持") })
    }

    @Test
    fun `无数据时不给姿势类建议`() {
        val tips = ReportAdvisor.advise(metrics(focusMs = 0L))
        assertEquals(listOf("本轮没有专注数据——先在 P06 完成一轮番茄再来看建议"), tips)
    }

    @Test
    fun `建议最多3条`() {
        val tips = ReportAdvisor.advise(
            metrics(
                riskRate = 0.6, nearMs = 10 * 60_000L,
                reminderCount = 5, improvementRate = 0.2,
                breakMs = 5 * 60_000L, completeness = 0.5,
            )
        )
        assertTrue(tips.size <= 3)
    }

    @Test
    fun `趋势对比建议`() {
        assertTrue(ReportAdvisor.adviseTrend(60.0, 40.0, "高风险占比(%)")!!.contains("上升"))
        assertTrue(ReportAdvisor.adviseTrend(10.0, 30.0, "有效学习时长(分)")!!.contains("低于"))
        assertEquals(null, ReportAdvisor.adviseTrend(50.0, 52.0, "有效学习时长(分)"))
        assertEquals(null, ReportAdvisor.adviseTrend(50.0, null, "有效学习时长(分)"))
    }
}
