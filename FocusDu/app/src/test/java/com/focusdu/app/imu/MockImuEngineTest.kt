package com.focusdu.app.imu

import com.focusdu.app.core.FakeMonotonicClock
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** X22 验收锚点：Mock 脚本周期与样本流节奏 */
class MockImuEngineTest {

    @Test
    fun `脚本64秒周期 按时段输出五态`() {
        assertEquals(ImuState.NORMAL, MockImuEngine.scriptState(0L))     // t0 正常
        assertEquals(ImuState.HEAD_DOWN, MockImuEngine.scriptState(16L)) // t8 低头开始
        assertEquals(ImuState.HEAD_DOWN, MockImuEngine.scriptState(43L)) // t21 低头结束
        assertEquals(ImuState.NORMAL, MockImuEngine.scriptState(44L))    // t22 恢复
        assertEquals(ImuState.LEAN, MockImuEngine.scriptState(52L))      // t26 侧趴
        assertEquals(ImuState.FIDGET, MockImuEngine.scriptState(80L))    // t40 乱动
        assertEquals(ImuState.AWAY, MockImuEngine.scriptState(108L))     // t54 离座
        assertEquals(ImuState.NORMAL, MockImuEngine.scriptState(120L))   // t60 恢复
        assertEquals(ImuState.NORMAL, MockImuEngine.scriptState(128L))   // 周期回卷
    }

    @Test
    fun `样本流500ms一帧 seq递增且置信度可驱动下游`() = runTest {
        val engine = MockImuEngine(FakeMonotonicClock(), backgroundScope)
        val got = mutableListOf<ImuSample>()
        val job = launch { engine.samples().take(6).toList(got) }
        advanceTimeBy(500L * 6)
        job.join()

        assertEquals((0L..5L).toList(), got.map { it.seq })
        assertTrue(got.all { it.state == ImuState.NORMAL })   // 前 8 秒都是正常态
        assertTrue(got.all { it.conf >= 0.5f })
    }

    @Test
    fun `mock样本字段与判定态一致`() {
        val engine = MockImuEngine(FakeMonotonicClock(), TestScope())
        val headDown = engine.mockSample(ImuState.HEAD_DOWN, 20L)
        assertEquals(30f, headDown.pitchDeg)
        val fidget = engine.mockSample(ImuState.FIDGET, 80L)
        assertTrue(fidget.motionRms > 0.3f)
        val away = engine.mockSample(ImuState.AWAY, 108L)
        assertTrue(away.motionRms < 0.05f)
    }
}
