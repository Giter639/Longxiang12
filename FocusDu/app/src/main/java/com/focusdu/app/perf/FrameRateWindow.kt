package com.focusdu.app.perf

import java.util.ArrayDeque

/** 最近一段时间的帧率窗口；只依赖单调时间，避免息屏后时间漂移。 */
class FrameRateWindow(
    private val windowMs: Long = 5_000,
) {
    private val timestamps = ArrayDeque<Long>()

    fun record(nowMs: Long): Float {
        timestamps.addLast(nowMs)
        trim(nowMs)
        return currentFps()
    }

    fun currentFps(nowMs: Long? = null): Float {
        nowMs?.let(::trim)
        if (timestamps.size < 2) return 0f
        val spanMs = (timestamps.last - timestamps.first).coerceAtLeast(1L)
        return (timestamps.size - 1) * 1_000f / spanMs
    }

    private fun trim(nowMs: Long) {
        while (timestamps.isNotEmpty() && timestamps.first < nowMs - windowMs) {
            timestamps.removeFirst()
        }
    }

    fun reset() {
        timestamps.clear()
    }
}
