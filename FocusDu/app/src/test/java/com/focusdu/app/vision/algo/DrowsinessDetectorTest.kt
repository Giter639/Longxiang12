package com.focusdu.app.vision.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-08 验收锚点：闭眼延长/眨眼变慢/点头/哈欠 四类特征的波形判定。
 */
class DrowsinessDetectorTest {

    @Test
    fun `闭眼延长——连续闭眼时长正确累计与复位`() {
        val d = DrowsinessDetector()
        var f = d.update(1000, 0.9f, 16f, 8f, 0.05f, 0.95f, true)
        assertEquals(0L, f.closedMs)
        f = d.update(1500, 0.1f, 16f, 8f, 0.05f, 0.95f, true)
        assertEquals("刚闭合当帧为 0", 0L, f.closedMs)
        f = d.update(3000, 0.1f, 16f, 8f, 0.05f, 0.95f, true)
        assertEquals(1500L, f.closedMs)
        f = d.update(4500, 0.1f, 16f, 8f, 0.05f, 0.95f, true)
        assertEquals(3000L, f.closedMs)
        f = d.update(4700, 0.9f, 16f, 8f, 0.05f, 0.95f, true)
        assertEquals(0L, f.closedMs)
    }

    @Test
    fun `眨眼变慢——持续低于阈值30秒才置位`() {
        val d = DrowsinessDetector()
        var f = d.update(10_000, 0.9f, 6f, 8f, 0.05f, 0.95f, true)
        assertFalse("不足 30s 不置位", f.slowBlink)
        f = d.update(41_000, 0.9f, 6f, 8f, 0.05f, 0.95f, true)
        assertTrue(f.slowBlink)
        f = d.update(42_000, 0.9f, 16f, 8f, 0.05f, 0.95f, true) // 恢复正常眨眼率
        assertFalse(f.slowBlink)
    }

    @Test
    fun `点头——完整下跌回升周期计数，低头久蹲不计`() {
        val d = DrowsinessDetector()
        // 建立基线（EMA 收敛到 ~10°）
        var ts = 0L
        repeat(20) { d.update(ts, 0.9f, 16f, 10f, 0.05f, 0.95f, true); ts += 100 }
        // 两次完整点头：下跌 14° 持续 700ms 后回升
        repeat(2) {
            d.update(ts, 0.9f, 16f, 24f, 0.05f, 0.95f, true); ts += 700   // 下跌段（形态完整）
            d.update(ts, 0.9f, 16f, 10f, 0.05f, 0.95f, true); ts += 2000  // 回升段
        }
        assertEquals(2, d.update(ts, 0.9f, 16f, 10f, 0.05f, 0.95f, true).nodCount)
        // 下跌 5s 不回升：是低头不是点头，不计数
        val d2 = DrowsinessDetector()
        ts = 0
        repeat(20) { d2.update(ts, 0.9f, 16f, 10f, 0.05f, 0.95f, true); ts += 100 }
        d2.update(ts, 0.9f, 16f, 24f, 0.05f, 0.95f, true); ts += 5_000
        d2.update(ts, 0.9f, 16f, 10f, 0.05f, 0.95f, true)
        assertEquals(0, d2.update(ts + 100, 0.9f, 16f, 10f, 0.05f, 0.95f, true).nodCount)
    }

    @Test
    fun `哈欠——张嘴持续超过1_2秒激活，30秒内保持`() {
        val d = DrowsinessDetector()
        var f = d.update(1000, 0.9f, 16f, 8f, 0.7f, 0.95f, true)
        assertFalse("不足 1.2s 未激活", f.yawnActive)
        f = d.update(2300, 0.9f, 16f, 8f, 0.7f, 0.95f, true)
        assertTrue(f.yawnActive)
        f = d.update(2400, 0.9f, 16f, 8f, 0.05f, 0.95f, true) // 闭嘴（张嘴 1.4s ≥ 1.2s，记一次哈欠）
        assertTrue("30s 窗口内保持", f.yawnActive)
        f = d.update(33_500, 0.9f, 16f, 8f, 0.05f, 0.95f, true)
        assertFalse(f.yawnActive)
    }

    @Test
    fun `无人脸全部特征复位——离开不等于困倦`() {
        val d = DrowsinessDetector()
        d.update(0, 0.1f, 16f, 8f, 0.7f, 0.95f, true)
        val f = d.update(5000, 0.1f, 16f, 8f, 0.7f, 0.95f, false)
        assertEquals(0L, f.closedMs)
        assertFalse(f.yawnActive)
    }
}
