package com.focusdu.app.report

import com.focusdu.app.core.EventTypes
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.core.TrackEvent
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.track.Tracker
import com.focusdu.app.vision.protocol.FusionState

/**
 * T3-04 休息恢复校验：休息期间累计「离开屏幕/起身」时长，
 * 休息结束时结算结论（是否有效休息）→ 埋点 REST_VALIDATED 写入报告数据链，
 * 并供 P09 休息页展示恢复校验结果。
 * 有效判定口径：离开屏幕累计 ≥ MetricDefs.EFFECTIVE_REST_MIN_MS（60s）。
 *
 * 累计方式：状态 enter/exit 结算（内部对重复状态去重，累计结果与采样频率无关）。
 * 单线程使用（容器主线程 scope）。
 */
class RestValidator(
    private val clock: MonotonicClock,
    private val tracker: Tracker,
) {
    data class RestCheckResult(
        val breakMs: Long,
        val awayMs: Long,
        val effective: Boolean,
        val summary: String,
    )

    private var inBreak = false
    private var breakStartMs = 0L
    private var awayMs = 0L
    private var lastState: FusionState? = null
    private var lastStateAtMs = 0L
    private var lastResult: RestCheckResult? = null

    private fun isAway(s: FusionState) = s == FusionState.AWAY || s == FusionState.OFF_SCREEN

    /** 容器接线：番茄相位变化（BREAK 开始计时，离开 BREAK 结算） */
    fun onPhase(phase: Phase, sessionId: Long) {
        when (phase) {
            Phase.BREAK -> {
                inBreak = true
                breakStartMs = clock.nowMs()
                awayMs = 0L
                lastState = null
            }
            else -> if (inBreak) {
                inBreak = false
                settle(sessionId)
            }
        }
    }

    /** 容器接线：实时状态流（重复状态自动去重，仅状态切换时结算上一段） */
    fun onVisionState(state: FusionState) {
        if (!inBreak) {
            lastState = state
            return
        }
        if (state == lastState) return
        val now = clock.nowMs()
        if (lastState != null && isAway(lastState!!)) {
            awayMs += (now - lastStateAtMs).coerceAtLeast(0)
        }
        lastState = state
        lastStateAtMs = now
    }

    private fun settle(sessionId: Long) {
        // 休息结束时若仍处于离屏状态，把最后一段补上
        if (lastState != null && isAway(lastState!!)) {
            awayMs += (clock.nowMs() - lastStateAtMs).coerceAtLeast(0)
        }
        lastState = null
        val breakMs = (clock.nowMs() - breakStartMs).coerceAtLeast(0)
        val effective = awayMs >= MetricDefs.EFFECTIVE_REST_MIN_MS
        val summary = if (effective) {
            "有效休息：离开屏幕 ${awayMs / 1000} 秒，距离与姿态可恢复"
        } else {
            "休息期间基本未离开屏幕（仅 ${awayMs / 1000} 秒），建议起身远眺"
        }
        lastResult = RestCheckResult(breakMs, awayMs, effective, summary)
        tracker.log(
            TrackEvent(sessionId, clock.nowMs(), EventTypes.REST_VALIDATED, metricMs = awayMs, detail = summary)
        )
    }

    /** P09/P12 展示最近一次校验结论 */
    fun lastCheck(): RestCheckResult? = lastResult

    /** 休息中实时查看当前累计离屏时长 */
    fun currentAwayMs(): Long {
        if (!inBreak) return awayMs
        return if (lastState != null && isAway(lastState!!)) {
            awayMs + (clock.nowMs() - lastStateAtMs).coerceAtLeast(0)
        } else {
            awayMs
        }
    }
}
