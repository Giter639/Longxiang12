package com.focusdu.app.vision.algo

/**
 * T2-08 困倦特征识别（算法端；阈值 DRAFT_v0.1，归 R4 调）。
 *
 * 协议允许 4 类特征做 3 类，这里实现全部 4 类（检测不到眼部关键点时自动只剩 3 类，满足降级承诺）：
 * - 闭眼延长：eyeOpenRatio 持续低于阈值（微睡眠倾向）；
 * - 眨眼变慢：眨眼率低于下限并持续（输入来自 BlinkCounter）；
 * - 点头：俯仰角相对缓变基线反复快速下跌又回升；
 * - 打哈欠：嘴部张合比持续偏高。
 * 验收「困倦召回 ≥80%」依赖 X08 标注数据回归；单测锚定各类典型波形。
 */
class DrowsinessDetector(config: Config = Config()) {
    data class Config(
        val closedEnterRatio: Float = 0.15f,
        val slowBlinkRate: Float = 8f,          // 次/分钟以下视为变慢
        val slowBlinkSustainMs: Long = 30_000L,
        val nodBaseEmaAlpha: Float = 0.01f,     // 俯仰缓变基线（坐姿渐变）
        val nodDropDeg: Float = 12f,            // 相对基线快速下跌幅度
        val nodMinMs: Long = 300L,
        val nodMaxMs: Long = 1_500L,
        val nodRecoverDeg: Float = 6f,
        val nodActiveCount: Int = 2,            // 30s 内 ≥2 次点头视为点头状态
        val nodWindowMs: Long = 30_000L,
        val yawnEnterRatio: Float = 0.55f,
        val yawnMinMs: Long = 1_200L,
        val yawnWindowMs: Long = 30_000L,
        val minValidConf: Float = 0.5f,
    )

    /** 当前帧的困倦特征快照（喂给 T2-09 分级） */
    data class Features(
        val closedMs: Long,        // 连续闭眼时长
        val slowBlink: Boolean,    // 眨眼变慢状态
        val nodCount: Int,         // 窗口内点头次数
        val yawnActive: Boolean,   // 哈欠状态（正在打或 30s 内打过）
    )

    private val cfg = config
    private var pitchEma = Float.NaN
    private var nodDropStartMs = UNSET
    private val nodTimestamps = ArrayDeque<Long>()
    private var closedSinceMs = UNSET
    private var slowBlinkSinceMs = UNSET
    private var yawnSinceMs = UNSET
    private var lastYawnEndMs = 0L

    fun reset() {
        pitchEma = Float.NaN; nodDropStartMs = UNSET
        nodTimestamps.clear()
        closedSinceMs = UNSET; slowBlinkSinceMs = UNSET; yawnSinceMs = UNSET; lastYawnEndMs = 0
    }

    fun update(
        tsMs: Long,
        eyeOpenRatio: Float,
        blinkRatePerMin: Float,
        pitchDeg: Float,
        mouthOpenRatio: Float,
        conf: Float,
        facePresent: Boolean,
    ): Features {
        if (conf < cfg.minValidConf || !facePresent) {
            // 无人脸时全部特征复位（离开/离屏不属于困倦）
            closedSinceMs = UNSET; slowBlinkSinceMs = UNSET; yawnSinceMs = UNSET; nodDropStartMs = UNSET
            return Features(0, false, nodTimestamps.size, false)
        }

        // —— 闭眼延长
        val closedMs = if (eyeOpenRatio < cfg.closedEnterRatio) {
            if (closedSinceMs < 0) closedSinceMs = tsMs
            tsMs - closedSinceMs
        } else {
            closedSinceMs = UNSET
            0L
        }

        // —— 眨眼变慢（持续确认，防单帧噪声）
        slowBlinkSinceMs = if (blinkRatePerMin < cfg.slowBlinkRate) {
            if (slowBlinkSinceMs < 0) slowBlinkSinceMs = tsMs
            slowBlinkSinceMs
        } else {
            UNSET
        }
        val slowBlink = slowBlinkSinceMs >= 0 && tsMs - slowBlinkSinceMs >= cfg.slowBlinkSustainMs

        // —— 点头：相对缓变基线的快速下跌-回升周期
        if (pitchEma.isNaN()) pitchEma = pitchDeg
        pitchEma += cfg.nodBaseEmaAlpha * (pitchDeg - pitchEma)
        val drop = pitchDeg - pitchEma
        if (drop >= cfg.nodDropDeg && nodDropStartMs < 0) {
            nodDropStartMs = tsMs
        } else if (nodDropStartMs >= 0) {
            val dur = tsMs - nodDropStartMs
            if (pitchDeg <= pitchEma + cfg.nodRecoverDeg) {
                // 回升到位：形态完整的点头计入
                if (dur in cfg.nodMinMs..cfg.nodMaxMs) nodTimestamps.addLast(tsMs)
                nodDropStartMs = UNSET
            } else if (dur > cfg.nodMaxMs) {
                nodDropStartMs = UNSET // 跌得太久是低头，不是点头
            }
        }
        while (nodTimestamps.isNotEmpty() && nodTimestamps.first() < tsMs - cfg.nodWindowMs) {
            nodTimestamps.removeFirst()
        }

        // —— 打哈欠
        yawnSinceMs = if (mouthOpenRatio > cfg.yawnEnterRatio) {
            if (yawnSinceMs < 0) yawnSinceMs = tsMs
            yawnSinceMs
        } else {
            if (yawnSinceMs >= 0 && tsMs - yawnSinceMs >= cfg.yawnMinMs) lastYawnEndMs = tsMs
            UNSET
        }
        val yawnActive = (yawnSinceMs >= 0 && tsMs - yawnSinceMs >= cfg.yawnMinMs) ||
            (lastYawnEndMs > 0 && tsMs - lastYawnEndMs <= cfg.yawnWindowMs)

        return Features(closedMs, slowBlink, nodTimestamps.size, yawnActive)
    }

    private companion object {
        const val UNSET = -1L
    }
}
