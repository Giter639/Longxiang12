package com.focusdu.app.vision.algo

import com.focusdu.app.vision.PersonalBaseline

/**
 * T2-01 用眼距离三档判定与剂量累计（算法端；阈值 DRAFT_v0.1，归 R4 调）。
 *
 * 原理：前视人脸尺度 faceScale 与个人基线之比 ≈ 基线距离/当前距离。
 * 进入档位用 enter 阈值、退回用更低的 exit 阈值（滞回），防临界距离抖动导致档位闪烁。
 * 剂量：处于 near/veryNear 的时长累计（口径表 M5「近距离」桶：持续偏近累计分钟数）。
 */
class DistanceMonitor(
    private val baseline: PersonalBaseline,
    config: Config = Config(),
) {
    data class Config(
        val nearEnterRatio: Float = 1.10f,
        val nearExitRatio: Float = 1.06f,
        val veryNearEnterRatio: Float = 1.30f,
        val veryNearExitRatio: Float = 1.24f,
        val minValidConf: Float = 0.5f,
    )

    companion object {
        const val BAND_NORMAL = "normal"
        const val BAND_NEAR = "near"
        const val BAND_VERY_NEAR = "veryNear"
    }

    private val cfg = config
    private var current = BAND_NORMAL

    /** 偏近剂量（ms）：near/veryNear 状态累计时长，供报告「持续偏近累计分钟数」 */
    var riskMs = 0L
        private set
    var lastTsMs = 0L
        private set

    fun reset() {
        current = BAND_NORMAL
        riskMs = 0L
        lastTsMs = 0L
    }

    /** 每个视觉样本调用一次，返回当前档位 */
    fun update(tsMs: Long, faceScale: Float, conf: Float): String {
        if (lastTsMs > 0 && tsMs > lastTsMs && current != BAND_NORMAL) {
            riskMs += tsMs - lastTsMs
        }
        lastTsMs = tsMs
        if (conf < cfg.minValidConf || faceScale <= 0f || baseline.faceScale <= 0f) {
            return current // 无效样本不改变档位（断流兜底由客户端 X18 做）
        }
        val ratio = faceScale / baseline.faceScale
        val inVeryNear = ratio >= cfg.veryNearEnterRatio ||
            (current == BAND_VERY_NEAR && ratio >= cfg.veryNearExitRatio)
        val inNear = !inVeryNear &&
            (ratio >= cfg.nearEnterRatio || (current == BAND_NEAR && ratio >= cfg.nearExitRatio))
        current = when {
            inVeryNear -> BAND_VERY_NEAR
            inNear -> BAND_NEAR
            else -> BAND_NORMAL
        }
        return current
    }
}
