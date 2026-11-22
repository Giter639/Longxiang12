package com.focusdu.app.guard

import android.content.Context
import android.content.Intent
import com.focusdu.app.MainActivity
import com.focusdu.app.core.EventTypes
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.core.TrackEvent
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.pomodoro.PomodoroState
import com.focusdu.app.track.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * T4-03/T4-05 运行时专注守护。
 *
 * 只在番茄 FOCUS 相位轮询前台应用。命中限制应用后显示用户授权的软拦截页；
 * 电话、联系人、系统安全类包始终放行；退出限制需要二次确认。
 */
class FocusGuardEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val pomodoroState: StateFlow<PomodoroState>,
    private val settings: GuardSettings,
    private val permissions: GuardPermissions,
    private val notificationMemory: NotificationMemoryStore,
    private val tracker: Tracker,
) {
    private val reader = ForegroundAppReader(context)
    private var monitorJob: Job? = null
    private var bypassedPackage: String? = null
    private var notificationSessionId = 0L

    private val overlay = GuardOverlayController(
        context = context,
        onReturnFocus = {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        },
        onConfirmExit = { packageName ->
            bypassedPackage = packageName
            val state = pomodoroState.value
            tracker.log(
                TrackEvent(
                    sessionId = state.sessionId,
                    atMs = clock.nowMs(),
                    type = EventTypes.GUARD_TRIGGER,
                    refId = packageName,
                    detail = "CONFIRM_EXIT",
                )
            )
        },
    )

    fun start() {
        scope.launch {
            pomodoroState
                .map { it.phase to it.sessionId }
                .distinctUntilChanged()
                .collect { (phase, sessionId) ->
                    when (phase) {
                        Phase.FOCUS -> {
                            if (sessionId != notificationSessionId) {
                                notificationSessionId = sessionId
                                bypassedPackage = null
                            }
                            notificationMemory.beginSession(sessionId)
                            startMonitor(sessionId)
                        }
                        Phase.FOCUS_PAUSED -> {
                            stopMonitor()
                            notificationMemory.endSession()
                        }
                        else -> {
                            stopMonitor()
                            notificationMemory.endSession()
                        }
                    }
                }
        }
    }

    private fun startMonitor(sessionId: Long) {
        if (monitorJob?.isActive == true) return
        overlay.hide()
        reader.reset()
        monitorJob = scope.launch {
            var previousPackage: String? = null
            while (isActive) {
                val state = pomodoroState.value
                if (state.phase != Phase.FOCUS || state.sessionId != sessionId) break

                val pollStartedAt = clock.nowMs()
                val foregroundPackage = reader.poll()
                if (foregroundPackage != previousPackage) {
                    bypassedPackage = null
                    previousPackage = foregroundPackage
                }

                val shouldIntercept = GuardDecision.shouldIntercept(
                    foregroundPackage = foregroundPackage,
                    restrictedPackages = settings.restrictedPackages.value,
                    ownPackage = context.packageName,
                )
                if (!shouldIntercept) {
                    overlay.hide()
                } else if (
                    foregroundPackage != null &&
                    foregroundPackage != bypassedPackage &&
                    permissions.isGranted(GuardPermission.USAGE_ACCESS) &&
                    permissions.isGranted(GuardPermission.OVERLAY)
                ) {
                    val shown = overlay.show(foregroundPackage)
                    if (shown) {
                        tracker.log(
                            TrackEvent(
                                sessionId = sessionId,
                                atMs = clock.nowMs(),
                                type = EventTypes.GUARD_TRIGGER,
                                refId = foregroundPackage,
                                metricMs = clock.nowMs() - pollStartedAt,
                                detail = "SHOW",
                            )
                        )
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
            overlay.hide()
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        overlay.hide()
        reader.reset()
    }

    private companion object {
        // 300ms 是验收目标；ColorOS 对 UsageStats 的实际上报延迟只能在真机测量。
        const val POLL_INTERVAL_MS = 250L
    }
}
