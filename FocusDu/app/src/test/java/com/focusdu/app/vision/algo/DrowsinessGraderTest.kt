package com.focusdu.app.vision.algo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2-09 验收锚点：三级均可触发，规则可解释。
 */
class DrowsinessGraderTest {

    private fun feats(
        closedMs: Long = 0,
        slowBlink: Boolean = false,
        nodCount: Int = 0,
        yawn: Boolean = false,
    ) = DrowsinessDetector.Features(closedMs, slowBlink, nodCount, yawn)

    @Test
    fun `severe——连续闭眼4秒以上`() {
        assertEquals("severe", DrowsinessGrader.grade(feats(closedMs = 4_000)))
        assertEquals("severe", DrowsinessGrader.grade(feats(closedMs = 2_600, slowBlink = true)))
    }

    @Test
    fun `moderate——闭眼2秒或点头2次或变慢加哈欠`() {
        assertEquals("moderate", DrowsinessGrader.grade(feats(closedMs = 2_000)))
        assertEquals("moderate", DrowsinessGrader.grade(feats(nodCount = 2)))
        assertEquals("moderate", DrowsinessGrader.grade(feats(slowBlink = true, yawn = true)))
    }

    @Test
    fun `mild——任一轻度特征`() {
        assertEquals("mild", DrowsinessGrader.grade(feats(slowBlink = true)))
        assertEquals("mild", DrowsinessGrader.grade(feats(yawn = true)))
        assertEquals("mild", DrowsinessGrader.grade(feats(nodCount = 1)))
        assertEquals("mild", DrowsinessGrader.grade(feats(closedMs = 1_000)))
    }

    @Test
    fun `none——全部特征安静`() {
        assertEquals("none", DrowsinessGrader.grade(feats()))
    }

    @Test
    fun `升级路径——从mild到moderate到severe单调可解释`() {
        var f = feats(closedMs = 1_000)
        assertEquals("mild", DrowsinessGrader.grade(f))
        f = f.copy(closedMs = 2_500)
        assertEquals("moderate", DrowsinessGrader.grade(f))
        f = f.copy(closedMs = 4_500)
        assertEquals("severe", DrowsinessGrader.grade(f))
    }
}
