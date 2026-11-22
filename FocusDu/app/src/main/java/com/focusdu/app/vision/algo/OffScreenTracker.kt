package com.focusdu.app.vision.algo

import java.util.ArrayDeque

/**
 * T2-05 离屏率与头部朝向稳定性（算法端；阈值 DRAFT_v0.1，归 R4 调）。
 *
 * 协议约束：只出**占比与次数**，不出分数——
 * - 滑动窗口（默认 60s）内 offScreenRatio 的均值 = 离屏占比；
 * - 同窗口内 headStability 均值 = 头部朝向稳定性（客户端直接透传展示）；
 * - 抬头/转头次数 = 偏航或俯仰越出跟随阈值的事件数（单次偏离只计 1 次，带 500ms 确认）。
 */
class OffScreenTracker(config: Config = Config()) {
    data class Config(
        val windowMs: Long = 60_000L,
        val lookAwayYawDeg: Float = 25f,     // |偏航| 超过视为转头
        val lookUpPitchDeg: Float = -15f,    // 俯仰低于（负=抬头）视为抬头
        val confirmMs: Long = 500L,
        val minValidConf: Float = 0.5f,
    )

    data class Stats(
        val avgOffScreenRatio: Float,   // 0~1
        val avgHeadStability: Float,    // 0~1
        val lookAwayCount: Int,         // 窗口内抬头/转头次数
    )

    private companion object {
        const val UNSET = -1L
    }

    private val cfg = config
    private val samples = ArrayDeque<Pair<Long, Float>>()          // (ts, offScreenRatio)
    private val stabilities = ArrayDeque<Pair<Long, Float>>()      // (ts, headStability)
    private var lookAwayRawSinceMs = UNSET
    private var inLookAway = false
    private var lookAwayCount = 0

    fun reset() {
        samples.clear(); stabilities.clear()
        lookAwayRawSinceMs = UNSET; inLookAway = false; lookAwayCount = 0
    }

    fun update(
        tsMs: Long,
        offScreenRatio: Float,
        headStability: Float,
        yawDeg: Float,
        pitchDeg: Float,
        conf: Float,
    ): Stats {
        if (conf >= cfg.minValidConf) {
            samples.addLast(tsMs to offScreenRatio)
            stabilities.addLast(tsMs to headStability)

            // 抬头/转头事件计数：原始越界持续 ≥confirmMs 计 1 次，回到界内后才能再计
            val rawAway = yawDeg >= cfg.lookAwayYawDeg || yawDeg <= -cfg.lookAwayYawDeg || pitchDeg <= cfg.lookUpPitchDeg
            if (rawAway) {
                if (lookAwayRawSinceMs < 0) lookAwayRawSinceMs = tsMs
                if (!inLookAway && tsMs - lookAwayRawSinceMs >= cfg.confirmMs) {
                    inLookAway = true
                    lookAwayCount++
                }
            } else {
                lookAwayRawSinceMs = UNSET
                inLookAway = false
            }
        }

        trim(samples, tsMs); trim(stabilities, tsMs)
        val avgRatio = if (samples.isEmpty()) 0f else samples.map { it.second }.average().toFloat()
        val avgStability = if (stabilities.isEmpty()) 1f else stabilities.map { it.second }.average().toFloat()
        return Stats(avgRatio, avgStability, lookAwayCount)
    }

    private fun trim(deque: ArrayDeque<Pair<Long, Float>>, nowMs: Long) {
        while (deque.isNotEmpty() && deque.first().first < nowMs - cfg.windowMs) deque.removeFirst()
    }
}
