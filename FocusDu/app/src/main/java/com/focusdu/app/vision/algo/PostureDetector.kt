package com.focusdu.app.vision.algo

import com.focusdu.app.reminder.RiskEvent
import com.focusdu.app.reminder.RiskLevel
import com.focusdu.app.reminder.RiskType

/**
 * T2-02 低头/前倾/肩线检测（算法端；阈值 DRAFT_v0.1，归 R4 调）。
 *
 * 多帧确认：原始条件连续保持 ≥[Config.confirmMs] 才计事件（startMs = 首次达标时刻）；
 * 条件回落后保持 ≥[Config.releaseMs] 才结束事件。
 * 任务卡验收「事件起止误差 ≤1s」：起点零误差（取原始达标时刻），终点误差 ≤ releaseMs(500ms)。
 * 三种姿态互斥输出（协议 postureEvent 为单值），优先级：低头 > 前倾 > 肩线倾斜。
 */
class PostureDetector(config: Config = Config()) {
    data class Config(
        val headDownEnterDeg: Float = 20f,
        val headDownExitDeg: Float = 15f,
        val leanForwardEnterDeg: Float = 15f,
        val leanForwardExitDeg: Float = 10f,
        val shoulderTiltEnterDeg: Float = 8f,
        val shoulderTiltExitDeg: Float = 5f,
        val confirmMs: Long = 800L,
        val releaseMs: Long = 500L,
        val minValidConf: Float = 0.5f,
    )

    companion object {
        const val EVENT_NONE = "none"
        const val EVENT_HEAD_DOWN = "headDown"
        const val EVENT_LEANING_FORWARD = "leaningForward"
        const val EVENT_SHOULDER_TILT = "shoulderTilt"
        private const val UNSET = -1L
    }

    private val cfg = config

    /** 单个姿态手势的确认/释放状态机（-1 = 未开始，时间戳可为任意单调值） */
    private class Gesture(val name: String, val enterDeg: Float, val exitDeg: Float) {
        var rawSinceMs = UNSET   // 原始条件首次达标时刻（=事件 startMs）
        var activeStartMs = 0L   // 事件确认生效时刻
        var lastRawOkMs = UNSET  // 原始条件最近一次达标时刻
        var isActive = false

        fun update(nowMs: Long, deg: Float, cfg: Config): Boolean {
            val rawOk = deg >= enterDeg
            if (rawOk) {
                if (rawSinceMs < 0) rawSinceMs = nowMs
                lastRawOkMs = nowMs
                if (!isActive && nowMs - rawSinceMs >= cfg.confirmMs) {
                    isActive = true
                    activeStartMs = rawSinceMs
                }
            } else if (isActive) {
                // 滞回区（enterDeg 与 exitDeg 之间）继续维持事件；低于 exitDeg 且超过释放时长才结束
                if (deg <= exitDeg && nowMs - lastRawOkMs >= cfg.releaseMs) {
                    isActive = false
                    rawSinceMs = UNSET
                    lastRawOkMs = UNSET
                }
            } else {
                rawSinceMs = UNSET // 确认期内中断则重新累计，杜绝抖动误报
            }
            return isActive
        }

        fun reset() {
            rawSinceMs = UNSET; activeStartMs = 0; lastRawOkMs = UNSET; isActive = false
        }
    }

    private val gHeadDown = Gesture(EVENT_HEAD_DOWN, cfg.headDownEnterDeg, cfg.headDownExitDeg)
    private val gLean = Gesture(EVENT_LEANING_FORWARD, cfg.leanForwardEnterDeg, cfg.leanForwardExitDeg)
    private val gShoulder = Gesture(EVENT_SHOULDER_TILT, cfg.shoulderTiltEnterDeg, cfg.shoulderTiltExitDeg)

    /** 当前生效的 POSTURE 风险事件（null = 无），供事件流替换 RiskEventWire */
    var activeEvent: RiskEvent? = null
        private set

    fun reset() {
        listOf(gHeadDown, gLean, gShoulder).forEach { it.reset() }
        activeEvent = null
    }

    /**
     * 每个视觉样本调用一次。
     * @return 本样本的 postureEvent 字符串（none/headDown/leaningForward/shoulderTilt）
     */
    fun update(tsMs: Long, pitchDeg: Float, forwardDeg: Float, shoulderTiltDeg: Float, conf: Float): String {
        if (conf < cfg.minValidConf) return activeGestureName()
        val headDown = gHeadDown.update(tsMs, pitchDeg, cfg)
        val lean = gLean.update(tsMs, forwardDeg, cfg)
        val shoulder = gShoulder.update(tsMs, shoulderTiltDeg, cfg)
        val name = when {
            headDown -> EVENT_HEAD_DOWN
            lean -> EVENT_LEANING_FORWARD
            shoulder -> EVENT_SHOULDER_TILT
            else -> EVENT_NONE
        }
        activeEvent = if (name == EVENT_NONE) {
            null
        } else {
            val start = when (name) {
                EVENT_HEAD_DOWN -> gHeadDown.activeStartMs
                EVENT_LEANING_FORWARD -> gLean.activeStartMs
                else -> gShoulder.activeStartMs
            }
            RiskEvent(RiskType.POSTURE, RiskLevel.LIGHT, start, tsMs - start)
        }
        return name
    }

    private fun activeGestureName(): String = when {
        gHeadDown.isActive -> EVENT_HEAD_DOWN
        gLean.isActive -> EVENT_LEANING_FORWARD
        gShoulder.isActive -> EVENT_SHOULDER_TILT
        else -> EVENT_NONE
    }
}
