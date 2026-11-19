package com.focusdu.app.vision

import com.focusdu.app.core.FakeMonotonicClock
import com.focusdu.app.vision.protocol.FusionState
import com.focusdu.app.vision.protocol.VisionSample
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X18 验收的自动化锚点：断流 3s 内提示「检测中断」不白屏；新状态防抖上屏。
 */
class StateStreamDebouncerTest {

    private fun sample(state: FusionState, conf: Float = 0.95f, ts: Long = 0L) = VisionSample(
        tsMs = ts, seq = 0, facePresent = true, conf = conf,
        faceScale = 0.3f, headPitchDeg = 8f, headYawDeg = 3f, headForwardDeg = 4f,
        shoulderTiltDeg = 2f, eyeOpenRatio = 0.9f, blinkRatePerMin = 16f, mouthOpenRatio = 0.05f,
        lumFace = 128f, lumDesktop = 96f, lumBackground = 110f, overexposureRatio = 0.02f,
        leftRightDelta = 12f, lightClass = "normal", atDesk = true, offScreenRatio = 0f,
        headStability = 0.9f, distanceBand = "normal", postureEvent = "none",
        drowsiness = "none", state = state,
    )

    @Test
    fun `断流3秒内提示检测中断`() = runTest {
        val clock = FakeMonotonicClock(1_000L)
        val d = StateStreamDebouncer(backgroundScope, clock)

        d.onSample(sample(FusionState.NORMAL))
        runCurrent()
        assertEquals(FusionState.NORMAL, (d.ui.value as StateStreamDebouncer.UiState.Live).state)

        // 之后无任何样本 → 3s 内进入中断态（不白屏，保留最后状态）
        advanceTimeBy(3_000)
        runCurrent()
        val interrupted = d.ui.value as StateStreamDebouncer.UiState.Interrupted
        assertEquals(FusionState.NORMAL, interrupted.lastState)

        // 恢复供流 → 回到 Live
        d.onSample(sample(FusionState.HEAD_DOWN, ts = 4_100L))
        runCurrent()
        assertTrue(d.ui.value is StateStreamDebouncer.UiState.Live)
    }

    @Test
    fun `新状态需持续达到确认窗才上屏`() = runTest {
        val clock = FakeMonotonicClock(1_000L)
        val d = StateStreamDebouncer(backgroundScope, clock)

        d.onSample(sample(FusionState.NORMAL, ts = 1_000L))
        runCurrent()
        assertEquals(FusionState.NORMAL, (d.ui.value as StateStreamDebouncer.UiState.Live).state)

        // 新状态首次出现（1500ms 处）：确认窗内保持旧态
        clock.advance(500L)
        d.onSample(sample(FusionState.HEAD_DOWN, ts = 1_500L))
        runCurrent()
        assertEquals(FusionState.NORMAL, (d.ui.value as StateStreamDebouncer.UiState.Live).state)

        // 连续样本流：下一个样本在 2500ms 到达（距首次出现累计 1000ms ≥ 800ms 确认窗）→ 切换
        clock.advance(1_000L)
        d.onSample(sample(FusionState.HEAD_DOWN, ts = 2_500L))
        runCurrent()
        assertEquals(FusionState.HEAD_DOWN, (d.ui.value as StateStreamDebouncer.UiState.Live).state)
    }

    @Test
    fun `低置信度样本不驱动界面`() = runTest {
        val clock = FakeMonotonicClock(1_000L)
        val d = StateStreamDebouncer(backgroundScope, clock)
        d.onSample(sample(FusionState.NEAR, conf = 0.3f))
        runCurrent()
        assertEquals(StateStreamDebouncer.UiState.Idle, d.ui.value)
    }

    @Test
    fun `同状态持续更新不改变状态起始时间`() = runTest {
        val clock = FakeMonotonicClock(1_000L)
        val d = StateStreamDebouncer(backgroundScope, clock)
        d.onSample(sample(FusionState.NEAR, ts = 1_000L))
        runCurrent()
        val first = d.ui.value as StateStreamDebouncer.UiState.Live
        clock.advance(2_000L)
        d.onSample(sample(FusionState.NEAR, ts = 3_000L))
        runCurrent()
        val second = d.ui.value as StateStreamDebouncer.UiState.Live
        assertEquals(first.sinceMs, second.sinceMs)
        assertTrue(second.updatedAtMs > first.updatedAtMs)
    }
}
