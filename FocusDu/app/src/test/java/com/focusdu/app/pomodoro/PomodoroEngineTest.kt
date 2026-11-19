package com.focusdu.app.pomodoro

import com.focusdu.app.FakePomodoroStore
import com.focusdu.app.FakeTracker
import com.focusdu.app.core.FakeMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * T3-01/T3-02 验收的自动化锚点。
 */
class PomodoroEngineTest {

    @Test
    fun `息屏30分钟后恢复 误差小于2秒`() = runTest {
        val clock = FakeMonotonicClock()
        val store = FakePomodoroStore()
        val engine = PomodoroEngine(clock, backgroundScope, FakeTracker(), store)
        engine.start(PomodoroMode.STANDARD, 1)

        // 专注 10 分钟后进程被杀（息屏期间）
        clock.advance(10 * 60_000L)
        engine.tick()

        // 息屏 30 分钟后重启，从检查点恢复
        clock.advance(30 * 60_000L)
        val recovered = PomodoroEngine(clock, backgroundScope, FakeTracker(), store)
        assertTrue(recovered.recoverIfPossible())
        recovered.tick()
        runCurrent()   // 让相位切换协程跑完

        // 25 分钟专注已超时 → 应自动进入 5 分钟休息；误差仅来自时钟源，假时钟下为 0
        val s = recovered.state.value
        assertEquals(Phase.BREAK, s.phase)
        val driftMs = abs(s.breakRemainingMs - 5 * 60_000L)
        assertTrue("恢复误差应<2s，实际偏差 ${driftMs}ms", driftMs <= 2_000)
    }

    @Test
    fun `暂停期间不计入专注时长`() = runTest {
        val clock = FakeMonotonicClock(1_000L)
        val engine = PomodoroEngine(clock, backgroundScope, FakeTracker(), FakePomodoroStore())
        engine.start(PomodoroMode.STANDARD, 1)

        clock.advance(5 * 60_000L)
        engine.tick()
        engine.pause()
        clock.advance(3 * 60_000L)   // 暂停（含息屏）3 分钟
        engine.resume()
        clock.advance(2 * 60_000L)
        engine.tick()

        val s = engine.state.value
        assertEquals(Phase.FOCUS, s.phase)
        assertEquals(7 * 60_000L, s.focusElapsedMs)
        assertEquals(18 * 60_000L, s.focusRemainingMs)
    }

    @Test
    fun `专注结束自动进入休息 休息结束轮次完成`() = runTest {
        val clock = FakeMonotonicClock()
        val store = FakePomodoroStore()
        val engine = PomodoroEngine(clock, backgroundScope, FakeTracker(), store)
        engine.start(PomodoroMode.STANDARD, 1)

        clock.advance(25 * 60_000L)
        engine.tick()
        runCurrent()
        assertEquals(Phase.BREAK, engine.state.value.phase)

        clock.advance(5 * 60_000L)
        engine.tick()
        runCurrent()
        assertEquals(Phase.FINISHED, engine.state.value.phase)

        val session = store.sessions.values.first()
        assertEquals("FINISHED", session.outcome)
        assertNull(store.checkpoint)
    }

    @Test
    fun `放弃本轮 记录GAVE_UP并清检查点`() = runTest {
        val clock = FakeMonotonicClock()
        val store = FakePomodoroStore()
        val engine = PomodoroEngine(clock, backgroundScope, FakeTracker(), store)
        engine.start(PomodoroMode.DEEP, 1)
        clock.advance(3 * 60_000L)
        engine.giveUp()

        assertEquals("GAVE_UP", store.sessions.values.first().outcome)
        assertNull(store.checkpoint)
        assertEquals(Phase.IDLE, engine.state.value.phase)
    }

    @Test
    fun `健康优先-重度风险提前休息且下一轮自动缩短`() = runTest {
        val clock = FakeMonotonicClock()
        val engine = PomodoroEngine(clock, backgroundScope, FakeTracker(), FakePomodoroStore())
        engine.start(PomodoroMode.HEALTH_FIRST, 1)

        clock.advance(5 * 60_000L)
        engine.onSevereRisk()
        runCurrent()

        val s = engine.state.value
        assertEquals(Phase.BREAK, s.phase)
        assertTrue(s.earlyBreakTriggered)
        assertEquals(15 * 60_000L, s.nextFocusTargetMs)   // 25min × 0.6

        // 休息走完后开下一轮 → 焦点目标应为缩短后的 15 分钟
        clock.advance(5 * 60_000L)
        engine.tick()
        runCurrent()
        assertEquals(Phase.FINISHED, engine.state.value.phase)
        engine.start(PomodoroMode.HEALTH_FIRST, 2)
        assertEquals(15 * 60_000L, engine.state.value.focusTargetMs)
    }

    @Test
    fun `非健康优先模式不响应提前休息`() = runTest {
        val clock = FakeMonotonicClock()
        val engine = PomodoroEngine(clock, backgroundScope, FakeTracker(), FakePomodoroStore())
        engine.start(PomodoroMode.STANDARD, 1)
        clock.advance(1 * 60_000L)
        engine.onSevereRisk()
        runCurrent()
        assertEquals(Phase.FOCUS, engine.state.value.phase)
        assertFalse(engine.state.value.earlyBreakTriggered)
    }
}
