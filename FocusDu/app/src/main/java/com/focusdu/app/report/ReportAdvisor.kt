package com.focusdu.app.report

import kotlin.math.abs

/**
 * T6-01 报告「下一步建议」：基于本轮指标的可解释规则，生成 1~3 条建议。
 * 规则全部可答辩（对应 docx 报告内容表的「下一步建议」行），不做黑盒。
 */
object ReportAdvisor {

    fun advise(m: RoundMetrics): List<String> {
        if (m.focusMs == 0L) {
            return listOf("本轮没有专注数据——先在 P06 完成一轮番茄再来看建议")
        }
        val tips = mutableListOf<String>()

        if (m.riskRate >= 0.5) {
            tips += "高风险占比 ${(m.riskRate * 100).toInt()}% 偏高，建议缩短下一轮番茄或提前休息（可试试健康优先模式）"
        } else if (m.riskRate >= 0.3 && m.focusMs >= 10 * 60_000L) {
            tips += "风险占比中等，注意保持当前坐姿节奏，出现提醒及时调整"
        }

        // 找出占比最高的风险类型给针对性建议
        val buckets = listOf(
            "近距离用眼" to m.nearMs,
            "低头" to m.headDownMs,
            "困倦" to m.drowsyMs,
            "光照异常" to m.lightAnomalyMs,
        ).filter { it.second > 0 }.maxByOrNull { it.second }
        when (buckets?.first) {
            "近距离用眼" -> tips += "近距离用眼占比最高——注意屏幕摆远一点/字调大，身体后靠"
            "低头" -> tips += "低头时间最长——建议垫高屏幕或调整椅子高度"
            "困倦" -> tips += "困倦特征明显——本轮结束后建议起身活动、补水"
            "光照异常" -> tips += "光照异常——调整台灯位置或角度，避免逆光和眩光"
        }

        if (m.reminderCount > 0 && m.improvementRate >= 0 && m.improvementRate < 0.5) {
            tips += "提醒后恢复较慢（改善率 ${(m.improvementRate * 100).toInt()}%），可降低提醒频率或换提醒人格（P11）"
        }
        if (m.breakMs > 0 && m.effectiveRestCount == 0) {
            tips += "休息时没有离开屏幕——下个休息建议起身远眺，恢复效果更好"
        }
        if (m.completeness < 0.7) {
            tips += "数据完整度仅 ${(m.completeness * 100).toInt()}%——检查机位与光线，让摄像头稳定看到你"
        }

        if (tips.isEmpty()) {
            tips += if (m.riskRate > 0 && m.riskRate < 0.2 && m.effectiveRestCount > 0) {
                "本轮表现很好：风险低、休息有效——保持这个节奏"
            } else {
                "本轮表现良好，保持节奏"
            }
        }
        return tips.take(3)
    }

    /** 与近 7 次中位对比后的追加建议（P14 可复用） */
    fun adviseTrend(current: Double, medianLast7: Double?, metricName: String): String? {
        val ref = medianLast7 ?: return null
        if (abs(ref) < 1e-9) return null
        val delta = (current - ref) / ref
        return when {
            metricName.contains("风险") && delta > 0.15 -> "风险率较近期中位明显上升，注意休息"
            metricName.contains("有效学习") && delta < -0.15 -> "有效学习时长低于近期中位，试试减少干扰源"
            else -> null
        }
    }
}
