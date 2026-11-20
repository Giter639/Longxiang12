package com.focusdu.app.report

import com.focusdu.app.FakeTracker
import com.focusdu.app.core.EventTypes
import com.focusdu.app.core.FakeMonotonicClock
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.vision.protocol.FusionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3-04 休息恢复校验的自动化锚点：休息结束时结论自动产生并写入 REST_VALIDATED 埋点。
 */
class RestValidatorTest {

    @Test
    fun `休息期间离开屏幕足够久 判定有效休息`() {
        val clock = FakeMonotonicClock()
        val tracker = FakeTracker()
        val v = RestValidator(clock, tracker)

        v.onPhase(Phase.BREAK, sessionId = 1L)          // t=0 休息开始
        v.onVisionState(FusionState.AWAY)               // t=0 进入离屏
        clock.advance(70_000L)                          // 离屏持续 70 秒（重复状态自动去重）
        v.onVisionState(FusionState.AWAY)
        v.onPhase(Phase.FINISHED, sessionId = 1L)       // 休息结束 → 结算

        val r = v.lastCheck()
        assertNotNull(r)
        assertTrue(r!!.effective)
        assertEquals(70_000L, r.awayMs)
        assertTrue(r.summary.contains("有效休息"))
        val ev = tracker.events.first { it.type == EventTypes.REST_VALIDATED }
        assertEquals(1L, ev.sessionId)
        assertTrue(ev.detail!!.contains("有效休息"))
    }

    @Test
    fun `休息期间一直盯着屏幕 判定无效并给建议`() {
        val clock = FakeMonotonicClock()
        val tracker = FakeTracker()
        val v = RestValidator(clock, tracker)

        v.onPhase(Phase.BREAK, sessionId = 2L)
        clock.advance(5 * 60_000L)
        v.onVisionState(FusionState.NORMAL)             // 一直正常注视
        v.onPhase(Phase.FINISHED, sessionId = 2L)

        val r = v.lastCheck()!!
        assertTrue(!r.effective)
        assertTrue(r.summary.contains("建议起身远眺"))
    }

    @Test
    fun `提前跳过休息 也会结算并埋点`() {
        val clock = FakeMonotonicClock()
        val tracker = FakeTracker()
        val v = RestValidator(clock, tracker)

        v.onPhase(Phase.BREAK, sessionId = 3L)
        clock.advance(10_000L)
        v.onVisionState(FusionState.OFF_SCREEN)         // 离屏 10 秒（<60s）
        v.onPhase(Phase.IDLE, sessionId = 3L)           // 用户点"提前结束休息"

        assertTrue(!v.lastCheck()!!.effective)
        assertTrue(tracker.events.any { it.type == EventTypes.REST_VALIDATED })
    }

    @Test
    fun `专注期间喂状态不产生累计`() {
        val clock = FakeMonotonicClock()
        val tracker = FakeTracker()
        val v = RestValidator(clock, tracker)

        v.onPhase(Phase.FOCUS, sessionId = 4L)
        clock.advance(10 * 60_000L)
        v.onVisionState(FusionState.AWAY)               // 专注期离开不归属休息
        v.onPhase(Phase.FINISHED, sessionId = 4L)       // 从未进入休息 → 不结算

        assertTrue(v.lastCheck() == null)
        assertTrue(tracker.events.none { it.type == EventTypes.REST_VALIDATED })
    }
}
