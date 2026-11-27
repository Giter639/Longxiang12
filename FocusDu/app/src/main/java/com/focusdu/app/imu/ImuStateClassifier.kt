package com.focusdu.app.imu

import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * X24 IMU 规则状态机（R4 判定层，协议见 docs/接口协议IMU-v0.md）。
 * 输入 X23 解包后的原始样本，输出补齐特征与五态判定的样本 —— 与视觉判定层（vision/algo/）同定位：
 * 阈值/滞回/最短保持全在这里，客户端消费方不做任何阈值判断。
 *
 * 特征层：
 * - 重力低通（α≈0.05 @25Hz，时间常数 ≈0.8s）→ pitch/roll；
 * - 500ms 滑窗（12 样本）合加速度偏离 1g 的 RMS → motionRms。
 * 状态机：进入/退出双阈值滞回 + 最短保持（1s 内不反复跳变，对齐视觉 T2-10 验收语义）；
 * 五态优先级 AWAY > HEAD_DOWN > LEAN > FIDGET > NORMAL（同时满足取更高风险）。
 *
 * 坐标约定：设备竖直佩戴（胸前/衣领，Z 轴大致向上），pitch=atan2(ax,az)、roll=atan2(ay,az)，
 * 正=低头/右倾；真机联调时若方向反了只调 [Config] 的符号常量，不破协议（对齐 FaceFeatureMapper 符号标定惯例）。
 * 判定基于个人基线偏移（ImuBaseline，静坐 5s 采集），无基线时用默认阈值。
 *
 * 使用：X23 的 BleImuEngine 内部组合本类（引擎负责产出判定态，协议第 1 节分工）；
 * MockImuEngine 已带判定态，不经过本类。非线程安全，单线程调用。
 */
class ImuStateClassifier(private val config: Config = Config()) {

    data class Config(
        val gravityAlpha: Float = 0.05f,      // 25Hz 低通系数，τ≈0.8s
        val motionWindowSamples: Int = 12,    // ≈480ms 滑窗
        val headDownEnterDeg: Float = 25f,
        val headDownExitDeg: Float = 15f,
        val leanEnterDeg: Float = 30f,
        val leanExitDeg: Float = 20f,
        val postureHoldMs: Long = 3_000,      // 低头/侧趴进入保持
        val fidgetEnterRms: Float = 0.25f,
        val fidgetExitRms: Float = 0.15f,
        val fidgetHoldMs: Long = 2_000,
        val awayEnterRms: Float = 0.35f,      // 走动级能量（静坐/乱动达不到）
        val awayExitRms: Float = 0.10f,
        val awayHoldMs: Long = 5_000,         // 持续走动才判离座，短促起坐不误报
        val confStable: Float = 0.90f,
        val confTransition: Float = 0.60f,
    )

    private var baseline: ImuBaseline? = null

    // 滤波重力向量（单位 g）
    private var initialized = false
    private var fx = 0f
    private var fy = 0f
    private var fz = 1f
    private val motionWindow = ArrayDeque<Float>()

    private var current = ImuState.NORMAL
    private var pending: ImuState? = null
    private var pendingSinceMs = 0L
    private var lastTsMs = Long.MIN_VALUE

    /** 静坐基线注入（X24）：判定改为基线偏移口径；null 恢复默认阈值 */
    fun setBaseline(baseline: ImuBaseline?) {
        this.baseline = baseline
    }

    /** 会话切换/重连复位：重力滤波、滑窗与状态机全部归零 */
    fun reset() {
        initialized = false
        fx = 0f; fy = 0f; fz = 1f
        motionWindow.clear()
        current = ImuState.NORMAL
        pending = null
        pendingSinceMs = 0L
        lastTsMs = Long.MIN_VALUE
    }

    /** 输入原始样本（X23 解包），返回补齐 pitchDeg/rollDeg/motionRms/state/conf 的样本 */
    fun classify(raw: ImuSample): ImuSample {
        // 断连重连：时间倒退说明新会话，先复位再判定
        if (raw.tsMs < lastTsMs) reset()
        lastTsMs = raw.tsMs

        val ax = raw.axMg / 1000f
        val ay = raw.ayMg / 1000f
        val az = raw.azMg / 1000f

        // ---- 特征层 ----
        if (!initialized) {
            fx = ax; fy = ay; fz = az   // 首样本直接落位，避免冷启动误判低头
            initialized = true
        } else {
            val a = config.gravityAlpha
            fx += a * (ax - fx)
            fy += a * (ay - fy)
            fz += a * (az - fz)
        }
        // 竖直佩戴约定（见类注释），正=低头/右倾；真机联调只调符号
        val pitch = Math.toDegrees(atan2(fx, fz).toDouble()).toFloat()
        val roll = Math.toDegrees(atan2(fy, fz).toDouble()).toFloat()

        val mag = sqrt(ax * ax + ay * ay + az * az)
        motionWindow.addLast(abs(mag - 1f))
        while (motionWindow.size > config.motionWindowSamples) motionWindow.removeFirst()
        var sumSq = 0f
        for (v in motionWindow) sumSq += v * v
        val motionRms = sqrt(sumSq / motionWindow.size)

        // ---- 判定层（基线偏移口径）----
        val b = baseline
        val pitchOffset = pitch - (b?.pitchDeg ?: 0f)
        val rollOffset = roll - (b?.rollDeg ?: 0f)
        val motion = motionRms - (b?.motionRms ?: 0f)

        val awayActive = hysteresis(current == ImuState.AWAY, motion, config.awayEnterRms, config.awayExitRms)
        val headDownActive = hysteresis(current == ImuState.HEAD_DOWN, pitchOffset, config.headDownEnterDeg, config.headDownExitDeg)
        val leanActive = hysteresis(current == ImuState.LEAN, rollOffset, config.leanEnterDeg, config.leanExitDeg)
        val fidgetActive = hysteresis(current == ImuState.FIDGET, motion, config.fidgetEnterRms, config.fidgetExitRms)

        val desired = when {
            awayActive -> ImuState.AWAY
            headDownActive -> ImuState.HEAD_DOWN
            leanActive -> ImuState.LEAN
            fidgetActive -> ImuState.FIDGET
            else -> ImuState.NORMAL
        }

        var conf = config.confStable
        if (desired != current) {
            // 新状态需持续 holdMs 才切换（每个状态各自的保持窗）
            if (pending != desired) {
                pending = desired
                pendingSinceMs = raw.tsMs
            }
            if (raw.tsMs - pendingSinceMs >= holdMsOf(desired)) {
                current = desired
                pending = null
            } else {
                conf = config.confTransition   // 转换期置信度低，但 >=0.5 不被客户端丢弃
            }
        } else {
            pending = null
        }

        return raw.copy(
            pitchDeg = pitch,
            rollDeg = roll,
            motionRms = motionRms,
            state = current,
            conf = conf,
        )
    }

    /** 滞回：处于该态时用退出阈值，否则用进入阈值 */
    private fun hysteresis(inState: Boolean, value: Float, enter: Float, exit: Float): Boolean =
        if (inState) value >= exit else value >= enter

    private fun holdMsOf(state: ImuState): Long = when (state) {
        ImuState.AWAY -> config.awayHoldMs
        ImuState.HEAD_DOWN, ImuState.LEAN -> config.postureHoldMs
        ImuState.FIDGET -> config.fidgetHoldMs
        ImuState.NORMAL -> 0L
    }
}
