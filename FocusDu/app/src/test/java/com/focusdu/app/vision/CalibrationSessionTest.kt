package com.focusdu.app.vision

import com.focusdu.app.core.FakeMonotonicClock
import com.focusdu.app.vision.protocol.FusionState
import com.focusdu.app.vision.protocol.VisionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSessionTest {

    @Test
    fun completesAfter150ValidFramesAndFiveSeconds() {
        val clock = FakeMonotonicClock()
        val session = CalibrationSession(clock)
        session.start(clock.nowMs())

        repeat(150) { index ->
            clock.advance(66)
            if (index % 8 == 0) {
                session.onVisionSample(sample(clock.nowMs()))
            }
            session.onFrame(frame(clock.nowMs()))
            session.tick(clock.nowMs())
        }

        val state = session.state.value as CalibrationState.Completed
        assertTrue(state.frameCount >= 150)
        assertTrue(state.activeValidMs >= 5_000)
    }

    @Test
    fun occlusionPausesAndThenResumesWithoutResettingProgress() {
        val clock = FakeMonotonicClock()
        val session = CalibrationSession(clock)
        session.start(clock.nowMs())
        clock.advance(100)
        session.onVisionSample(sample(clock.nowMs()))
        repeat(20) {
            clock.advance(66)
            session.onFrame(frame(clock.nowMs()))
            session.tick(clock.nowMs())
        }
        val before = (session.state.value as CalibrationState.Running).frameCount

        clock.advance(100)
        session.onVisionSample(sample(clock.nowMs(), facePresent = false))
        repeat(8) {
            clock.advance(66)
            session.onFrame(frame(clock.nowMs()))
            session.tick(clock.nowMs())
        }
        val paused = session.state.value as CalibrationState.Running
        assertEquals(before, paused.frameCount)
        assertEquals(CalibrationIssue.OCCLUDED, paused.issue)

        clock.advance(100)
        session.onVisionSample(sample(clock.nowMs()))
        repeat(10) {
            clock.advance(66)
            session.onFrame(frame(clock.nowMs()))
            session.tick(clock.nowMs())
        }
        val resumed = session.state.value as CalibrationState.Running
        assertTrue(resumed.frameCount > before)
    }

    @Test
    fun lowConfidenceIsReportedAsAnIssue() {
        val clock = FakeMonotonicClock()
        val session = CalibrationSession(clock)
        session.start(clock.nowMs())
        clock.advance(100)
        session.onVisionSample(sample(clock.nowMs(), confidence = 0.2f))
        clock.advance(450)
        session.tick(clock.nowMs())

        val state = session.state.value as CalibrationState.Running
        assertEquals(CalibrationIssue.LOW_CONFIDENCE, state.issue)
    }

    @Test
    fun cameraMovementIsReportedAndBlocksFrames() {
        val clock = FakeMonotonicClock()
        val session = CalibrationSession(clock)
        session.start(clock.nowMs())
        clock.advance(100)
        session.onVisionSample(sample(clock.nowMs()))
        clock.advance(66)
        session.onFrame(frame(clock.nowMs(), moving = true))
        clock.advance(450)
        session.tick(clock.nowMs())

        val state = session.state.value as CalibrationState.Running
        assertEquals(CalibrationIssue.CAMERA_MOVING, state.issue)
        assertEquals(0, state.frameCount)
    }

    @Test
    fun streamInterruptionIsReported() {
        val clock = FakeMonotonicClock()
        val session = CalibrationSession(clock)
        session.start(clock.nowMs())
        clock.advance(1_600)
        session.tick(clock.nowMs())

        val state = session.state.value as CalibrationState.Running
        assertEquals(CalibrationIssue.STREAM_INTERRUPTED, state.issue)
    }

    @Test
    fun insufficientFramesFailsAfterTenActiveSeconds() {
        val clock = FakeMonotonicClock()
        val session = CalibrationSession(clock)
        session.start(clock.nowMs())

        repeat(80) { index ->
            clock.advance(125)
            if (index % 4 == 0) {
                session.onVisionSample(sample(clock.nowMs()))
            }
            session.onFrame(frame(clock.nowMs()))
            session.tick(clock.nowMs())
        }

        val state = session.state.value as CalibrationState.Failed
        assertEquals(CalibrationIssue.INSUFFICIENT_FRAMES, state.reason)
    }

    private fun frame(now: Long, moving: Boolean = false) = CameraFrame(
        tsMs = now,
        seq = now,
        width = 640,
        height = 480,
        rotationDegrees = 0,
        isFrontFacing = true,
        isDeviceMoving = moving,
        nv21 = byteArrayOf(),
    )

    private fun sample(
        now: Long,
        facePresent: Boolean = true,
        confidence: Float = 0.9f,
    ) = VisionSample(
        tsMs = now,
        seq = now,
        facePresent = facePresent,
        conf = confidence,
        faceScale = 0.3f,
        headPitchDeg = 8f,
        headYawDeg = 3f,
        headForwardDeg = 4f,
        shoulderTiltDeg = 2f,
        eyeOpenRatio = 0.9f,
        blinkRatePerMin = 16f,
        mouthOpenRatio = 0.05f,
        lumFace = 128f,
        lumDesktop = 96f,
        lumBackground = 110f,
        overexposureRatio = 0.02f,
        leftRightDelta = 12f,
        lightClass = "normal",
        atDesk = true,
        offScreenRatio = 0.05f,
        headStability = 0.93f,
        distanceBand = "normal",
        postureEvent = "none",
        drowsiness = "none",
        state = FusionState.NORMAL,
    )
}
