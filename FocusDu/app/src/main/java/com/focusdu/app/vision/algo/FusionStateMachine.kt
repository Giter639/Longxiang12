package com.focusdu.app.vision.algo

import com.focusdu.app.vision.protocol.FusionState

/**
 * T2-10 多指标融合状态机（算法端主战场；协议六态，阈值 DRAFT_v0.1 归 R4 调）。
 *
 * 优先级（同帧多指标越线时取最高风险）：困倦 > 离开 > 离屏 > 低头 > 偏近 > 正常。
 * 防跳变（任务卡验收「1s 内不反复跳变」）：候选态必须**连续**保持 ≥[Config.minHoldMs]
 * 才允许切态；候选被打断即重新计时。因此任意两次切态的间隔 ≥ minHoldMs。
 * 客户端 X18 只做 800ms 防抖显示 + 3s 断流兜底，不再做任何阈值判断。
 */
class FusionStateMachine(config: Config = Config()) {
    data class Config(
        val minHoldMs: Long = 1_200L,
        val offScreenEnterRatio: Float = 0.5f,   // 离屏占比阈值
        val drowsyMinLevel: String = "moderate", // mild 只记录不切态（短时异常只记录）
    )

    /** 融合输入：距离档位 / 姿态事件 / 困倦分级 / 在场与离屏指标（由真引擎逐帧填充） */
    data class FusionInput(
        val distanceBand: String,
        val postureEvent: String,
        val drowsiness: String,
        val atDesk: Boolean,
        val offScreenRatio: Float,
        val facePresent: Boolean,
    )

    private val cfg = config
    private var current = FusionState.NORMAL
    private var pending: FusionState? = null
    private var pendingSinceMs = 0L

    var lastSwitchMs = 0L
        private set

    fun reset() {
        current = FusionState.NORMAL
        pending = null
        pendingSinceMs = 0L
        lastSwitchMs = 0L
    }

    /** 当前融合态 */
    fun state(): FusionState = current

    fun update(tsMs: Long, input: FusionInput): FusionState {
        val raw = rawCandidate(input)
        when {
            raw == current -> {
                pending = null
            }
            raw != pending -> {
                pending = raw
                pendingSinceMs = tsMs
            }
            tsMs - pendingSinceMs >= cfg.minHoldMs -> {
                current = raw
                pending = null
                lastSwitchMs = tsMs
            }
        }
        return current
    }

    private fun rawCandidate(input: FusionInput): FusionState = when {
        input.drowsiness == "severe" || input.drowsiness == cfg.drowsyMinLevel -> FusionState.DROWSY
        !input.atDesk -> FusionState.AWAY
        !input.facePresent || input.offScreenRatio >= cfg.offScreenEnterRatio -> FusionState.OFF_SCREEN
        input.postureEvent != PostureDetector.EVENT_NONE -> FusionState.HEAD_DOWN
        input.distanceBand != DistanceMonitor.BAND_NORMAL -> FusionState.NEAR
        else -> FusionState.NORMAL
    }
}
