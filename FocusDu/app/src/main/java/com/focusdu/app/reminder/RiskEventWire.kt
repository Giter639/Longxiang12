package com.focusdu.app.reminder

import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.pomodoro.PomodoroEngine
import com.focusdu.app.pomodoro.PomodoroState
import com.focusdu.app.vision.StateStreamDebouncer
import com.focusdu.app.vision.protocol.FusionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ★ 骨架演示接线：把实时状态流转成 RiskEvent 喂给提醒引擎。
 * R4 真引擎上线后（T2-09/T2-03 输出事件）直接替换本类 —— 提醒引擎本身不用动。
 *
 * 规则（演示口径，阈值最终以 R4 为准）：
 * - 持续达到该类型 persist 阈值才投递（引擎仍会按自己的规则过滤一遍）；
 * - 持续 2×阈值 → MODERATE，4×阈值 → SEVERE（升级时重新投递）；
 * - SEVERE + 健康优先番茄 → 触发提前休息（T3-01）。
 */
class RiskEventWire(
    private val clock: MonotonicClock,
    private val reminderEngine: ReminderEngine,
    private val pomodoroEngine: PomodoroEngine,
    private val ui: StateFlow<StateStreamDebouncer.UiState>,
    private val pomodoroState: StateFlow<PomodoroState>,
) {
    private var lastEmitted: Pair<FusionState, RiskLevel>? = null
    private val openReminders = HashMap<RiskType, String>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            ui.collect { handle(it) }
        }
    }

    private suspend fun handle(u: StateStreamDebouncer.UiState) {
        when (u) {
            is StateStreamDebouncer.UiState.Live -> handleLive(u)
            else -> {
                // 断流或空闲时并不知道用户是否恢复：只关闭未决提醒，不记「风险解除」，
                // 否则相机关闭、切到后台都会被算成 60 秒内恢复，虚增报告里的改善率。
                lastEmitted = null
                openReminders.clear()
            }
        }
    }

    private suspend fun handleLive(u: StateStreamDebouncer.UiState.Live) {
        val mapping = when (u.state) {
            FusionState.NEAR -> RiskType.DISTANCE
            FusionState.HEAD_DOWN -> RiskType.POSTURE
            FusionState.DROWSY -> RiskType.DROWSINESS
            FusionState.OFF_SCREEN -> RiskType.OFF_SCREEN
            else -> null
        }
        val type = mapping ?: run {
            lastEmitted = null
            clearOpenReminders()
            return
        }
        if (pomodoroState.value.phase != Phase.FOCUS) return   // 休息期不提醒

        val persist = reminderEngine.policy.persistMsByType[type] ?: 15_000L
        val duration = clock.nowMs() - u.sinceMs
        val level = when {
            duration >= persist * 4 -> RiskLevel.SEVERE
            duration >= persist * 2 -> RiskLevel.MODERATE
            else -> RiskLevel.LIGHT
        }
        if (duration < persist) return

        val key = u.state to level
        if (key == lastEmitted) return
        lastEmitted = key

        val decision = reminderEngine.onRiskEvent(RiskEvent(type, level, u.sinceMs, duration))
        if (decision.action == ReminderAction.REMIND && decision.reminderId != null) {
            openReminders[type] = decision.reminderId
        }
        if (level == RiskLevel.SEVERE) {
            pomodoroEngine.onSevereRisk()
        }
    }

    private suspend fun clearOpenReminders() {
        for ((_, id) in openReminders) reminderEngine.onRiskCleared(id)
        openReminders.clear()
    }
}
