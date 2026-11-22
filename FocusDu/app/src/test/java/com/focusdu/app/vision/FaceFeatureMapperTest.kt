package com.focusdu.app.vision

import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * R4 真引擎的纯映射层锚点：欧拉角分解往返、faceScale（与 Mock 0.30 口径对齐）、
 * blendshape → 眼口指标、三区亮度统计。
 */
class FaceFeatureMapperTest {

    private fun lm(x: Float, y: Float, z: Float = 0f) =
        NormalizedLandmark.create(x, y, z)

    private fun cat(name: String, score: Float) =
        Category.create(score, 0, name, "")

    /**
     * 按映射层分解约定构造 4x4 列主序旋转矩阵：
     * 分解满足 yaw=asin(r20)、pitch=atan2(r21,r22)、forward=atan2(r10,r00)
     * ⇒ 取 α=forward, γ=pitch, β=−yaw 的 Rz(α)·Ry(β)·Rx(γ)。
     */
    private fun rotationFromEuler(pitchDeg: Double, yawDeg: Double, forwardDeg: Double): FloatArray {
        val p = Math.toRadians(pitchDeg); val y = Math.toRadians(yawDeg); val f = Math.toRadians(forwardDeg)
        val cA = cos(f); val sA = sin(f)
        val cB = cos(-y); val sB = sin(-y)
        val cG = cos(p); val sG = sin(p)
        // R = Rz(f)·Ry(-yaw)·Rx(pitch)，元素按 ZYX 展开式
        val r00 = cA * cB; val r10 = sA * cB; val r20 = -sB
        val r21 = cB * sG; val r22 = cB * cG
        val r01 = cA * sB * sG - sA * cG; val r02 = cA * sB * cG + sA * sG
        val r11 = sA * sB * sG + cA * cG; val r12 = sA * sB * cG - cA * sG
        // 列主序 4x4：R[row][col] = m[col*4 + row]
        return floatArrayOf(
            r00.toFloat(), r10.toFloat(), r20.toFloat(), 0f,
            r01.toFloat(), r11.toFloat(), r21.toFloat(), 0f,
            r02.toFloat(), r12.toFloat(), r22.toFloat(), 0f,
            0f, 0f, 0f, 1f,
        )
    }

    @Test
    fun `欧拉角分解往返——多组角度误差小于0_1度`() {
        val cases = listOf(
            Triple(8.0, 3.0, 4.0),      // 正常坐姿
            Triple(28.0, 5.0, 15.0),    // 低头
            Triple(-12.0, -18.0, 2.0),  // 抬头+转头
            Triple(0.0, 0.0, 0.0),
        )
        for ((pitch, yaw, forward) in cases) {
            val m = rotationFromEuler(pitch, yaw, forward)
            val a = FaceFeatureMapper.eulerFromTransform(m)
            assertEquals(pitch, a.pitchDeg.toDouble(), 0.1)
            assertEquals(yaw, a.yawDeg.toDouble(), 0.1)
            assertEquals(forward, a.forwardDeg.toDouble(), 0.1)
        }
    }

    @Test
    fun `faceScale——双眼外眦距离除以图宽（对齐Mock正常值0_30）`() {
        val lms = MutableList(478) { lm(0f, 0f) }
        lms[FaceFeatureMapper.IDX_LEFT_EYE_OUTER] = lm(0.2f, 0.5f)  // 左眼外眦
        lms[FaceFeatureMapper.IDX_RIGHT_EYE_OUTER] = lm(0.5f, 0.5f) // 右眼外眦
        val scale = FaceFeatureMapper.faceScale(lms, 100, 200)
        assertEquals(0.30f, scale, 0.001f)
    }

    @Test
    fun `blendshape映射——眨眼均值取补、jawOpen直通`() {
        val (eyeOpen, mouth) = FaceFeatureMapper.eyeAndMouthFromBlendshapes(
            listOf(
                cat(FaceFeatureMapper.BS_EYE_BLINK_LEFT, 0.9f),
                cat(FaceFeatureMapper.BS_EYE_BLINK_RIGHT, 0.1f),
                cat(FaceFeatureMapper.BS_JAW_OPEN, 0.5f),
            ),
        )
        assertEquals(0.5f, eyeOpen, 0.01f)
        assertEquals(0.5f, mouth, 0.01f)
    }

    @Test
    fun `blendshape缺省——全开全闭边界`() {
        val (open, closed) = FaceFeatureMapper.eyeAndMouthFromBlendshapes(
            listOf(
                cat(FaceFeatureMapper.BS_EYE_BLINK_LEFT, 1f),
                cat(FaceFeatureMapper.BS_EYE_BLINK_RIGHT, 1f),
                cat(FaceFeatureMapper.BS_JAW_OPEN, 0f),
            ),
        )
        assertEquals(0f, open, 0.001f) // 全闭眼
        assertEquals(0f, closed, 0.001f)
    }

    @Test
    fun `faceBox——关键点包围盒转像素坐标`() {
        val lms = listOf(lm(0.2f, 0.3f), lm(0.4f, 0.1f), lm(0.3f, 0.6f))
        val box = FaceFeatureMapper.faceBox(lms, 100, 200)!!
        assertEquals(20, box.left)
        assertEquals(20, box.top)
        assertEquals(40, box.right)
        assertEquals(120, box.bottom)
        assertNull(FaceFeatureMapper.faceBox(emptyList(), 100, 200))
    }

    @Test
    fun `亮度统计——三区均值与左右差`() {
        // 160x120：上 1/4=40（背景暗），下 1/3=100（桌面中），中间=200（脸亮）
        val w = 160; val h = 120
        val gray = IntArray(w * h) { i ->
            val y = i / w
            when {
                y < h / 4 -> 40
                y >= h * 2 / 3 -> 100
                else -> 200
            }
        }
        val stats = FaceFeatureMapper.lumStats(gray, w, h, FaceFeatureMapper.FaceBox(0, h / 4, w - 1, h * 2 / 3 - 1))
        assertEquals(40f, stats.lumBackground, 0.1f)
        assertEquals(200f, stats.lumFace, 0.1f)
        assertEquals(100f, stats.lumDesktop, 0.1f)
        assertEquals(0f, stats.overexposureRatio, 0.001f)
        // 左右完全对称 → 左右差 0
        assertEquals(0f, stats.leftRightDelta, 0.1f)
    }

    @Test
    fun `过曝比例与左右差`() {
        val w = 10; val h = 10
        val gray = IntArray(w * h) { i ->
            val x = i % w
            if (x < 5) 255 else 10 // 左半全过曝、右半暗
        }
        val stats = FaceFeatureMapper.lumStats(gray, w, h, null)
        assertEquals(0.5f, stats.overexposureRatio, 0.001f)
        assertTrue("左右差应显著", stats.leftRightDelta > 100f)
    }

    @Test
    fun `正常坐姿样本的完整映射链——特征字段全部产出`() {
        // 33/263 两点 + 矩阵 → 全字段可算，无 NaN
        val lms = List(300) { lm(0.5f, 0.5f) }.toMutableList()
        lms[FaceFeatureMapper.IDX_LEFT_EYE_OUTER] = lm(0.2f, 0.5f)
        lms[FaceFeatureMapper.IDX_RIGHT_EYE_OUTER] = lm(0.5f, 0.5f)
        val scale = FaceFeatureMapper.faceScale(lms, 100, 200)
        val box = FaceFeatureMapper.faceBox(lms, 100, 200)
        val angles = FaceFeatureMapper.eulerFromTransform(rotationFromEuler(8.0, 3.0, 4.0))
        assertNotNull(box)
        assertTrue(scale > 0f)
        assertTrue(!angles.pitchDeg.isNaN() && !angles.yawDeg.isNaN() && !angles.forwardDeg.isNaN())
    }
}
