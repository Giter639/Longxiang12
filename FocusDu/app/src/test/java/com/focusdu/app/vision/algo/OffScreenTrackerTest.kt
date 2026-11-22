package com.focusdu.app.vision.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-05 验收锚点：离屏占比、头部稳定性、抬头/转头次数（只出占比与次数）。
 */
class OffScreenTrackerTest {

    @Test
    fun `离屏占比为窗口均值`() {
        val t = OffScreenTracker()
        var stats = t.update(0, 0.8f, 0.9f, 3f, 8f, 0.95f)
        for (i in 1 until 20) stats = t.update(i * 500L, 0.8f, 0.9f, 3f, 8f, 0.95f)
        assertEquals(0.8f, stats.avgOffScreenRatio, 0.01f)
        assertEquals(0.9f, stats.avgHeadStability, 0.01f)
        assertEquals(0, stats.lookAwayCount)
    }

    @Test
    fun `转头计数——持续2秒计1次，瞬态300ms不计`() {
        val t = OffScreenTracker()
        // 瞬态 300ms：低于 500ms 确认 → 不计
        t.update(0, 0.0f, 0.9f, 30f, 8f, 0.95f)
        t.update(300, 0.0f, 0.9f, 3f, 8f, 0.95f)
        assertEquals(0, t.update(400, 0.0f, 0.9f, 3f, 8f, 0.95f).lookAwayCount)
        // 持续 2s 转头 → 计 1
        for (i in 0..4) t.update(1000 + i * 500L, 0.0f, 0.9f, 30f, 8f, 0.95f)
        assertEquals(1, t.update(3600, 0.0f, 0.9f, 3f, 8f, 0.95f).lookAwayCount)
        // 再次转头 → 计 2
        for (i in 0..4) t.update(5000 + i * 500L, 0.0f, 0.9f, 30f, 8f, 0.95f)
        assertTrue(t.update(7600, 0.0f, 0.9f, 3f, 8f, 0.95f).lookAwayCount >= 2)
    }

    @Test
    fun `窗口过期——旧样本不再影响均值`() {
        val t = OffScreenTracker()
        t.update(0, 1.0f, 0.9f, 3f, 8f, 0.95f)
        // 61s 后只有本帧样本
        val stats = t.update(61_000, 0.0f, 0.95f, 3f, 8f, 0.95f)
        assertEquals(0.0f, stats.avgOffScreenRatio, 0.01f)
    }
}
