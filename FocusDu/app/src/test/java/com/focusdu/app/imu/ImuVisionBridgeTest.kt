package com.focusdu.app.imu

import com.focusdu.app.vision.protocol.FusionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** X22 验收锚点：IMU→六态映射口径（改映射必须同步改这里，R4 定稿） */
class ImuVisionBridgeTest {

    @Test
    fun `五态到六态映射口径`() {
        assertEquals(FusionState.NORMAL, ImuVisionBridge.mapState(ImuState.NORMAL))
        assertEquals(FusionState.NORMAL, ImuVisionBridge.mapState(ImuState.FIDGET))   // 不伪造视觉语义
        assertEquals(FusionState.HEAD_DOWN, ImuVisionBridge.mapState(ImuState.HEAD_DOWN))
        assertEquals(FusionState.HEAD_DOWN, ImuVisionBridge.mapState(ImuState.LEAN))  // 侧趴归 POSTURE
        assertEquals(FusionState.AWAY, ImuVisionBridge.mapState(ImuState.AWAY))
    }

    @Test
    fun `低头样本透传姿态特征并标记postureEvent`() {
        val src = ImuSample(
            tsMs = 1234L, seq = 7L,
            axMg = 0f, ayMg = 0f, azMg = 1000f, gxDps = 0f, gyDps = 0f, gzDps = 0f,
            pitchDeg = 30f, rollDeg = 3f, motionRms = 0.08f,
            state = ImuState.HEAD_DOWN, conf = 0.91f,
        )
        val out = ImuVisionBridge.toVisionSample(src)
        assertEquals(FusionState.HEAD_DOWN, out.state)
        assertEquals("headDown", out.postureEvent)
        assertEquals(0.91f, out.conf)
        assertEquals(30f, out.headPitchDeg)
        assertTrue(out.facePresent)
        assertTrue(out.atDesk)
        assertEquals("normal", out.distanceBand)
        assertEquals("none", out.drowsiness)
    }

    @Test
    fun `侧趴样本横滚挂肩线字段`() {
        val src = ImuSample(
            tsMs = 0L, seq = 0L,
            axMg = 0f, ayMg = 0f, azMg = 1000f, gxDps = 0f, gyDps = 0f, gzDps = 0f,
            pitchDeg = 12f, rollDeg = 35f, motionRms = 0.08f,
            state = ImuState.LEAN, conf = 0.9f,
        )
        val out = ImuVisionBridge.toVisionSample(src)
        assertEquals(FusionState.HEAD_DOWN, out.state)
        assertEquals(35f, out.shoulderTiltDeg)
    }

    @Test
    fun `离座样本在场字段翻转`() {
        val src = ImuSample(
            tsMs = 0L, seq = 0L,
            axMg = 0f, ayMg = 0f, azMg = 950f, gxDps = 0f, gyDps = 0f, gzDps = 0f,
            pitchDeg = 8f, rollDeg = 3f, motionRms = 0.02f,
            state = ImuState.AWAY, conf = 0.95f,
        )
        val out = ImuVisionBridge.toVisionSample(src)
        assertEquals(FusionState.AWAY, out.state)
        assertFalse(out.facePresent)
        assertFalse(out.atDesk)
    }
}
