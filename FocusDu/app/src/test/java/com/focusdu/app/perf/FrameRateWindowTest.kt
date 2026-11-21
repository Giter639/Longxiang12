package com.focusdu.app.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateWindowTest {

    @Test
    fun computesRateFromMonotonicTimestamps() {
        val window = FrameRateWindow(windowMs = 5_000)
        var fps = 0f
        repeat(11) { index ->
            fps = window.record(index * 100L)
        }
        assertEquals(10f, fps, 0.01f)
    }

    @Test
    fun dropsFramesOutsideWindow() {
        val window = FrameRateWindow(windowMs = 1_000)
        repeat(11) { index -> window.record(index * 100L) }
        repeat(10) { index -> window.record(1_100L + index * 100L) }
        assertTrue(window.currentFps(2_000L) in 9.9f..10.1f)
    }
}
