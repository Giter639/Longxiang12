package com.focusdu.app.vision.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-02 验收锚点：事件起止误差 ≤1s；抖动误报 ≤1次/10min；滞回维持；优先级互斥。
 */
class PostureDetectorTest {

    @Test
    fun `事件起止误差小于1秒——持续低头10秒`() {
        val d = PostureDetector()
        // 500ms 一帧：t=1000 起低头 25°，持续到 t=19500，t=20000 回正
        var eventStart = 0L
        for (t in 0..1999) {
            val ts = t * 10L
            val pitch = if (ts in 1000..19499) 25f else 8f
            d.update(ts, pitch, 4f, 2f, 0.95f)
            if (t == 300) eventStart = d.activeEvent?.startMs ?: 0L
        }
        // 起点误差：startMs 应为原始达标时刻 1000（t=3000 时已确认）
        assertEquals(1000L, eventStart)
        // 终点误差：t=20000 回正后 500ms 内事件结束
        d.update(20_500, 8f, 4f, 2f, 0.95f)
        assertNull("回正后 500ms 内应结束", d.activeEvent)
    }

    @Test
    fun `抖动不误报——10分钟内频繁瞬态低头零误报`() {
        val d = PostureDetector()
        // 每 400ms 在 25°/5° 之间翻转，持续 10 分钟 → 0 次确认事件
        var falseEvents = 0
        for (t in 0..1499) {
            val ts = t * 400L
            d.update(ts, if (t % 2 == 0) 25f else 5f, 4f, 2f, 0.95f)
            if (d.activeEvent != null) falseEvents++
        }
        assertEquals(0, falseEvents)
    }

    @Test
    fun `滞回维持——俯仰回落到滞回区事件不中断`() {
        val d = PostureDetector()
        for (t in 0..2) d.update(t * 500L, 25f, 4f, 2f, 0.95f) // 确认事件
        assertNotNull(d.activeEvent)
        // 回落到 16°（exit 15 与 enter 20 之间）→ 维持
        val still = d.update(2000, 16f, 4f, 2f, 0.95f)
        assertEquals(PostureDetector.EVENT_HEAD_DOWN, still)
    }

    @Test
    fun `优先级互斥——低头优先于前倾与肩线`() {
        val d = PostureDetector()
        for (t in 0..2) d.update(t * 500L, 25f, 20f, 12f, 0.95f)
        assertEquals(PostureDetector.EVENT_HEAD_DOWN, d.update(2000, 25f, 20f, 12f, 0.95f))
    }

    @Test
    fun `肩线倾斜单独成事件`() {
        val d = PostureDetector()
        val event = (0..3).map { d.update(it * 500L, 8f, 4f, 12f, 0.95f) }.last()
        assertEquals(PostureDetector.EVENT_SHOULDER_TILT, event)
        assertNotNull(d.activeEvent)
    }
}
