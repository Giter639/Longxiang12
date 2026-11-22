package com.focusdu.app.vision.algo

import com.focusdu.app.vision.PersonalBaseline

/**
 * 校准单帧特征：MediaPipe 引擎从关键点算出的原始特征（R4 内部数据，不属于跨端协议）。
 * 判定类字段（distanceBand/postureEvent/...）尚未生成，因此这里不复用 VisionSample。
 */
data class CalibrationFrameFeatures(
    val tsMs: Long,
    val faceScale: Float,        // 脸部尺度（与 T1-04 基线比较得距离档位）
    val pitchDeg: Float,         // 俯仰：正=低头
    val yawDeg: Float,           // 偏航
    val forwardDeg: Float,       // 前倾
    val shoulderTiltDeg: Float,  // 肩线倾角
    val lumFace: Float,
    val lumBackground: Float,
    val conf: Float,
)

/**
 * T1-04 个人基线生成（算法侧）。
 *
 * 输入：P03 校准期（5–10s，≥150 帧）的有效帧特征；估计器全部取**中位数**——
 * 对眨眼、呼吸微动、单帧检测跳变鲁棒，满足「重建偏差 <5%」验收。
 * 验收（任务卡量化指标）：
 * - 基线字段完整率 100%：PersonalBaseline 的数值字段全部产出，缺任何一项都不得落库；
 * - 重建偏差 <5%：由 [reconstructionErrorPercent] 固化为单测锚点。
 */
object BaselineCalculator {

    /** 与 CalibrationSession.Config.requiredFrames 对齐：帧数不足禁止出基线 */
    const val MIN_VALID_FRAMES = 150
    const val MIN_VALID_CONF = 0.5f

    /**
     * 由校准帧算基线。无效输入（帧数不足 / 无有效帧）返回 null，
     * 上层必须引导重新校准，禁止用残缺基线做距离/姿态判定。
     */
    fun compute(
        frames: List<CalibrationFrameFeatures>,
        sceneTag: String,
        createdAtWall: Long,
    ): PersonalBaseline? {
        val valid = frames.filter { it.conf >= MIN_VALID_CONF && it.faceScale > 0f }
        if (valid.size < MIN_VALID_FRAMES) return null
        fun medianOf(pick: (CalibrationFrameFeatures) -> Float) = median(valid.map(pick))
        return PersonalBaseline(
            faceScale = medianOf { it.faceScale },
            pitchDeg = medianOf { it.pitchDeg },
            yawDeg = medianOf { it.yawDeg },
            forwardDeg = medianOf { it.forwardDeg },
            shoulderTiltDeg = medianOf { it.shoulderTiltDeg },
            lumFace = medianOf { it.lumFace },
            lumBackground = medianOf { it.lumBackground },
            confidence = valid.map { it.conf }.average().toFloat(),
            sampleCount = valid.size,
            createdAtWall = createdAtWall,
            sceneTag = sceneTag,
        )
    }

    /** 重建偏差（%）：真值帧 vs 基线，7 个姿态/亮度字段取最大相对偏差 */
    fun reconstructionErrorPercent(truth: CalibrationFrameFeatures, baseline: PersonalBaseline): Float {
        fun rel(a: Float, b: Float) = if (a == 0f) kotlin.math.abs(b) else kotlin.math.abs(a - b) / kotlin.math.abs(a) * 100f
        return maxOf(
            rel(truth.faceScale, baseline.faceScale),
            rel(truth.pitchDeg, baseline.pitchDeg),
            rel(truth.yawDeg, baseline.yawDeg),
            rel(truth.forwardDeg, baseline.forwardDeg),
            rel(truth.shoulderTiltDeg, baseline.shoulderTiltDeg),
            rel(truth.lumFace, baseline.lumFace),
            rel(truth.lumBackground, baseline.lumBackground),
        )
    }

    fun median(values: List<Float>): Float {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }
}
