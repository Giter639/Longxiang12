package com.focusdu.app.imu

import com.focusdu.app.vision.protocol.FusionState
import com.focusdu.app.vision.protocol.VisionSample

/**
 * X22 IMU→视觉六态桥：把 IMU 判定映射进既有 StateStreamDebouncer 消费协议，
 * 下游（P07/提醒/报告）不需要为 IMU 新增类型。纯映射，单测锚定（对齐 FaceFeatureMapper 定位）。
 *
 * 映射口径：
 * - HEAD_DOWN/LEAN → HEAD_DOWN（六态不区分低头/侧趴，提醒类型都是 POSTURE）；
 * - AWAY → AWAY（离座即六态的离开）；
 * - NORMAL/FIDGET → NORMAL：六态词汇没有「乱动」，X24 若为 FIDGET 增补 RiskType 再放开，
 *   现在不得伪造视觉语义（不把乱动算成任何视觉风险）；
 * - 视觉专属字段（faceScale/光照/眼部）填中性值，不吃掉视觉判定的语义。
 */
object ImuVisionBridge {

    fun mapState(state: ImuState): FusionState = when (state) {
        ImuState.NORMAL, ImuState.FIDGET -> FusionState.NORMAL
        ImuState.HEAD_DOWN, ImuState.LEAN -> FusionState.HEAD_DOWN
        ImuState.AWAY -> FusionState.AWAY
    }

    fun toVisionSample(s: ImuSample): VisionSample {
        val mapped = mapState(s.state)
        val away = mapped == FusionState.AWAY
        return VisionSample(
            tsMs = s.tsMs,
            seq = s.seq,
            facePresent = !away,
            conf = s.conf,
            faceScale = 0f,               // IMU 无距离概念，不参与偏近判定
            headPitchDeg = s.pitchDeg,
            headYawDeg = 0f,
            headForwardDeg = 0f,
            shoulderTiltDeg = s.rollDeg,  // 横滚信息挂在肩线字段透传（当前判定层未用）
            eyeOpenRatio = 1f,
            blinkRatePerMin = 0f,
            mouthOpenRatio = 0f,
            lumFace = 0f,
            lumDesktop = 0f,
            lumBackground = 0f,
            overexposureRatio = 0f,
            leftRightDelta = 0f,
            lightClass = "none",
            atDesk = !away,
            offScreenRatio = 0f,
            headStability = (1f - s.motionRms).coerceIn(0f, 1f),
            distanceBand = "normal",
            postureEvent = if (mapped == FusionState.HEAD_DOWN) "headDown" else "none",
            drowsiness = "none",
            state = mapped,
        )
    }
}
