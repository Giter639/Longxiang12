package com.focusdu.app.imu

import com.focusdu.app.core.FakeMonotonicClock
import com.focusdu.app.vision.protocol.FusionState
import com.focusdu.app.vision.protocol.VisionSample
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** X26 验收锚点：主备切换仲裁规则（改规则必须同步改这里） */
class VisionImuArbiterTest {

    private fun vision(tsMs: Long, state: FusionState = FusionState.NORMAL, conf: Float = 0.94f) =
        ArbiterTestSamples.vision(tsMs, state, conf)

    private fun imu(tsMs: Long, state: ImuState = ImuState.NORMAL) =
        ArbiterTestSamples.imu(tsMs, state)

    /** 订阅仲裁流并喂样本的测试脚手架：返回收集到的仲裁样本 */
    private fun TestScope.collectArbitrated(
        a: VisionImuArbiter,
        block: VisionImuArbiter.() -> Unit,
    ): List<VisionSample> {
        val got = mutableListOf<VisionSample>()
        backgroundScope.launch(UnconfinedTestDispatcher()) { a.arbitrated.collect { got.add(it) } }
        a.block()
        return got
    }

    @Test
    fun `视觉健康时 IMU 不接管`() = runTest {
        val clock = FakeMonotonicClock()
        val a = VisionImuArbiter(clock, VisionImuArbiter.Config(visionFreshMs = 3000, imuFreshMs = 2000))
        val got = collectArbitrated(a) {
            onVisionSample(vision(0))
            clock.advance(500); onImuSample(imu(500))
            clock.advance(500); onVisionSample(vision(1000))
        }
        assertEquals(listOf(FusionState.NORMAL, FusionState.NORMAL), got.map { it.state })
        assertEquals(2, got.size)   // IMU 样本没进仲裁流
    }

    @Test
    fun `视觉断流3秒后 IMU 兜底接管`() = runTest {
        val clock = FakeMonotonicClock()
        val a = VisionImuArbiter(clock, VisionImuArbiter.Config(visionFreshMs = 3000, imuFreshMs = 2000))
        val got = collectArbitrated(a) {
            onVisionSample(vision(0))
            clock.advance(2800); onImuSample(imu(2800))                    // 未超 3s，不接管
            clock.advance(700); onImuSample(imu(3500, ImuState.HEAD_DOWN)) // 超过 3s → 接管并映射
        }
        assertEquals(listOf(FusionState.NORMAL, FusionState.HEAD_DOWN), got.map { it.state })
    }

    @Test
    fun `低置信视觉样本不算新鲜 遮挡时 IMU 接管`() = runTest {
        val clock = FakeMonotonicClock()
        val a = VisionImuArbiter(clock, VisionImuArbiter.Config(visionFreshMs = 3000, imuFreshMs = 2000))
        val got = collectArbitrated(a) {
            onVisionSample(vision(0, conf = 0.3f))              // 遮挡：不刷新健康度
            clock.advance(3500); onImuSample(imu(3500, ImuState.LEAN))
        }
        assertEquals(listOf(FusionState.HEAD_DOWN), got.map { it.state })   // 侧趴→HEAD_DOWN 映射
    }

    @Test
    fun `恢复即切回 视觉样本立即生效`() = runTest {
        val clock = FakeMonotonicClock()
        val a = VisionImuArbiter(clock, VisionImuArbiter.Config(visionFreshMs = 3000, imuFreshMs = 2000))
        val got = collectArbitrated(a) {
            onVisionSample(vision(0))
            clock.advance(3500); onImuSample(imu(3500, ImuState.AWAY))   // IMU 接管
            clock.advance(100); onVisionSample(vision(3600))             // 视觉恢复 → 立即切回
            clock.advance(100); onImuSample(imu(3700, ImuState.HEAD_DOWN))   // IMU 回到 standby，不再进流
        }
        assertEquals(listOf(FusionState.NORMAL, FusionState.AWAY, FusionState.NORMAL), got.map { it.state })
    }

    @Test
    fun `双源都断流 仲裁流静默 交给防抖的检测中断`() = runTest {
        val clock = FakeMonotonicClock()
        val a = VisionImuArbiter(clock, VisionImuArbiter.Config(visionFreshMs = 3000, imuFreshMs = 2000))
        val got = collectArbitrated(a) {
            onVisionSample(vision(0))
            clock.advance(500); onImuSample(imu(500))                      // 视觉健康，IMU standby
            clock.advance(5000); onImuSample(imu(5500, ImuState.HEAD_DOWN)) // 视觉断流但 IMU 也断了，不接管
        }
        assertEquals(1, got.size)   // 只有最早那条视觉样本；之后静默
        assertEquals(FusionState.NORMAL, got[0].state)
    }

    @Test
    fun `接管期间 IMU 样本经桥映射透传`() = runTest {
        val clock = FakeMonotonicClock()
        val a = VisionImuArbiter(clock, VisionImuArbiter.Config(visionFreshMs = 3000, imuFreshMs = 2000))
        val got = collectArbitrated(a) {
            onVisionSample(vision(0))
            clock.advance(3500); onImuSample(imu(3500, ImuState.FIDGET))
        }
        // FIDGET 映射 NORMAL（不伪造视觉语义，X22 口径）；首条 NORMAL 是切回前的视觉样本
        assertEquals(listOf(FusionState.NORMAL, FusionState.NORMAL), got.map { it.state })
    }
}

/** 测试样本工厂：字段与实现/Mock 引擎同构 */
private object ArbiterTestSamples {
    fun vision(tsMs: Long, state: FusionState, conf: Float) = VisionSample(
        tsMs = tsMs, seq = tsMs / 500,
        facePresent = state != FusionState.AWAY, conf = conf,
        faceScale = 0.3f, headPitchDeg = 8f, headYawDeg = 3f, headForwardDeg = 4f,
        shoulderTiltDeg = 2f, eyeOpenRatio = 0.9f, blinkRatePerMin = 16f, mouthOpenRatio = 0.05f,
        lumFace = 128f, lumDesktop = 96f, lumBackground = 110f,
        overexposureRatio = 0.02f, leftRightDelta = 12f, lightClass = "normal",
        atDesk = state != FusionState.AWAY, offScreenRatio = 0.05f, headStability = 0.93f,
        distanceBand = "normal", postureEvent = "none", drowsiness = "none", state = state,
    )

    fun imu(tsMs: Long, state: ImuState) = ImuSample(
        tsMs = tsMs, seq = tsMs / 40,
        axMg = 0f, ayMg = 0f, azMg = 1000f, gxDps = 0f, gyDps = 0f, gzDps = 0f,
        pitchDeg = 8f, rollDeg = 3f, motionRms = 0.08f,
        state = state, conf = 0.94f,
    )
}
