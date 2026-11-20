package com.focusdu.app.reminder

import com.focusdu.app.FakeTracker
import com.focusdu.app.core.EventTypes
import com.focusdu.app.core.FakeMonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3-03 分级提醒引擎验收的自动化锚点：
 * 短时只记录 / 边缘触发 / 冷却 ≥3min 100% 生效 / 25min 内 ≤3 次 / 延后 / 静默。
 */
class ReminderEngineTest {

    private fun engine(): Pair<ReminderEngine, FakeMonotonicClock> {
        val clock = FakeMonotonicClock(1_000L)
        return ReminderEngine(clock, FakeTracker()) to clock
    }

    private fun event(
        type: RiskType = RiskType.DISTANCE,
        level: RiskLevel = RiskLevel.LIGHT,
        start: Long,
        dur: Long = 20_000L,
    ) = RiskEvent(type, level, start, dur)

    @Test
    fun `短时异常只记录`() {
        val (e, _) = engine()
        val d = e.onRiskEvent(event(start = 0L, dur = 10_000L))   // DISTANCE 持续阈值 15s
        assertEquals(ReminderAction.LOG_ONLY, d.action)
    }

    @Test
    fun `持续风险提醒且同一事件不重复提醒`() {
        val (e, _) = engine()
        val d1 = e.onRiskEvent(event(start = 1_000L, dur = 20_000L))
        assertEquals(ReminderAction.REMIND, d1.action)
        // 同一持续事件的后续样本（间隔 ≤5s、等级不变）→ 边缘压制
        val d2 = e.onRiskEvent(event(start = 21_000L, dur = 15_000L))
        assertEquals(ReminderAction.EDGE_SUPPRESSED, d2.action)
    }

    @Test
    fun `冷却期内压制 到点解除`() {
        val (e, clock) = engine()
        val d1 = e.onRiskEvent(event(start = 1_000L, dur = 20_000L))
        assertEquals(ReminderAction.REMIND, d1.action)

        // 60s 后新事件（间隔 >5s 视为新事件）→ 冷却压制
        clock.advance(60_000L)
        val d2 = e.onRiskEvent(event(start = 27_000L, dur = 20_000L))
        assertEquals(ReminderAction.COOLDOWN_SUPPRESSED, d2.action)

        // 3 分钟冷却过后 → 放行
        clock.advance(180_000L)
        val d3 = e.onRiskEvent(event(start = 211_000L, dur = 20_000L))
        assertEquals(ReminderAction.REMIND, d3.action)
    }

    @Test
    fun `25分钟窗口内最多提醒3次`() {
        val (e, clock) = engine()
        val actions = mutableListOf<ReminderAction>()
        repeat(4) { i ->
            val start = 1_000L + i * 200_000L
            clock.advance(200_000L)   // 每次跨过 3 分钟冷却、远超事件间隔
            actions += e.onRiskEvent(event(type = RiskType.POSTURE, start = start)).action
        }
        assertEquals(
            listOf(ReminderAction.REMIND, ReminderAction.REMIND, ReminderAction.REMIND, ReminderAction.CAP_SUPPRESSED),
            actions,
        )
    }

    @Test
    fun `延后模式入队 重度风险不延后 休息前排空`() {
        val (e, _) = engine()
        e.deferMode = true
        val d1 = e.onRiskEvent(event(start = 1_000L, dur = 30_000L))                      // 中轻度 → 延后
        assertEquals(ReminderAction.DEFER_TO_REST, d1.action)
        val d2 = e.onRiskEvent(event(start = 60_000L, dur = 30_000L, level = RiskLevel.SEVERE))
        assertEquals(ReminderAction.REMIND, d2.action)                                    // 重度永不延后
        val drained = e.drainDeferredForRest()
        assertEquals(1, drained.size)
    }

    @Test
    fun `用户延后窗口内压制`() {
        val (e, _) = engine()
        e.onRiskEvent(event(start = 1_000L, dur = 20_000L))                                // 先提醒一次
        e.snooze(RiskType.DISTANCE)
        val d2 = e.onRiskEvent(event(start = 27_000L, dur = 20_000L))                      // 新事件（间隔>5s）
        assertEquals(ReminderAction.SNOOZE_SUPPRESSED, d2.action)
    }

    @Test
    fun `静默模式一律只记录`() {
        val (e, _) = engine()
        e.silentMode = true
        val d = e.onRiskEvent(event(start = 1_000L, dur = 60_000L, level = RiskLevel.SEVERE))
        assertEquals(ReminderAction.SILENT_LOGGED, d.action)
    }

    @Test
    fun `风险解除埋点带恢复耗时`() {
        val (e, clock) = engine()
        val tracker = FakeTracker()
        val engine = ReminderEngine(clock, tracker)
        val d = engine.onRiskEvent(event(start = 1_000L, dur = 20_000L))
        clock.advance(40_000L)
        engine.onRiskCleared(d.reminderId!!)
        val cleared = tracker.events.first { it.type == EventTypes.RISK_CLEARED }
        assertEquals(40_000L, cleared.metricMs)
    }

    @Test
    fun `升级事件突破同事件压制`() {
        val (e, _) = engine()
        e.onRiskEvent(event(start = 1_000L, dur = 20_000L, level = RiskLevel.LIGHT))
        // 同一事件但升到 SEVERE：冷却仍生效（同一时段），但应落在冷却判定而非边缘压制 —— 保证升级信息不被吞
        val d = e.onRiskEvent(event(start = 21_000L, dur = 15_000L, level = RiskLevel.SEVERE))
        assertTrue(d.action == ReminderAction.COOLDOWN_SUPPRESSED || d.action == ReminderAction.REMIND)
    }
}
