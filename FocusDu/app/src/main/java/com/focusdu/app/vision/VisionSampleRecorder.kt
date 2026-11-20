package com.focusdu.app.vision

import com.focusdu.app.data.db.AppDatabase
import com.focusdu.app.data.db.VisionSampleEntity
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.pomodoro.PomodoroState
import com.focusdu.app.vision.protocol.VisionSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 视觉样本采样落盘（T7-03 存储层的一部分）：
 * 按存储采样率 1 条/秒 写 Room，专注期间才记录 —— 报告「数据完整度」的数据源
 * （口径见 MetricDefs.SAMPLE_PER_SEC，与取流帧率 8fps 是两回事）。
 */
class VisionSampleRecorder(
    private val db: AppDatabase,
    private val pomodoroState: StateFlow<PomodoroState>,
) {
    private var lastWriteMs = 0L
    private val intervalMs = 1_000L

    fun onSample(s: VisionSample) {
        val now = s.tsMs
        if (now - lastWriteMs < intervalMs) return
        lastWriteMs = now

        val state = pomodoroState.value
        if (state.phase != Phase.FOCUS) return   // 完整度只统计专注段样本
        val sessionId = state.sessionId
        if (sessionId <= 0) return

        val entity = VisionSampleEntity(
            sessionId = sessionId,
            atMs = now,
            state = s.state.name,
            distanceBand = s.distanceBand,
            drowsiness = s.drowsiness,
            conf = s.conf,
            faceScale = s.faceScale,
            pitchDeg = s.headPitchDeg,
            yawDeg = s.headYawDeg,
            lumFace = s.lumFace,
            lumBackground = s.lumBackground,
            lightClass = s.lightClass,
        )
        // 落盘走 IO，不阻塞采样线程
        writeAsync(entity)
    }

    private fun writeAsync(entity: VisionSampleEntity) = writeScope.launch(Dispatchers.IO) {
        db.visionSampleDao().insert(entity)
    }

    companion object {
        private val writeScope = CoroutineScope(Dispatchers.IO)
    }
}
