package com.focusdu.app.vision

import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.vision.protocol.VisionSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

enum class CalibrationIssue {
    OCCLUDED,
    LOW_CONFIDENCE,
    CAMERA_MOVING,
    STREAM_INTERRUPTED,
    INSUFFICIENT_FRAMES,
    CAMERA_UNAVAILABLE,
}

sealed interface CalibrationState {
    data object Idle : CalibrationState

    data class Running(
        val frameCount: Int,
        val requiredFrames: Int,
        val activeValidMs: Long,
        val requiredMs: Long,
        val progress: Float,
        val issue: CalibrationIssue?,
    ) : CalibrationState

    data class Completed(
        val frameCount: Int,
        val activeValidMs: Long,
    ) : CalibrationState

    data class Failed(
        val reason: CalibrationIssue,
        val frameCount: Int,
    ) : CalibrationState
}

/**
 * T1-02/T1-03 的纯逻辑状态机。
 *
 * 质量判定只消费 R3 能确定的输入：
 * - 人脸是否出现、R4 给出的置信度；
 * - R3 根据设备传感器判断的相机移动；
 * - 相机帧或算法样本是否断流。
 *
 * 异常期间不累计有效帧和有效时长；异常恢复后从原进度继续，不要求重新校准。
 */
class CalibrationSession(
    private val clock: MonotonicClock,
    private val config: Config = Config(),
) {
    data class Config(
        val requiredFrames: Int = 150,
        val requiredActiveMs: Long = 5_000,
        val maxActiveMs: Long = 10_000,
        val frameTimeoutMs: Long = 1_000,
        val sampleTimeoutMs: Long = 1_500,
        val issueConfirmMs: Long = 400,
    )

    private val _state = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var running = false
    private var validFrames = 0
    private var activeValidMs = 0L
    private var lastTickMs = 0L
    private var lastFrameAtMs = 0L
    private var lastVisionSampleAtMs = 0L
    private var latestSample: VisionSample? = null
    private var candidateIssue: CalibrationIssue? = null
    private var candidateSinceMs = 0L
    private var visibleIssue: CalibrationIssue? = null

    fun start(nowMs: Long = clock.nowMs()) {
        running = true
        validFrames = 0
        activeValidMs = 0L
        lastTickMs = nowMs
        lastFrameAtMs = nowMs
        lastVisionSampleAtMs = 0L
        latestSample = null
        candidateIssue = CalibrationIssue.STREAM_INTERRUPTED
        candidateSinceMs = nowMs
        visibleIssue = null
        publish(nowMs)
    }

    /**
     * 每计入一个有效帧时回调，参数是该帧时刻最近一次分析样本（有效帧的前提是已有分析样本，故非空）。
     * 接线方据此收集基线特征：基线按「采集帧」计数，与这里的帧数口径保持一致。
     */
    var onValidFrame: ((VisionSample) -> Unit)? = null

    fun onFrame(frame: CameraFrame) {
        if (!running) return
        lastFrameAtMs = frame.tsMs
        val issue = currentRawIssue(frame.tsMs, frame.isDeviceMoving)
        updateIssue(issue, frame.tsMs)
        if (issue == null) {
            validFrames++
            latestSample?.let { onValidFrame?.invoke(it) }
        }
        publish(frame.tsMs)
    }

    fun onVisionSample(sample: VisionSample) {
        if (!running) return
        latestSample = sample
        lastVisionSampleAtMs = sample.tsMs
        val cameraMoving = latestFrameMoving
        val issue = currentRawIssue(sample.tsMs, cameraMoving)
        updateIssue(issue, sample.tsMs)
        publish(sample.tsMs)
    }

    /** ViewModel 每 250ms 调用一次，用于识别断流并在异常暂停时停止累计时长。 */
    fun tick(nowMs: Long = clock.nowMs()) {
        if (!running) return
        val delta = (nowMs - lastTickMs).coerceIn(0L, MAX_TICK_STEP_MS)
        lastTickMs = nowMs
        val issue = currentRawIssue(nowMs, latestFrameMoving)
        updateIssue(issue, nowMs)
        if (issue == null) {
            activeValidMs += delta
        }
        publish(nowMs)
    }

    fun fail(reason: CalibrationIssue) {
        if (!running) return
        running = false
        _state.value = CalibrationState.Failed(reason, validFrames)
    }

    /** 离开 P03 时取消进行中的校准；已经完成的结果保留给 P04 使用。 */
    fun cancel() {
        if (_state.value is CalibrationState.Running) {
            running = false
            _state.value = CalibrationState.Idle
        }
    }

    private var latestFrameMoving = false

    private fun currentRawIssue(nowMs: Long, cameraMoving: Boolean): CalibrationIssue? {
        latestFrameMoving = cameraMoving
        val sample = latestSample ?: return CalibrationIssue.STREAM_INTERRUPTED
        return when {
            !sample.facePresent -> CalibrationIssue.OCCLUDED
            sample.conf < LOW_CONFIDENCE_THRESHOLD -> CalibrationIssue.LOW_CONFIDENCE
            cameraMoving -> CalibrationIssue.CAMERA_MOVING
            nowMs - lastFrameAtMs > config.frameTimeoutMs ||
                nowMs - lastVisionSampleAtMs > config.sampleTimeoutMs ->
                CalibrationIssue.STREAM_INTERRUPTED
            else -> null
        }
    }

    private fun updateIssue(issue: CalibrationIssue?, nowMs: Long) {
        if (issue != candidateIssue) {
            candidateIssue = issue
            candidateSinceMs = nowMs
        }
        visibleIssue = when {
            issue == null -> null
            issue != visibleIssue -> {
                if (nowMs - candidateSinceMs >= config.issueConfirmMs) issue else visibleIssue
            }
            else -> issue
        }
    }

    private fun publish(@Suppress("UNUSED_PARAMETER") nowMs: Long) {
        if (!running) return
        when {
            validFrames >= config.requiredFrames &&
                activeValidMs >= config.requiredActiveMs -> {
                running = false
                _state.value = CalibrationState.Completed(validFrames, activeValidMs)
            }
            activeValidMs >= config.maxActiveMs &&
                validFrames < config.requiredFrames -> {
                running = false
                _state.value = CalibrationState.Failed(
                    CalibrationIssue.INSUFFICIENT_FRAMES,
                    validFrames,
                )
            }
            else -> {
                val progress = min(
                    1f,
                    min(
                        validFrames.toFloat() / config.requiredFrames,
                        activeValidMs.toFloat() / config.requiredActiveMs,
                    ),
                )
                _state.value = CalibrationState.Running(
                    frameCount = validFrames,
                    requiredFrames = config.requiredFrames,
                    activeValidMs = activeValidMs,
                    requiredMs = config.requiredActiveMs,
                    progress = progress,
                    issue = visibleIssue,
                )
            }
        }
    }

    private companion object {
        const val LOW_CONFIDENCE_THRESHOLD = 0.5f
        const val MAX_TICK_STEP_MS = 500L
    }
}
