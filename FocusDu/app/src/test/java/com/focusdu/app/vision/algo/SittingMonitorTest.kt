package com.focusdu.app.vision.algo

import com.focusdu.app.reminder.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-03 验收锚点：久坐升级投递、离座确认、归来重置、恢复校验。
 */
class SittingMonitorTest {

    @Test
    fun `久坐45分钟投递moderate、60分钟投递severe`() {
        val s = SittingMonitor()
        var st = s.update(0, true)
        // 快进：500ms 一帧到 46 分钟
        val lightAt = 45 * 60_000L
        val severeAt = 60 * 60_000L
        var ts = 500L
        while (ts <= severeAt + 1_000) {
            st = s.update(ts, true)
            ts += 500
        }
        assertTrue(st.sedentaryMs >= severeAt)
        assertEquals(RiskLevel.SEVERE, s.sedentaryEvent?.level)
        assertTrue(s.sedentaryEvent!!.durationMs >= severeAt)
    }

    @Test
    fun `离座确认——1分钟不算离座，3分钟算`() {
        val s = SittingMonitor()
        s.update(0, true)
        var st = s.update(60_000, false)
        assertFalse("1 分钟 < 2 分钟确认阈值", st.away)
        st = s.update(200_000, false) // 离座 200s - 起始 60s = 140s ≥ 120s
        assertTrue(st.away)
        assertTrue(st.awayMs >= 120_000)
    }

    @Test
    fun `归来重置久坐计时并标记justReturned`() {
        val s = SittingMonitor()
        s.update(0, true)
        var ts = 500L
        var st = s.update(0, true)
        while (ts < 10 * 60_000L) { st = s.update(ts, true); ts += 500 } // 伏案 10 分钟
        s.update(ts, false)            // 离座
        ts += 3 * 60_000L              // 离开 3 分钟
        s.update(ts, false)
        ts += 500
        st = s.update(ts, true)        // 归来
        assertTrue(st.justReturned)
        assertTrue("归来应重置久坐计时", st.sedentaryMs <= 1_000)
        assertEquals(null, s.sedentaryEvent)
    }

    @Test
    fun `恢复校验——距离档位与俯仰均回到基线附近`() {
        val s = SittingMonitor()
        val baseline = com.focusdu.app.vision.PersonalBaseline(
            faceScale = 0.30f, pitchDeg = 8f, yawDeg = 3f, forwardDeg = 4f,
            shoulderTiltDeg = 2f, lumFace = 128f, lumBackground = 110f,
            confidence = 0.95f, sampleCount = 160, createdAtWall = 0L, sceneTag = "desk",
        )
        s.update(0, true)
        s.update(60_000, false)
        s.update(200_000, false) // 正式离座
        s.update(200_500, true)  // 归来
        val ok = s.checkRecovery(201_000, "normal", 10f, baseline)
        assertEquals(true, ok)
        val notOk = s.checkRecovery(201_500, "near", 10f, baseline)
        assertEquals(false, notOk)
    }
}
