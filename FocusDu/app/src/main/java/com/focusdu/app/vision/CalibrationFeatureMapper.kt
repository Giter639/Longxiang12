package com.focusdu.app.vision

import com.focusdu.app.vision.algo.BaselineCalculator
import com.focusdu.app.vision.algo.CalibrationFrameFeatures
import com.focusdu.app.vision.protocol.VisionSample

/**
 * 校准期把引擎输出的样本转成基线算法的输入特征（T1-04 接线：P03 → BaselineCalculator）。
 * 没有脸、低置信或尺度无效的样本返回 null，不计入基线。
 */
fun VisionSample.toCalibrationFeatures(): CalibrationFrameFeatures? {
    if (!facePresent || conf < BaselineCalculator.MIN_VALID_CONF || faceScale <= 0f) return null
    return CalibrationFrameFeatures(
        tsMs = tsMs,
        faceScale = faceScale,
        pitchDeg = headPitchDeg,
        yawDeg = headYawDeg,
        forwardDeg = headForwardDeg,
        shoulderTiltDeg = shoulderTiltDeg,
        lumFace = lumFace,
        lumBackground = lumBackground,
        conf = conf,
    )
}
