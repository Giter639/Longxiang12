package com.focusdu.app.vision

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow

data class CameraFrame(
    val tsMs: Long,
    val seq: Long,
    val width: Int,
    val height: Int,
)

/**
 * 取流抽象（X11，R3 实现_CAMERA_X_：CameraXStreamer）。
 * 骨架期用 NoCameraStreamer 降级：无相机/无权限时全链路仍可运行（X14 兼容矩阵）。
 */
interface CameraStreamer {
    val streaming: StateFlow<Boolean>
    fun frames(): Flow<CameraFrame>
    suspend fun start(targetFps: Int = 8)   // 验收：目标机型 8fps 稳定 ≥30min
    fun stop()
}

/** 占位实现：空流。R3 在同包新建 CameraXStreamer 实现本接口后，到 AppContainer 替换绑定 */
class NoCameraStreamer : CameraStreamer {
    private val _streaming = MutableStateFlow(false)
    override val streaming: StateFlow<Boolean> = _streaming
    override fun frames(): Flow<CameraFrame> = emptyFlow()
    override suspend fun start(targetFps: Int) { _streaming.value = false }
    override fun stop() {}
}
