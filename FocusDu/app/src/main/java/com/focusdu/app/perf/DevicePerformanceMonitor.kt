package com.focusdu.app.perf

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.vision.CameraFrame
import com.focusdu.app.vision.CameraStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PerformanceState {
    IDLE,
    RUNNING,
    COMPLETED,
}

data class PerformanceSnapshot(
    val state: PerformanceState = PerformanceState.IDLE,
    val elapsedMs: Long = 0L,
    val measuredFps: Float = 0f,
    val frames: Long = 0L,
    val batteryPercent: Int? = null,
    val batteryTempC: Float? = null,
)

/**
 * T7-04 设备端性能采集。帧率来自 CameraStreamer 的实际输出；
 * 电池温度取 BatteryManager 的电池温度，不等同于芯片温度，报告中必须如实标注。
 */
class DevicePerformanceMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val cameraStreamer: CameraStreamer,
) {
    private val _state = MutableStateFlow(PerformanceSnapshot())
    val state: StateFlow<PerformanceSnapshot> = _state.asStateFlow()

    private var job: Job? = null

    fun bindToStreaming() {
        scope.launch {
            cameraStreamer.streaming.collect { streaming ->
                if (streaming) start() else stop()
            }
        }
    }

    fun start(durationMs: Long = DEFAULT_DURATION_MS) {
        if (job?.isActive == true) return
        val startedAt = clock.nowMs()
        val frameWindow = FrameRateWindow()
        var frameCount = 0L

        job = scope.launch {
            val frameJob = launch {
                cameraStreamer.frames().collect { frame: CameraFrame ->
                    frameCount++
                    frameWindow.record(frame.tsMs)
                }
            }
            try {
                while (isActive && clock.nowMs() - startedAt < durationMs) {
                    val battery = readBattery()
                    val snapshot = PerformanceSnapshot(
                        state = PerformanceState.RUNNING,
                        elapsedMs = clock.nowMs() - startedAt,
                        measuredFps = frameWindow.currentFps(clock.nowMs()),
                        frames = frameCount,
                        batteryPercent = battery.first,
                        batteryTempC = battery.second,
                    )
                    _state.value = snapshot
                    Log.i(
                        LOG_TAG,
                        "fps=${"%.1f".format(snapshot.measuredFps)} " +
                            "frames=${snapshot.frames} battery=${snapshot.batteryPercent}% " +
                            "batteryTempC=${snapshot.batteryTempC}",
                    )
                    delay(SAMPLE_INTERVAL_MS)
                }
            } finally {
                frameJob.cancel()
                val battery = readBattery()
                _state.value = PerformanceSnapshot(
                    state = PerformanceState.COMPLETED,
                    elapsedMs = clock.nowMs() - startedAt,
                    measuredFps = frameWindow.currentFps(clock.nowMs()),
                    frames = frameCount,
                    batteryPercent = battery.first,
                    batteryTempC = battery.second,
                )
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun readBattery(): Pair<Int?, Float?> {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null to null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.div(10f)
        return percent to temp
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 30 * 60_000L
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val LOG_TAG = "R3_PERF"
    }
}
