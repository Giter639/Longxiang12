package com.focusdu.app.vision.algo

import com.focusdu.app.vision.protocol.FusionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-10 验收锚点：六态融合、滞回与最短保持——**1s 内不反复跳变**。
 */
class FusionStateMachineTest {

    private fun input(
        band: String = "normal",
        posture: String = "none",
        drowsiness: String = "none",
        atDesk: Boolean = true,
        offScreen: Float = 0f,
        face: Boolean = true,
    ) = FusionStateMachine.FusionInput(band, posture, drowsiness, atDesk, offScreen, face)

    @Test
    fun `候选态保持超过最短保持时间才切态`() {
        val m = FusionStateMachine()
        val start = 10_000L
        // 偏近持续 1.1s（< minHold 1.2s）→ 不切
        var s = FusionState.NORMAL
        for (i in 0..2) s = m.update(start + i * 400, input(band = "near"))
        assertEquals(FusionState.NORMAL, s)
        // 累计到 1.3s → 切 NEAR
        s = m.update(start + 1_300, input(band = "near"))
        assertEquals(FusionState.NEAR, s)
    }

    @Test
    fun `1秒内不反复跳变——每400ms交替偏近与正常，永不切态`() {
        val m = FusionStateMachine()
        var s = FusionState.NORMAL
        var switches = 0
        val start = m.lastSwitchMs
        for (i in 0 until 60) { // 24 秒内每 400ms 翻转
            s = m.update(i * 400L, input(band = if (i % 2 == 0) "near" else "normal"))
            if (m.lastSwitchMs != start) switches++
        }
        assertEquals("候选不断被打断，不允许切态", 0, switches)
        assertEquals(FusionState.NORMAL, s)
    }

    @Test
    fun `优先级——重度困倦压过低头与偏近`() {
        val m = FusionStateMachine()
        // 先建立低头态（候选保持 ≥1.2s）
        var ts = 0L
        for (i in 0..3) { ts += 500; m.update(ts, input(posture = "headDown")) }
        assertEquals(FusionState.HEAD_DOWN, m.state())
        // 重度困倦出现 → 切 DROWSY（候选保持 2s）
        for (i in 0..3) { ts += 500; m.update(ts, input(posture = "headDown", drowsiness = "severe", band = "veryNear")) }
        assertEquals(FusionState.DROWSY, m.state())
    }

    @Test
    fun `离开——不在工位持续超时切AWAY`() {
        val m = FusionStateMachine()
        var s = FusionState.NORMAL
        for (i in 0..3) s = m.update(i * 500L, input(atDesk = false, face = false))
        assertEquals(FusionState.AWAY, s)
    }

    @Test
    fun `离屏——在工位但视线离开屏幕`() {
        val m = FusionStateMachine()
        var s = FusionState.NORMAL
        for (i in 0..3) s = m.update(i * 500L, input(offScreen = 0.8f))
        assertEquals(FusionState.OFF_SCREEN, s)
    }

    @Test
    fun `相邻两次切态间隔不小于1秒——验收1s内不反复跳变`() {
        val m = FusionStateMachine()
        var ts = 0L
        var lastSwitch = 0L
        // 依次：偏近1.5s → 正常1.5s → 低头1.5s，检查每次切态间隔
        val script = listOf(
            input(band = "near"), input(), input(posture = "headDown"),
        )
        var phase = 0
        var phaseStart = 0L
        while (phase < script.size) {
            m.update(ts, script[phase])
            if (m.lastSwitchMs > lastSwitch) {
                if (lastSwitch > 0) {
                    assertTrue(
                        "切态间隔 ${m.lastSwitchMs - lastSwitch}ms 应 ≥1s",
                        m.lastSwitchMs - lastSwitch >= 1_000,
                    )
                }
                lastSwitch = m.lastSwitchMs
            }
            ts += 500
            if (ts - phaseStart >= 1_500) { phase++; phaseStart = ts }
        }
    }

    @Test
    fun `mild困倦不切态——短时异常只记录`() {
        val m = FusionStateMachine()
        for (i in 0..3) m.update(i * 500L, input(drowsiness = "mild"))
        assertEquals(FusionState.NORMAL, m.state())
    }
}
