package com.focusdu.app.vision.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-01 验收锚点：三档判定 + 滞回防抖 + 剂量累计（剂量误差 <10% 用合成流固化）。
 */
class DistanceMonitorTest {

    private val baseline = baselineOf(0.30f)

    private fun baselineOf(scale: Float) = com.focusdu.app.vision.PersonalBaseline(
        faceScale = scale, pitchDeg = 8f, yawDeg = 3f, forwardDeg = 4f,
        shoulderTiltDeg = 2f, lumFace = 128f, lumBackground = 110f,
        confidence = 0.95f, sampleCount = 160, createdAtWall = 0L, sceneTag = "desk",
    )

    @Test
    fun `三档判定——正常偏近很近`() {
        val m = DistanceMonitor(baseline)
        assertEquals(DistanceMonitor.BAND_NORMAL, m.update(0, 0.30f, 0.95f))
        assertEquals(DistanceMonitor.BAND_NEAR, m.update(1000, 0.34f, 0.95f))   // 1.133
        assertEquals(DistanceMonitor.BAND_VERY_NEAR, m.update(2000, 0.40f, 0.95f)) // 1.333
    }

    @Test
    fun `滞回防抖——临界距离档位不闪烁`() {
        val m = DistanceMonitor(baseline)
        m.update(0, 0.34f, 0.95f) // near (1.133)
        // 退到 1.083：低于进入阈值 1.10 但高于退出阈值 1.06 → 维持 near
        assertEquals(DistanceMonitor.BAND_NEAR, m.update(1000, 0.325f, 0.95f))
        // 退到 1.033：低于退出阈值 → normal
        assertEquals(DistanceMonitor.BAND_NORMAL, m.update(2000, 0.31f, 0.95f))
    }

    @Test
    fun `剂量累计误差小于10——合成偏近20秒`() {
        val m = DistanceMonitor(baseline)
        // 前 10s 正常（500ms 一帧），后 20s 偏近
        for (i in 0 until 20) m.update(i * 500L, 0.30f, 0.95f)
        for (i in 20 until 60) m.update(i * 500L, 0.34f, 0.95f)
        val expected = 20_000L
        assertTrue("剂量 ${m.riskMs} 应在 20s±10%", m.riskMs in (expected * 9 / 10)..(expected * 11 / 10))
    }

    @Test
    fun `无效样本保持原档位——断流兜底交给客户端X18`() {
        val m = DistanceMonitor(baseline)
        assertEquals(DistanceMonitor.BAND_NEAR, m.update(0, 0.34f, 0.95f))
        assertEquals(DistanceMonitor.BAND_NEAR, m.update(1000, 0.20f, 0.3f)) // conf<0.5：档位不变
    }
}
