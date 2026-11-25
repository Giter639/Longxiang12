package com.focusdu.app.vision

import androidx.camera.view.PreviewView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.focusdu.app.pomodoro.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 相机开关的统一协调（真引擎接线）。
 *
 * 取流层是单客户端的：CameraXStreamer.start() 会先停掉现有取流再重新绑定。校准页和番茄会话
 * 都需要相机，所以所有开关相机的动作都从这里走，共用一把锁，避免两边同时启动取流。
 *
 * - 专注、休息阶段需要相机（休息期要识别起身与离屏），暂停、空闲、结束阶段关闭；
 * - 校准页在前台时由校准页持有相机（需要预览画面），校准结束后若番茄仍在进行则自动恢复无预览取流；
 * - 相机绑定到页面的生命周期：应用退到后台时系统自动停流，回到前台自动恢复。
 *
 * 所有方法必须在主线程调用（与取流层、生命周期回调同线程）。
 */
class SessionCameraGate(
    private val streamer: CameraStreamer,
    private val scope: CoroutineScope,
) {
    companion object {
        /** 专注与休息需要相机；暂停、空闲、结束不需要 */
        fun sessionNeedsCamera(phase: Phase): Boolean =
            phase == Phase.FOCUS || phase == Phase.BREAK

        /** 与 X11 验收口径一致：8fps */
        const val SESSION_TARGET_FPS = 8
    }

    private val mutex = Mutex()
    private var sessionWanted = false
    private var calibrationActive = false
    private var owner: LifecycleOwner? = null
    private var boundOwner: LifecycleOwner? = null

    /** 最近一次番茄取流启动失败的原因，启动成功后清空。P07 据此说明「为什么没有检测数据」 */
    var lastError: Throwable? = null
        private set

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            schedule { applyLocked() }
        }

        override fun onDestroy(owner: LifecycleOwner) {
            schedule { if (boundOwner === owner) stopBound() }
        }
    }

    /** 页面在 onCreate 调用；页面重建时会带来新的 owner */
    fun attachOwner(newOwner: LifecycleOwner) {
        if (owner === newOwner) return
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = newOwner
        newOwner.lifecycle.addObserver(lifecycleObserver)
        schedule { applyLocked() }
    }

    fun setSessionWanted(wanted: Boolean) {
        sessionWanted = wanted
        schedule { applyLocked() }
    }

    /** 校准页开始采集：接管相机（带预览）。启动失败时交还相机并把异常原样抛出 */
    suspend fun startCalibration(calibrationOwner: LifecycleOwner, previewView: PreviewView, targetFps: Int) {
        try {
            mutex.withLock {
                calibrationActive = true
                boundOwner = null
                streamer.start(calibrationOwner, previewView, targetFps)
            }
        } catch (t: Throwable) {
            endCalibration()
            throw t
        }
    }

    /** 校准页结束采集（完成、失败或离开页面）后调用 */
    fun endCalibration() {
        schedule {
            streamer.stop()
            calibrationActive = false
            boundOwner = null
            applyLocked()
        }
    }

    /** 把一段相机操作排到锁后面执行；后到的一段总能看到前一段完成后的最新状态 */
    private fun schedule(block: suspend () -> Unit) {
        scope.launch { mutex.withLock { block() } }
    }

    /** 锁内执行：按当前意愿决定番茄取流的开或关 */
    private suspend fun applyLocked() {
        val o = owner ?: run {
            stopBound()
            return
        }
        val need = !calibrationActive && sessionWanted &&
            o.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!need) {
            stopBound()
            return
        }
        if (boundOwner === o && streamer.streaming.value) return
        try {
            streamer.start(o, null, SESSION_TARGET_FPS)
            boundOwner = o
            lastError = null
        } catch (t: Throwable) {
            boundOwner = null
            lastError = t
        }
    }

    private fun stopBound() {
        if (boundOwner == null) return
        boundOwner = null
        streamer.stop()
    }
}
