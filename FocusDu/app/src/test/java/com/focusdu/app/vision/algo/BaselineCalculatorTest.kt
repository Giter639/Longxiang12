package com.focusdu.app.vision.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * T1-04 验收锚点：基线字段完整率 100%、重建偏差 <5%、中位数抗离群。
 */
class BaselineCalculatorTest {

    private val truth = CalibrationFrameFeatures(
        tsMs = 0, faceScale = 0.300f, pitchDeg = 8f, yawDeg = 3f, forwardDeg = 4f,
        shoulderTiltDeg = 2f, lumFace = 128f, lumBackground = 110f, conf = 0.95f,
    )

    private fun frame(tsMs: Long, noise: Float, rng: Random) = truth.copy(
        tsMs = tsMs,
        faceScale = truth.faceScale + noise * (rng.nextFloat() - 0.5f),
        pitchDeg = truth.pitchDeg + noise * (rng.nextFloat() - 0.5f),
        yawDeg = truth.yawDeg + noise * (rng.nextFloat() - 0.5f),
        forwardDeg = truth.forwardDeg + noise * (rng.nextFloat() - 0.5f),
        shoulderTiltDeg = truth.shoulderTiltDeg + noise * (rng.nextFloat() - 0.5f),
        lumFace = truth.lumFace + noise * 8f * (rng.nextFloat() - 0.5f),
        lumBackground = truth.lumBackground + noise * 8f * (rng.nextFloat() - 0.5f),
    )

    @Test
    fun `字段完整率100——全部数值字段产出且样本数正确`() {
        val rng = Random(42)
        val frames = (0 until 160).map { frame(it * 33L, 0.02f, rng) }
        val baseline = BaselineCalculator.compute(frames, "desk", 123L)
        assertNotNull(baseline)
        baseline!!
        // 完整率 100%：9 个字段全部为有效值
        assertTrue(baseline.faceScale > 0)
        assertTrue(baseline.sampleCount == 160)
        assertEquals("desk", baseline.sceneTag)
        assertEquals(123L, baseline.createdAtWall)
        assertTrue(baseline.confidence > 0.9f)
        // 均值近似真值（噪声均值≈0）
        assertTrue(kotlin.math.abs(baseline.pitchDeg - truth.pitchDeg) < 0.5f)
    }

    @Test
    fun `重建偏差小于5——带噪声帧的基线重建`() {
        val rng = Random(7)
        val frames = (0 until 200).map { frame(it * 33L, 0.04f, rng) } // ±2% 噪声
        val baseline = BaselineCalculator.compute(frames, "desk", 1L)!!
        val err = BaselineCalculator.reconstructionErrorPercent(truth, baseline)
        assertTrue("重建偏差 $err% 应 <5%", err < 5f)
    }

    @Test
    fun `中位数抗离群——少数异常帧不影响基线`() {
        val rng = Random(1)
        val frames = (0 until 160).map { frame(it * 33L, 0.02f, rng) }.toMutableList()
        repeat(5) { frames[it] = frames[it].copy(faceScale = 0.9f, pitchDeg = 60f) } // 5 帧检测跳变
        val baseline = BaselineCalculator.compute(frames, "desk", 1L)!!
        assertTrue(kotlin.math.abs(baseline.faceScale - truth.faceScale) < 0.01f)
        assertTrue(kotlin.math.abs(baseline.pitchDeg - truth.pitchDeg) < 1f)
    }

    @Test
    fun `帧数不足或无有效帧返回null——禁止残缺基线落库`() {
        val rng = Random(3)
        val short = (0 until 100).map { frame(it * 33L, 0.02f, rng) }
        assertNull(BaselineCalculator.compute(short, "desk", 1L))
        val lowConf = (0 until 160).map { frame(it * 33L, 0.02f, rng).copy(conf = 0.3f) }
        assertNull(BaselineCalculator.compute(lowConf, "desk", 1L))
    }
}
