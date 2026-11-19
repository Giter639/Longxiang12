package com.focusdu.app.pomodoro

import com.focusdu.app.core.EventTypes
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.core.TrackEvent
import com.focusdu.app.track.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Phase { IDLE, FOCUS, FOCUS_PAUSED, BREAK, FINISHED }

data class PomodoroState(
    val phase: Phase = Phase.IDLE,
    val mode: PomodoroMode? = null,
    val sessionId: Long = 0,
    val roundIndex: Int = 0,
    val focusTargetMs: Long = 0,
    val breakTargetMs: Long = 0,
    val focusElapsedMs: Long = 0,
    val focusRemainingMs: Long = 0,
    val breakElapsedMs: Long = 0,
    val breakRemainingMs: Long = 0,
    val earlyBreakTriggered: Boolean = false,
    /** 健康优先：提前休息后下一轮自动缩短到的目标时长（0=不缩短） */
    val nextFocusTargetMs: Long = 0,
)

/**
 * T3-01/T3-02 番茄计时引擎。
 *
 * 设计要点（对应验收「息屏 30min 恢复误差 <2s」）：
 * - 时间源只用单调时钟，剩余量永远 = 目标 −（now − 段起点 − 累计暂停），
 *   是"数学推导"而不是"倒计时递减"，息屏多久、进程被杀多久都不漂移；
 * - 每次相位变化写检查点（PomodoroCheckpointEntity），恢复时按同一公式重算。
 *
 * 单线程使用（容器主线程 scope）；suspend 方法由 VM/Service 在协程里调。
 */
class PomodoroEngine(
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val tracker: Tracker,
    private val store: PomodoroStore,
) {
    private val _state = MutableStateFlow(PomodoroState())
    val state: StateFlow<PomodoroState> = _state

    private val mutex = Mutex()
    private var segStart = 0L          // 当前段（专注或休息）开始的单调时刻
    private var pausedAccum = 0L       // 本段累计暂停毫秒
    private var pauseStart = 0L        // >0 表示正处于暂停
    private var ticker: Job? = null

    suspend fun start(mode: PomodoroMode, roundIndex: Int) = mutex.withLock {
        check(_state.value.phase == Phase.IDLE || _state.value.phase == Phase.FINISHED) { "当前轮次未结束" }
        val prev = _state.value
        // 健康优先：上一轮提前休息过 → 本轮自动缩短
        val focusTarget = if (mode.healthFirst && prev.nextFocusTargetMs > 0) prev.nextFocusTargetMs else mode.focusMs
        val sessionId = clock.nowMs()
        segStart = clock.nowMs()
        pausedAccum = 0L
        pauseStart = 0L

        store.saveSession(
            com.focusdu.app.data.db.PomodoroSessionEntity(
                sessionId = sessionId,
                mode = mode.name,
                roundIndex = roundIndex,
                focusTargetMs = focusTarget,
                breakTargetMs = mode.breakMs,
                startedAtWall = System.currentTimeMillis(),
            )
        )
        persistCheckpoint(phase = "FOCUS", mode = mode, sessionId = sessionId, roundIndex = roundIndex,
            focusTarget = focusTarget, breakTarget = mode.breakMs, early = false)

        _state.value = PomodoroState(
            phase = Phase.FOCUS, mode = mode, sessionId = sessionId, roundIndex = roundIndex,
            focusTargetMs = focusTarget, breakTargetMs = mode.breakMs,
        )
        tracker.log(TrackEvent(sessionId, clock.nowMs(), EventTypes.FOCUS_START, detail = mode.name))
        startTicker()
    }

    fun pause() {
        val s = _state.value
        if (s.phase != Phase.FOCUS) return
        pauseStart = clock.nowMs()
        _state.value = s.copy(phase = Phase.FOCUS_PAUSED, focusElapsedMs = focusElapsed(), focusRemainingMs = remaining(s.focusTargetMs, focusElapsed()))
        tracker.log(TrackEvent(s.sessionId, clock.nowMs(), EventTypes.FOCUS_PAUSE))
        persistCheckpointAsync(phase = "FOCUS_PAUSED", mode = s.mode!!, sessionId = s.sessionId, roundIndex = s.roundIndex,
            focusTarget = s.focusTargetMs, breakTarget = s.breakTargetMs, early = s.earlyBreakTriggered)
    }

    fun resume() {
        val s = _state.value
        if (s.phase != Phase.FOCUS_PAUSED) return
        if (pauseStart > 0) {
            pausedAccum += clock.nowMs() - pauseStart
            pauseStart = 0L
        }
        _state.value = s.copy(phase = Phase.FOCUS)
        tracker.log(TrackEvent(s.sessionId, clock.nowMs(), EventTypes.FOCUS_RESUME))
        persistCheckpointAsync(phase = "FOCUS", mode = s.mode!!, sessionId = s.sessionId, roundIndex = s.roundIndex,
            focusTarget = s.focusTargetMs, breakTarget = s.breakTargetMs, early = s.earlyBreakTriggered)
    }

    /** 专注期放弃 = 本轮作废；休息期"放弃" = 跳过休息直接结轮 */
    suspend fun giveUp() = mutex.withLock {
        val s = _state.value
        when (s.phase) {
            Phase.FOCUS, Phase.FOCUS_PAUSED -> {
                tracker.log(TrackEvent(s.sessionId, clock.nowMs(), EventTypes.GIVE_UP, metricMs = focusElapsed()))
                store.updateOutcome(s.sessionId, "GAVE_UP", System.currentTimeMillis(), s.earlyBreakTriggered)
                stopTicker()
                store.clearCheckpoint()
                _state.value = PomodoroState()
            }
            Phase.BREAK -> finishRound()
            else -> Unit
        }
    }

    /**
     * 健康优先模式的提前休息入口（T3-01）：
     * 重度风险信号到达时立即结束专注进入休息，本轮学习时长按已学计算。
     * 非 HEALTH_FIRST 模式不响应（提醒引擎只记录）。
     */
    suspend fun onSevereRisk() = mutex.withLock {
        val s = _state.value
        if (s.mode?.healthFirst == true && s.phase == Phase.FOCUS && !s.earlyBreakTriggered) {
            tracker.log(TrackEvent(s.sessionId, clock.nowMs(), EventTypes.EARLY_BREAK))
            enterBreak(s, early = true)
        }
    }

    /** ticker 每 250ms 调一次；测试可手动调用推进相位 */
    fun tick() {
        val s = _state.value
        when (s.phase) {
            Phase.FOCUS -> {
                val elapsed = focusElapsed()
                if (elapsed >= s.focusTargetMs) {
                    _state.value = s.copy(focusElapsedMs = s.focusTargetMs, focusRemainingMs = 0)
                    scope.launch { mutex.withLock { enterBreak(_state.value, early = _state.value.earlyBreakTriggered) } }
                } else {
                    _state.value = s.copy(focusElapsedMs = elapsed, focusRemainingMs = s.focusTargetMs - elapsed)
                }
            }
            Phase.BREAK -> {
                val elapsed = breakElapsed()
                if (elapsed >= s.breakTargetMs) {
                    scope.launch { mutex.withLock { finishRound() } }
                } else {
                    _state.value = s.copy(breakElapsedMs = elapsed, breakRemainingMs = s.breakTargetMs - elapsed)
                }
            }
            else -> Unit
        }
    }

    /** T3-02 异常恢复：进程被杀 / 息屏后按检查点数学重算，误差仅取决于单调时钟本身 */
    suspend fun recoverIfPossible(maxStaleMs: Long = 12 * 60 * 60_000L): Boolean = mutex.withLock {
        val c = store.loadCheckpoint() ?: return@withLock false
        val now = clock.nowMs()
        if (now - c.savedAtElapsed > maxStaleMs) {
            store.clearCheckpoint()
            return@withLock false
        }
        val mode = PomodoroMode.valueOf(c.mode)
        segStart = c.segmentStartElapsed
        pausedAccum = c.pausedAccumMs
        pauseStart = c.pauseStartElapsed
        val base = PomodoroState(
            mode = mode, sessionId = c.sessionId, roundIndex = c.roundIndex,
            focusTargetMs = c.focusTargetMs, breakTargetMs = c.breakTargetMs,
            earlyBreakTriggered = c.earlyBreakTriggered,
        )
        when (c.phase) {
            "FOCUS" -> _state.value = base.copy(phase = Phase.FOCUS)
            "FOCUS_PAUSED" -> _state.value = base.copy(phase = Phase.FOCUS_PAUSED)
            "BREAK" -> _state.value = base.copy(phase = Phase.BREAK)
            else -> {
                store.clearCheckpoint()
                return@withLock false
            }
        }
        startTicker()   // 若停机期间段已到时，第一跳 tick 会自动推进相位
        true
    }

    private suspend fun enterBreak(s: PomodoroState, early: Boolean) {
        val focusElapsed = if (pauseStart > 0) pauseStart - segStart - pausedAccum else clock.nowMs() - segStart - pausedAccum
        tracker.log(TrackEvent(s.sessionId, clock.nowMs(), EventTypes.FOCUS_END, metricMs = focusElapsed.coerceAtLeast(0)))
        segStart = clock.nowMs()
        pausedAccum = 0L
        pauseStart = 0L
        persistCheckpoint(phase = "BREAK", mode = s.mode!!, sessionId = s.sessionId, roundIndex = s.roundIndex,
            focusTarget = s.focusTargetMs, breakTarget = s.breakTargetMs, early = early)
        // 健康优先：本轮触发提前休息 → 下一轮自动缩短 40%
        val nextTarget = if (early) (s.mode.focusMs * 0.6).toLong() / 60_000L * 60_000L else 0L
        _state.value = s.copy(
            phase = Phase.BREAK, focusElapsedMs = focusElapsed.coerceAtLeast(0), focusRemainingMs = 0,
            earlyBreakTriggered = early, nextFocusTargetMs = nextTarget,
            breakElapsedMs = 0, breakRemainingMs = s.breakTargetMs,
        )
        tracker.log(TrackEvent(s.sessionId, clock.nowMs(), EventTypes.BREAK_START, detail = if (early) "EARLY" else "NORMAL"))
    }

    private suspend fun finishRound() {
        val s = _state.value
        if (s.phase != Phase.BREAK) return
        val breakElapsed = clock.nowMs() - segStart - pausedAccum
        tracker.log(TrackEvent(s.sessionId, clock.nowMs(), EventTypes.BREAK_END, metricMs = breakElapsed.coerceAtLeast(0)))
        store.updateOutcome(s.sessionId, "FINISHED", System.currentTimeMillis(), s.earlyBreakTriggered)
        stopTicker()
        store.clearCheckpoint()
        _state.value = s.copy(phase = Phase.FINISHED, breakElapsedMs = breakElapsed.coerceAtLeast(0), breakRemainingMs = 0)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(250)
                tick()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun focusElapsed(): Long {
        val end = if (pauseStart > 0) pauseStart else clock.nowMs()
        return (end - segStart - pausedAccum).coerceAtLeast(0)
    }

    private fun breakElapsed(): Long = (clock.nowMs() - segStart - pausedAccum).coerceAtLeast(0)

    private fun remaining(target: Long, elapsed: Long): Long = (target - elapsed).coerceAtLeast(0)

    private fun persistCheckpointAsync(
        phase: String, mode: PomodoroMode, sessionId: Long, roundIndex: Int,
        focusTarget: Long, breakTarget: Long, early: Boolean,
    ) {
        scope.launch {
            persistCheckpoint(phase, mode, sessionId, roundIndex, focusTarget, breakTarget, early)
        }
    }

    /** 检查点写库：相位切换是关键路径，suspend 方法内直接等待完成（恢复测试依赖这一点） */
    private suspend fun persistCheckpoint(
        phase: String, mode: PomodoroMode, sessionId: Long, roundIndex: Int,
        focusTarget: Long, breakTarget: Long, early: Boolean,
    ) {
        store.saveCheckpoint(
            com.focusdu.app.data.db.PomodoroCheckpointEntity(
                sessionId = sessionId, phase = phase, mode = mode.name,
                segmentStartElapsed = segStart, pausedAccumMs = pausedAccum,
                pauseStartElapsed = pauseStart, savedAtElapsed = clock.nowMs(),
                focusTargetMs = focusTarget, breakTargetMs = breakTarget,
                roundIndex = roundIndex, earlyBreakTriggered = early,
            )
        )
    }
}
