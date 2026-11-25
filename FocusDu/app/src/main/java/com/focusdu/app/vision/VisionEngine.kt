package com.focusdu.app.vision

import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.vision.protocol.FusionState
import com.focusdu.app.vision.protocol.VisionSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 视觉引擎抽象（X00 接口协议）。
 * R4 实现真引擎（建议类名 MediaPipeVisionEngine），完成后到 AppContainer 替换绑定即可，
 * 客户端其余代码一行不用动 —— 这就是「D3 晚出算法首版，客户端不空转」的落点。
 */
interface VisionEngine {
    /** 每秒 2~8 个样本（与取流帧率匹配）；实现方负责流的异常重启 */
    fun samples(): Flow<VisionSample>
    fun stop()

    /**
     * 注入个人基线（T1-04）：P03 校准完成、P04 写入或 App 重启恢复后由上层调用，null 表示无基线。
     * 不依赖基线的实现（如 Mock）保持默认空实现。
     */
    fun setBaseline(baseline: PersonalBaseline?) {}
}

/**
 * 骨架期开发与演示引擎：按脚本循环输出六态，让 P07 实时小窗、提醒引擎、
 * 报告全链路在无相机、无算法的情况下就能跑通（R1 骨架验收靠它）。
 * 脚本一个周期 70 秒：正常8 → 偏近10 → 正常4 → 低头14 → 正常4 →
 * 困倦10 → 正常4 → 离屏6 → 正常4 → 离开6。
 * （低头/困倦/离屏段的持续时长均超过提醒引擎 persist 阈值，能稳定触发演示提醒）
 */
class MockVisionEngine(
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
) : VisionEngine {

    private val flow = MutableSharedFlow<VisionSample>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var job: Job? = null

    override fun samples(): Flow<VisionSample> {
        ensureStarted()
        return flow
    }

    private fun ensureStarted() {
        if (job?.isActive == true) return
        job = scope.launch {
            var seq = 0L
            while (isActive) {
                flow.emit(mockSample(scriptState(seq), seq))
                seq++
                delay(500)
            }
        }
    }

    override fun stop() {
        job?.cancel()
    }

    private fun scriptState(seq: Long): FusionState {
        val t = (seq / 2L) % 70L   // 每 500ms 一帧 → 秒序号，70 秒一个周期
        return when {
            t in 0L..7L -> FusionState.NORMAL
            t in 8L..17L -> FusionState.NEAR
            t in 18L..21L -> FusionState.NORMAL
            t in 22L..35L -> FusionState.HEAD_DOWN
            t in 36L..39L -> FusionState.NORMAL
            t in 40L..49L -> FusionState.DROWSY
            t in 50L..53L -> FusionState.NORMAL
            t in 54L..59L -> FusionState.OFF_SCREEN
            t in 60L..63L -> FusionState.NORMAL
            else -> FusionState.AWAY
        }
    }

    private fun mockSample(state: FusionState, seq: Long): VisionSample {
        val jitter = ((seq % 7) - 3) * 0.01f
        val risky = state != FusionState.NORMAL && state != FusionState.AWAY
        return VisionSample(
            tsMs = clock.nowMs(),
            seq = seq,
            facePresent = state != FusionState.AWAY,
            conf = 0.94f + jitter,
            faceScale = if (state == FusionState.NEAR) 0.42f else 0.30f,
            headPitchDeg = if (state == FusionState.HEAD_DOWN) 28f else 8f,
            headYawDeg = 3f + jitter * 10,
            headForwardDeg = if (state == FusionState.HEAD_DOWN) 15f else 4f,
            shoulderTiltDeg = 2f,
            eyeOpenRatio = if (state == FusionState.DROWSY) 0.25f else 0.9f,
            blinkRatePerMin = if (state == FusionState.DROWSY) 8f else 16f,
            mouthOpenRatio = 0.05f,
            lumFace = 128f,
            lumDesktop = 96f,
            lumBackground = 110f,
            overexposureRatio = 0.02f,
            leftRightDelta = 12f,
            lightClass = "normal",
            atDesk = state != FusionState.AWAY,
            offScreenRatio = if (state == FusionState.OFF_SCREEN) 0.8f else 0.05f,
            headStability = 0.93f,
            distanceBand = when {
                state == FusionState.NEAR -> "veryNear"
                risky -> "near"
                else -> "normal"
            },
            postureEvent = if (state == FusionState.HEAD_DOWN) "headDown" else "none",
            drowsiness = when (state) {
                FusionState.DROWSY -> "moderate"
                FusionState.OFF_SCREEN -> "mild"
                else -> "none"
            },
            state = state,
        )
    }
}
