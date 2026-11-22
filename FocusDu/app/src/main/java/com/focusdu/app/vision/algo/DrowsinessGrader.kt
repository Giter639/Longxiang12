package com.focusdu.app.vision.algo

/**
 * T2-09 困倦分级与提示阈值（算法端；规则可解释，阈值 DRAFT_v0.1 归 R4 调）。
 *
 * 分级规则（每条都可解释、可单独触发）：
 * - severe：连续闭眼 ≥4s（疑似微睡眠）；或闭眼 ≥2.5s 且伴点头/眨眼变慢；或 30s 内点头 ≥3 次；
 * - moderate：连续闭眼 ≥2s；或点头 ≥2 次；或眨眼变慢 + 打哈欠同时出现；
 * - mild：眨眼变慢 / 打哈欠 / 单次点头 / 连续闭眼 ≥1s 任一出现。
 * 协议约束：输出 none/mild/moderate/severe，与 VisionSample.drowsiness 字段一致。
 * 验收「三级均可触发」由单测锚定。
 */
object DrowsinessGrader {

    const val LEVEL_NONE = "none"
    const val LEVEL_MILD = "mild"
    const val LEVEL_MODERATE = "moderate"
    const val LEVEL_SEVERE = "severe"

    // DRAFT_v0.1 阈值
    const val SEVERE_CLOSED_MS = 4_000L
    const val SEVERE_CLOSED_WITH_SIGN_MS = 2_500L
    const val MODERATE_CLOSED_MS = 2_000L
    const val MILD_CLOSED_MS = 1_000L
    const val SEVERE_NOD_COUNT = 3
    const val MODERATE_NOD_COUNT = 2

    fun grade(f: DrowsinessDetector.Features): String = when {
        f.closedMs >= SEVERE_CLOSED_MS -> LEVEL_SEVERE
        f.closedMs >= SEVERE_CLOSED_WITH_SIGN_MS && (f.slowBlink || f.nodCount >= MODERATE_NOD_COUNT) -> LEVEL_SEVERE
        f.nodCount >= SEVERE_NOD_COUNT && f.slowBlink -> LEVEL_SEVERE
        f.closedMs >= MODERATE_CLOSED_MS -> LEVEL_MODERATE
        f.nodCount >= MODERATE_NOD_COUNT -> LEVEL_MODERATE
        f.slowBlink && f.yawnActive -> LEVEL_MODERATE
        f.slowBlink || f.yawnActive || f.nodCount >= 1 || f.closedMs >= MILD_CLOSED_MS -> LEVEL_MILD
        else -> LEVEL_NONE
    }
}
