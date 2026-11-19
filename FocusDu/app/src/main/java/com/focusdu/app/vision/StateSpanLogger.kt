package com.focusdu.app.vision

import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.core.TrackEvent
import com.focusdu.app.core.EventTypes
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.pomodoro.PomodoroState
import com.focusdu.app.track.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 状态持续段埋点：把实时状态流转成 STATE_SPAN 事件（指标引擎 T6-02 的高风险时长数据源）。
 * 只记录专注/休息期间的风险状态持续段，正常态与空闲期不记录。
 * 单线程（容器主线程 scope）。
 */
class StateSpanLogger(
    private val clock: MonotonicClock,
    private val tracker: Tracker,
    private val pomodoroState: StateFlow<PomodoroState>,
    private val ui: StateFlow<StateStreamDebouncer.UiState>,
) {
    private data class Span(val phaseRef: String, val state: String, val startMs: Long)

    private var open: Span? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(pomodoroState, ui) { p, u -> p to u }
                .distinctUntilChanged { old, new -> key(old.first, old.second) == key(new.first, new.second) }
                .collect { (p, u) -> onKeyChanged(p, u) }
        }
    }

    private fun key(p: PomodoroState, u: StateStreamDebouncer.UiState): String =
        p.phase.name + ":" + when (u) {
            is StateStreamDebouncer.UiState.Live -> u.state.name
            is StateStreamDebouncer.UiState.Interrupted -> "INTERRUPTED"
            StateStreamDebouncer.UiState.Idle -> "IDLE"
        }

    private fun onKeyChanged(p: PomodoroState, u: StateStreamDebouncer.UiState) {
        val now = clock.nowMs()
        closeOpen(now)

        val live = u as? StateStreamDebouncer.UiState.Live ?: return
        val phaseRef = when (p.phase) {
            Phase.FOCUS, Phase.FOCUS_PAUSED -> "FOCUS"
            Phase.BREAK -> "BREAK"
            else -> return   // 空闲期不开段
        }
        open = Span(phaseRef, live.state.name, now)
    }

    private fun closeOpen(now: Long) {
        val span = open ?: return
        open = null
        val duration = now - span.startMs
        if (duration >= 2_000) {
            tracker.log(TrackEvent(0L, now, EventTypes.STATE_SPAN, refId = span.phaseRef, metricMs = duration, detail = span.state))
        }
    }
}
