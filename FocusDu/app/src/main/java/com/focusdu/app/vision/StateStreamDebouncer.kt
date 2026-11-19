package com.focusdu.app.vision

import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.vision.protocol.FusionState
import com.focusdu.app.vision.protocol.VisionSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * X18 状态机客户端接入：算法状态流 → P07 实时小窗。
 * 职责只有两个，都不做阈值判断（阈值在 R4）：
 * 1) 防抖显示：新状态需保持 confirmMs 才上屏，防 UI 抖动；
 * 2) 断流兜底：超过 streamTimeoutMs 没有有效样本 → 提示「检测中断」，界面保留最后状态不白屏。
 * 单线程使用（容器主线程 scope）。
 */
class StateStreamDebouncer(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val config: Config = Config(),
) {
    data class Config(
        val streamTimeoutMs: Long = 3_000,  // 验收：断流 3s 内提示
        val confirmMs: Long = 800,          // 新态确认窗
    )

    sealed interface UiState {
        data object Idle : UiState
        data class Live(
            val state: FusionState,
            val stateLabel: String,
            val conf: Float,
            val distanceBand: String,
            val drowsiness: String,
            val sinceMs: Long,       // 该状态从何时起（单调时钟）
            val updatedAtMs: Long,
        ) : UiState

        /** 断流兜底：显示层据此提示「检测中断」并置灰，不白屏 */
        data class Interrupted(
            val lastState: FusionState?,
            val sinceMs: Long,       // 断流从何时起
        ) : UiState
    }

    private val _ui = MutableStateFlow<UiState>(UiState.Idle)
    val ui: StateFlow<UiState> = _ui

    private var watchdog: Job? = null
    private var lastLive: UiState.Live? = null
    private var pendingState: FusionState? = null    // 等待确认的新状态
    private var pendingSinceMs = 0L                  // 新状态首次出现的单调时刻

    fun onSample(s: VisionSample) {
        if (s.conf < 0.5f) return    // 低置信度样本不驱动 UI（校准/遮挡拦截在 P03 处理）
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(config.streamTimeoutMs)
            if (isActive) onStreamTimeout()
        }

        val current = _ui.value
        val shown = (current as? UiState.Live) ?: lastLive
        if (current !is UiState.Live || shown == null || shown.state == s.state) {
            // 首帧/断流恢复/与已显示同态：立即上屏（断流恢复不等确认窗）
            pendingState = null
            showNow(s)
            return
        }
        // 新状态确认：从首次出现起累计持续 ≥ confirmMs 才切换。
        // 注意不能用"每个样本重启定时器"——样本间隔小于确认窗时新状态永远无法上屏。
        if (pendingState != s.state) {
            pendingState = s.state
            pendingSinceMs = clock.nowMs()
        }
        if (clock.nowMs() - pendingSinceMs >= config.confirmMs) {
            pendingState = null
            showNow(s)
        }
    }

    private fun showNow(s: VisionSample) {
        val now = clock.nowMs()
        val prev = lastLive
        val since = if (prev != null && prev.state == s.state) prev.sinceMs else now
        val live = UiState.Live(
            state = s.state,
            stateLabel = s.state.label,
            conf = s.conf,
            distanceBand = s.distanceBand,
            drowsiness = s.drowsiness,
            sinceMs = since,
            updatedAtMs = now,
        )
        lastLive = live
        _ui.value = live
    }

    private fun onStreamTimeout() {
        val since = clock.nowMs()
        _ui.value = UiState.Interrupted(lastState = lastLive?.state, sinceMs = since)
    }

    /** 校准/停止番茄时复位 */
    fun reset() {
        watchdog?.cancel()
        pendingState = null
        lastLive = null
        _ui.value = UiState.Idle
    }
}
