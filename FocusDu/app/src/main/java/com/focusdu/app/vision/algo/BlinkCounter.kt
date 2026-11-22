package com.focusdu.app.vision.algo

import java.util.ArrayDeque

/**
 * T2-06 眨眼频率与用眼节律（算法端；阈值 DRAFT_v0.1，归 R4 调）。
 *
 * 眨眼判定：eyeOpenRatio 从睁（>[openThreshold]）落入闭（<[closedThreshold]）再回到睁，
 * 闭合时长在 [minClosedMs, maxClosedMs] 之间计 1 次（过滤长闭眼/丢帧毛刺）。
 * 输出：滑动窗口（默认 60s）眨眼率（次/分钟）+ 距上次眨眼时长（长时间不眨眼事件输入，T2-08）。
 * 验收「对照标注计数误差 ≤±15%」：合成眨眼序列的单测锚点 + X08 标注数据回归。
 */
class BlinkCounter(config: Config = Config()) {
    data class Config(
        val openThreshold: Float = 0.5f,
        val closedThreshold: Float = 0.3f,
        val minClosedMs: Long = 60L,
        val maxClosedMs: Long = 500L,
        val windowMs: Long = 60_000L,
        val longNoBlinkMs: Long = 30_000L,
        val minValidConf: Float = 0.5f,
    )

    data class Stats(
        val blinkRatePerMin: Float,
        val blinksInWindow: Int,
        val msSinceLastBlink: Long,   // 人脸在场才有意义
    )

    private companion object {
        const val UNSET = -1L
    }

    private val cfg = config
    private val blinkTimestamps = ArrayDeque<Long>()
    private var eyeOpen = false
    private var closedSinceMs = UNSET
    private var lastBlinkMs = 0L
    private var lastTsMs = 0L

    fun reset() {
        blinkTimestamps.clear()
        eyeOpen = false; closedSinceMs = UNSET; lastBlinkMs = 0; lastTsMs = 0
    }

    fun update(tsMs: Long, eyeOpenRatio: Float, conf: Float, facePresent: Boolean): Stats {
        if (conf >= cfg.minValidConf && facePresent) {
            if (!eyeOpen && eyeOpenRatio > cfg.openThreshold) {
                // 闭→睁：结算一次闭合时长
                if (closedSinceMs >= 0) {
                    val closedMs = tsMs - closedSinceMs
                    if (closedMs in cfg.minClosedMs..cfg.maxClosedMs) {
                        blinkTimestamps.addLast(tsMs)
                        lastBlinkMs = tsMs
                    }
                }
                eyeOpen = true
                closedSinceMs = UNSET
            } else if (eyeOpen && eyeOpenRatio < cfg.closedThreshold) {
                eyeOpen = false
                closedSinceMs = tsMs
            }
        }
        lastTsMs = tsMs
        while (blinkTimestamps.isNotEmpty() && blinkTimestamps.first() < tsMs - cfg.windowMs) {
            blinkTimestamps.removeFirst()
        }
        val rate = blinkTimestamps.size * 60_000f / cfg.windowMs
        return Stats(
            blinkRatePerMin = rate,
            blinksInWindow = blinkTimestamps.size,
            msSinceLastBlink = if (lastBlinkMs == 0L) cfg.longNoBlinkMs else tsMs - lastBlinkMs,
        )
    }
}
