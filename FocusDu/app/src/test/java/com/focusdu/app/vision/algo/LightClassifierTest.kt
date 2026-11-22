package com.focusdu.app.vision.algo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2-04 验收锚点：六类场景判定规则（X15 场景样本入库后做一致率回归）。
 */
class LightClassifierTest {

    @Test
    fun `六类典型样本分类正确`() {
        assertEquals(LightClassifier.CLASS_NORMAL,
            LightClassifier.classify(128f, 96f, 110f, 0.02f, 12f))
        assertEquals(LightClassifier.CLASS_NIGHT,
            LightClassifier.classify(50f, 25f, 30f, 0.01f, 10f))
        assertEquals(LightClassifier.CLASS_GLARE,
            LightClassifier.classify(140f, 120f, 110f, 0.25f, 20f))
        assertEquals(LightClassifier.CLASS_BACKLIGHT,
            LightClassifier.classify(60f, 90f, 200f, 0.05f, 15f))
        assertEquals(LightClassifier.CLASS_DARK,
            LightClassifier.classify(55f, 60f, 90f, 0.02f, 12f))
        assertEquals(LightClassifier.CLASS_UNEVEN,
            LightClassifier.classify(120f, 100f, 110f, 0.02f, 80f))
    }

    @Test
    fun `优先级——夜间压过眩光与偏暗`() {
        assertEquals(LightClassifier.CLASS_NIGHT,
            LightClassifier.classify(45f, 20f, 30f, 0.20f, 15f))
    }

    @Test
    fun `边界值——恰好低于阈值不触发`() {
        assertEquals(LightClassifier.CLASS_NORMAL,
            LightClassifier.classify(70f, 60f, 110f, 0.15f, 60f)) // 全部在阈值上（不含）
    }
}
