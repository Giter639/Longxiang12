package com.focusdu.app.vision.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-06 验收锚点：合成眨眼序列计数误差 ≤±15%；长闭眼不计；窗口滚动。
 */
class BlinkCounterTest {

    @Test
    fun `合成60秒20次眨眼——计数误差远小于15`() {
        val c = BlinkCounter()
        var stats = c.update(0, 0.9f, 0.95f, true)
        var ts = 500L
        repeat(20) {
            // 闭眼 200ms（每 3s 一次）
            stats = c.update(ts, 0.1f, 0.95f, true)
            ts += 200
            stats = c.update(ts, 0.9f, 0.95f, true)
            ts += 2800
        }
        // 全程 20 次/60s → 20 次/分钟，误差 0%（≪15%）
        assertEquals(20, stats.blinksInWindow)
        assertEquals(20f, stats.blinkRatePerMin, 20f * 0.15f)
    }

    @Test
    fun `长闭眼800ms不计入眨眼——归入闭眼延长特征`() {
        val c = BlinkCounter()
        c.update(0, 0.9f, 0.95f, true)
        c.update(1000, 0.1f, 0.95f, true)
        val stats = c.update(1800, 0.1f, 0.95f, true) // 仍闭
        val after = c.update(2000, 0.9f, 0.95f, true) // 闭了 1000ms 后回睁
        assertEquals(0, after.blinksInWindow)
        assertTrue(after.msSinceLastBlink >= BlinkCounter.Config().longNoBlinkMs)
    }

    @Test
    fun `窗口滚动——60秒前的眨眼不再计入`() {
        val c = BlinkCounter()
        c.update(0, 0.9f, 0.95f, true)
        c.update(1000, 0.1f, 0.95f, true)
        val first = c.update(1200, 0.9f, 0.95f, true)
        assertEquals(1, first.blinksInWindow)
        val later = c.update(70_000, 0.9f, 0.95f, true)
        assertEquals(0, later.blinksInWindow)
    }

    @Test
    fun `无人脸时不产生眨眼判定`() {
        val c = BlinkCounter()
        c.update(0, 0.9f, 0.95f, true)
        c.update(1000, 0.1f, 0.95f, false)
        val after = c.update(1200, 0.9f, 0.95f, false)
        assertEquals(0, after.blinksInWindow)
    }
}
