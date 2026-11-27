package com.focusdu.app.imu

import com.focusdu.app.core.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 骨架期开发与演示引擎（X22）：按脚本循环输出五态，让协议、桥接、下游消费方
 * 在无硬件的情况下全链可验证（对齐 vision/MockVisionEngine 的骨架定位）。
 * 脚本一个周期 64 秒：正常8 → 低头14 → 正常4 → 侧趴10 → 正常4 →
 * 乱动10 → 正常4 → 离座6 → 正常4。
 * （低头/侧趴/乱动段的持续时长均超过提醒引擎 persist 阈值，X24 接线后可稳定触发演示提醒）
 */
class MockImuEngine(
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
) : ImuEngine {

    private val flow = MutableSharedFlow<ImuSample>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var job: Job? = null

    override fun samples(): Flow<ImuSample> {
        ensureStarted()
        return flow
    }

    private fun ensureStarted() {
        if (job?.isActive == true) return
        job = scope.launch {
            var seq = 0L
            while (isActive) {
                flow.emit(mockSample(scriptState(seq), seq))
                seq++
                delay(500)
            }
        }
    }

    override fun stop() {
        job?.cancel()
    }

    companion object {
        /** 500ms 一帧 → t 为秒序号，64 秒一个周期（脚本纯函数，测试锚点） */
        fun scriptState(seq: Long): ImuState {
            val t = (seq / 2L) % 64L
            return when {
                t in 0L..7L -> ImuState.NORMAL
                t in 8L..21L -> ImuState.HEAD_DOWN
                t in 22L..25L -> ImuState.NORMAL
                t in 26L..35L -> ImuState.LEAN
                t in 36L..39L -> ImuState.NORMAL
                t in 40L..49L -> ImuState.FIDGET
                t in 50L..53L -> ImuState.NORMAL
                t in 54L..59L -> ImuState.AWAY
                else -> ImuState.NORMAL
            }
        }
    }

    internal fun mockSample(state: ImuState, seq: Long): ImuSample {
        val jitter = ((seq % 7) - 3) * 0.01f
        return ImuSample(
            tsMs = clock.nowMs(),
            seq = seq,
            axMg = 0f, ayMg = 0f,
            azMg = if (state == ImuState.AWAY) 950f else 1000f,
            gxDps = 0f, gyDps = 0f, gzDps = 0f,
            pitchDeg = when (state) {
                ImuState.HEAD_DOWN -> 30f
                ImuState.LEAN -> 12f
                else -> 8f
            },
            rollDeg = when (state) {
                ImuState.LEAN -> 35f
                else -> 3f + jitter * 10
            },
            motionRms = when (state) {
                ImuState.FIDGET -> 0.35f + jitter
                ImuState.AWAY -> 0.02f
                else -> 0.08f
            },
            state = state,
            conf = 0.94f + jitter,
        )
    }
}
