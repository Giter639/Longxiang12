package com.focusdu.app.vision.algo

import com.focusdu.app.vision.PersonalBaseline
import com.focusdu.app.reminder.RiskEvent
import com.focusdu.app.reminder.RiskLevel
import com.focusdu.app.reminder.RiskType

/**
 * T2-03 久坐、离座与恢复识别（算法端；阈值 DRAFT_v0.1，归 R4 调）。
 *
 * - 久坐：atDesk 连续伏案时长达到 [Config.sedentaryLightMs] 投递 LIGHT、达到 severe 投递 SEVERE
 *   （RiskEvent.SEDENTARY，喂提醒引擎；六态不含久坐，不进 FusionState）；
 * - 离座：atDesk=false 持续 ≥[Config.awayConfirmMs] 才算离座（防捡东西误判）；
 * - 恢复：离座归来后 [Config.recoveryWindowMs] 内由上层喂入当前距离/姿态档位，
 *   与基线偏差在允许范围内则判「休息后已恢复」（P09 恢复校验展示用）。
 * 离座识别准确率 ≥90% 的验收依赖 X08 标注回归；单测锚定状态迁移与计时正确性。
 */
class SittingMonitor(config: Config = Config()) {
    data class Config(
        val sedentaryLightMs: Long = 45 * 60_000L,
        val sedentarySevereMs: Long = 60 * 60_000L,
        val awayConfirmMs: Long = 2 * 60_000L,
        val recoveryWindowMs: Long = 3 * 60_000L,
        val recoveryMaxBandRatio: Float = 1.10f,   // 归来后 faceScale/基线 超过此值=距离没恢复
        val recoveryMaxPitchDeltaDeg: Float = 8f,  // 归来后俯仰相对基线超过此值=姿态没恢复
    )

    data class State(
        val sedentaryMs: Long,     // 本轮连续伏案时长（离座清零）
        val away: Boolean,         // 当前是否离座
        val awayMs: Long,          // 当前离座持续时长
        val justReturned: Boolean, // 本次采样点是否刚归来（仅归来后第一个采样为 true）
    )

    private val cfg = config
    private var lastTsMs = 0L
    private var sedentaryMs = 0L
    private var awayRawSinceMs = UNSET
    private var away = false
    private var awaySinceMs = 0L
    private var returnedAtMs = 0L
    private var sedentaryLevelEmitted = RiskLevel.LIGHT // 记录本轮已投递的最高级别

    /** 归来后恢复判定结果（P09 展示；null=未在恢复窗口内或未判） */
    var recoveryChecked: Boolean? = null
        private set

    /** 本轮产出的久坐风险事件（级别升级时更新；null=未达标） */
    var sedentaryEvent: RiskEvent? = null
        private set

    fun reset() {
        lastTsMs = 0; sedentaryMs = 0; awayRawSinceMs = UNSET
        away = false; awaySinceMs = 0; returnedAtMs = 0
        sedentaryLevelEmitted = RiskLevel.LIGHT
        recoveryChecked = null; sedentaryEvent = null
    }

    fun update(tsMs: Long, atDesk: Boolean): State {
        val delta = if (lastTsMs > 0 && tsMs > lastTsMs) tsMs - lastTsMs else 0L
        lastTsMs = tsMs
        var justReturned = false

        if (atDesk) {
            if (away) {
                // 归来：离座 ≥ 确认时长才重置久坐计时（短暂起身不断表）
                val awayDur = tsMs - awaySinceMs
                if (awayDur >= cfg.awayConfirmMs) {
                    sedentaryMs = 0
                    sedentaryEvent = null
                    sedentaryLevelEmitted = RiskLevel.LIGHT
                    returnedAtMs = tsMs
                    justReturned = true
                    recoveryChecked = null
                }
                away = false
                awayRawSinceMs = UNSET
            }
            sedentaryMs += delta
            // 久坐事件升级投递
            when {
                sedentaryMs >= cfg.sedentarySevereMs && sedentaryLevelEmitted.ordinal < RiskLevel.SEVERE.ordinal -> {
                    sedentaryLevelEmitted = RiskLevel.SEVERE
                    sedentaryEvent = RiskEvent(RiskType.SEDENTARY, RiskLevel.SEVERE, tsMs - sedentaryMs, sedentaryMs)
                }
                sedentaryMs >= cfg.sedentaryLightMs && sedentaryLevelEmitted.ordinal < RiskLevel.MODERATE.ordinal &&
                    sedentaryLevelEmitted != RiskLevel.MODERATE -> {
                    sedentaryLevelEmitted = RiskLevel.MODERATE
                    sedentaryEvent = RiskEvent(RiskType.SEDENTARY, RiskLevel.MODERATE, tsMs - sedentaryMs, sedentaryMs)
                }
            }
        } else {
            awayRawSinceMs = if (awayRawSinceMs < 0) tsMs else awayRawSinceMs
            if (!away && tsMs - awayRawSinceMs >= cfg.awayConfirmMs) {
                away = true
                awaySinceMs = awayRawSinceMs
            }
            // 离座期间不累计久坐
        }

        return State(sedentaryMs, away, if (away) tsMs - awaySinceMs else 0L, justReturned)
    }

    /**
     * 归来后的恢复校验（P09「回来后的恢复校验结果展示」）。
     * 在恢复窗口内判定：距离档位回到 normal 且俯仰相对基线未超差 → 已恢复。
     */
    fun checkRecovery(
        nowMs: Long,
        distanceBand: String,
        pitchDeg: Float,
        baseline: PersonalBaseline,
    ): Boolean? {
        if (returnedAtMs == 0L) return null
        if (nowMs - returnedAtMs > cfg.recoveryWindowMs) return recoveryChecked
        val ok = distanceBand == DistanceMonitor.BAND_NORMAL &&
            kotlin.math.abs(pitchDeg - baseline.pitchDeg) <= cfg.recoveryMaxPitchDeltaDeg
        recoveryChecked = ok
        return ok
    }

    private companion object {
        const val UNSET = -1L
    }
}
