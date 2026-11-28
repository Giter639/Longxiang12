package com.focusdu.app.imu

import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.vision.protocol.VisionSample
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * X26 视觉×IMU 融合仲裁（主备切换式，v0 规则可解释，答辩口径）。
 *
 * 仲裁规则：
 * 1. **视觉为主源**：健康（有效样本 conf≥0.5 在 [Config.visionFreshMs] 内）时，仲裁流只透传视觉样本；
 * 2. **IMU 兜底**：视觉连续 [Config.visionFreshMs] 无有效样本（断流/遮挡/相机关闭）且 IMU 健康
 *    （25Hz 样本在 [Config.imuFreshMs] 内）→ IMU 样本经 [ImuVisionBridge] 映射后接管仲裁流，
 *    风险段照常进 STATE_SPAN 埋点与提醒（视觉挡住时守护不中断）；
 * 3. **低置信不算新鲜**：conf<0.5 的视觉样本不刷新健康度（遮挡场景 IMU 才能接管）；
 * 4. **恢复即切回**：收到任一有效视觉样本立刻切回视觉（debouncer 首帧直接上屏，无需等待）；
 * 5. **双源同刻只有一个在仲裁流里**——下游（防抖/埋点/提醒/休息校验）零改动且不会双计。
 *
 * 线程模型：onVisionSample/onImuSample 必须在同一单线程 scope 串行调用（AppContainer 主线程 collect）。
 * 无头绪期（两源都没数据）仲裁流静默，debouncer 3s 后自然进入「检测中断」（X18 已有语义）。
 */
class VisionImuArbiter(
    private val clock: MonotonicClock,
    private val config: Config = Config(),
) {
    data class Config(
        val visionFreshMs: Long = 3_000,   // 与 StateStreamDebouncer.streamTimeoutMs 同口径
        val imuFreshMs: Long = 2_000,      // 25Hz 下 2s 无样本即 IMU 不健康（重连间隙短暂失去兜底，可接受）
    )

    private enum class Source { VISION, IMU }

    private val _arbitrated = MutableSharedFlow<VisionSample>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 仲裁后的单一状态样本流 → 现有 StateStreamDebouncer（下游零改动） */
    val arbitrated: SharedFlow<VisionSample> = _arbitrated

    private var source = Source.VISION
    private var lastVisionAtMs: Long? = null   // null = 从未见过有效视觉样本
    private var lastImuAtMs: Long? = null      // null = 从未见过 IMU 样本

    fun onVisionSample(sample: VisionSample) {
        val now = clock.nowMs()
        if (sample.conf >= 0.5f) {
            lastVisionAtMs = now
            source = Source.VISION   // 恢复即切回
            _arbitrated.tryEmit(sample)
        }
        // 低置信样本：不刷新健康度、不上仲裁流（遮挡判定交给 IMU）
    }

    fun onImuSample(sample: ImuSample) {
        val now = clock.nowMs()
        val prevImuAtMs = lastImuAtMs
        lastImuAtMs = now
        // IMU 健康度看「上一帧到现在」的间隔（持续到达才算健康），先取旧值再更新
        val imuHealthy = prevImuAtMs == null || now - prevImuAtMs <= config.imuFreshMs
        val visionAt = lastVisionAtMs
        if (source == Source.VISION &&
            (visionAt == null || now - visionAt > config.visionFreshMs) &&
            imuHealthy
        ) {
            source = Source.IMU      // 视觉断流/遮挡，IMU 兜底接管
        }
        if (source == Source.IMU) {
            _arbitrated.tryEmit(ImuVisionBridge.toVisionSample(sample))
        }
    }

    /** 当前生效源（P07 观测/答辩演示用；不参与判定） */
    fun activeSource(): String = if (source == Source.VISION) "vision" else "imu"
}
