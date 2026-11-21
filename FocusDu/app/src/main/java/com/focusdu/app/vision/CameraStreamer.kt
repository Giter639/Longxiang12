package com.focusdu.app.vision

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.focusdu.app.core.MonotonicClock
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * R3 取流层交给 R4 的唯一输入帧。
 *
 * [nv21] 是已经整理掉 YUV 行跨度的紧凑数据。CameraX 的 ImageProxy 在回调结束后必须关闭，
 * 因此先复制再异步分发，避免 R4 持有失效缓冲区。前摄不在这里做像素镜像；
 * [isFrontFacing] 和 [rotationDegrees] 供算法统一坐标。
 */
data class CameraFrame(
    val tsMs: Long,
    val seq: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val isFrontFacing: Boolean,
    val isDeviceMoving: Boolean,
    val nv21: ByteArray,
)

enum class CameraState {
    IDLE,
    STARTING,
    STREAMING,
    UNAVAILABLE,
    ERROR,
}

/** 运行时实际选中的相机能力，供页面和数据表记录机型差异。 */
data class CameraStatus(
    val state: CameraState,
    val width: Int? = null,
    val height: Int? = null,
    val requestedFps: Int? = null,
    val fpsRange: IntRange? = null,
    val isFrontFacing: Boolean? = null,
    val errorCode: String? = null,
)

/**
 * 取流抽象（X11，R3 实现 CameraXStreamer）。
 * 所有机型使用同一接口；具体相机、分辨率和帧率范围由 CameraX 在运行时协商。
 */
interface CameraStreamer {
    val streaming: StateFlow<Boolean>
    val status: StateFlow<CameraStatus>
    fun frames(): Flow<CameraFrame>

    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null,
        targetFps: Int = 8,
    )

    fun stop()
}

/** 无相机、无权限或绑定失败时的安全降级实现。 */
class NoCameraStreamer : CameraStreamer {
    private val _streaming = MutableStateFlow(false)
    override val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _status = MutableStateFlow(CameraStatus(CameraState.UNAVAILABLE))
    override val status: StateFlow<CameraStatus> = _status.asStateFlow()

    override fun frames(): Flow<CameraFrame> = emptyFlow()

    override suspend fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        targetFps: Int,
    ) {
        _streaming.value = false
        _status.value = CameraStatus(CameraState.UNAVAILABLE)
    }

    override fun stop() {
        _streaming.value = false
    }
}

/**
 * CameraX 实现。策略：
 * - 优先前摄，不可用时回落后摄；
 * - 分辨率请求 640x480，由 CameraX 选择设备最接近的受支持档位；
 * - 从相机特性中选择满足目标帧率的最低功耗范围；
 * - KEEP_ONLY_LATEST，避免处理不过来时排队；
 * - 对外只发紧凑 NV21，R4 不需要依赖 CameraX 的 ImageProxy 生命周期。
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraXStreamer(
    private val context: Context,
    private val clock: MonotonicClock,
) : CameraStreamer {

    private val _streaming = MutableStateFlow(false)
    override val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _status = MutableStateFlow(CameraStatus(CameraState.IDLE))
    override val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val frameFlow = MutableSharedFlow<CameraFrame>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val motionMonitor = DeviceMotionMonitor(context, clock)
    private var provider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var sequence = 0L
    private var lastDeliveredAtMs = 0L

    override fun frames(): Flow<CameraFrame> = frameFlow.asSharedFlow()

    override suspend fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        targetFps: Int,
    ) {
        stop()
        _streaming.value = false
        _status.value = CameraStatus(CameraState.STARTING, requestedFps = targetFps)

        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).await()
            provider = cameraProvider

            val selector = when {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                    CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                    CameraSelector.DEFAULT_BACK_CAMERA
                else -> error("No camera available")
            }

            val cameraInfo = cameraProvider.getAvailableCameraInfos()
                .firstOrNull { selector.filter(listOf(it)).isNotEmpty() }
                ?: error("Camera info unavailable")
            val lensFacing = Camera2CameraInfo.from(cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            val isFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
            val fpsRange = chooseFpsRange(cameraInfo, targetFps)

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            fpsRange?.let {
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(it.first, it.last),
                    )
            }

            val analysis = analysisBuilder.build()
            val executor = Executors.newSingleThreadExecutor()
            analysisExecutor = executor
            val minFrameGapMs = 1_000L / targetFps.coerceAtLeast(1)

            analysis.setAnalyzer(executor) { image ->
                try {
                    val now = clock.nowMs()
                    if (lastDeliveredAtMs == 0L || now - lastDeliveredAtMs >= minFrameGapMs) {
                        val nv21 = image.toCompactNv21()
                        frameFlow.tryEmit(
                            CameraFrame(
                                tsMs = now,
                                seq = ++sequence,
                                width = image.width,
                                height = image.height,
                                rotationDegrees = image.imageInfo.rotationDegrees,
                                isFrontFacing = isFront,
                                isDeviceMoving = motionMonitor.isMoving(),
                                nv21 = nv21,
                            )
                        )
                        if (_status.value.width == null) {
                            _status.value = _status.value.copy(
                                width = image.width,
                                height = image.height,
                            )
                        }
                        lastDeliveredAtMs = now
                    }
                } finally {
                    image.close()
                }
            }

            val useCases = mutableListOf<UseCase>(analysis)
            if (previewView != null) {
                val preview = Preview.Builder().build()
                preview.surfaceProvider = previewView.surfaceProvider
                useCases += preview
            }

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                *useCases.toTypedArray(),
            )
            motionMonitor.start()
            _streaming.value = true
            _status.value = CameraStatus(
                state = CameraState.STREAMING,
                requestedFps = targetFps,
                fpsRange = fpsRange,
                isFrontFacing = isFront,
            )
        } catch (t: Throwable) {
            stop()
            _status.value = CameraStatus(
                state = CameraState.ERROR,
                requestedFps = targetFps,
                errorCode = t.javaClass.simpleName,
            )
            throw t
        }
    }

    override fun stop() {
        motionMonitor.stop()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        provider?.unbindAll()
        provider = null
        lastDeliveredAtMs = 0L
        _streaming.value = false
        if (_status.value.state != CameraState.ERROR) {
            _status.value = CameraStatus(CameraState.IDLE)
        }
    }

    private fun chooseFpsRange(cameraInfo: CameraInfo, targetFps: Int): IntRange? {
        val ranges = Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower to it.upper }
            .orEmpty()
            .filter { it.second >= targetFps }
        return ranges.minWithOrNull(
            compareBy<Pair<Int, Int>> { abs(it.first - targetFps) }
                .thenBy { it.second - it.first }
        )?.let { it.first..it.second }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addListener(
                {
                    try {
                        continuation.resume(get())
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
            continuation.invokeOnCancellation { cancel(true) }
        }
}

/**
 * 设备移动检测使用陀螺仪优先，无陀螺仪时使用加速度计变化。
 * 连续运动会在最后一帧后保留 500ms，避免一两帧抖动造成状态闪烁。
 */
private class DeviceMotionMonitor(
    context: Context,
    private val clock: MonotonicClock,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var movingUntilMs = 0L

    fun start() {
        gyroscope?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (gyroscope == null) {
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        movingUntilMs = 0L
    }

    fun isMoving(): Boolean = clock.nowMs() < movingUntilMs

    override fun onSensorChanged(event: SensorEvent) {
        val magnitude = kotlin.math.sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )
        val moving = when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> magnitude > GYROSCOPE_MOVE_THRESHOLD
            Sensor.TYPE_ACCELEROMETER -> abs(magnitude - SensorManager.GRAVITY_EARTH) >
                ACCELEROMETER_MOVE_THRESHOLD
            else -> false
        }
        if (moving) movingUntilMs = clock.nowMs() + MOTION_HOLD_MS
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val GYROSCOPE_MOVE_THRESHOLD = 0.35f
        const val ACCELEROMETER_MOVE_THRESHOLD = 1.3f
        const val MOTION_HOLD_MS = 500L
    }
}

/** 把 CameraX 的 YUV_420_888 转成无行填充的 NV21，交给 R4 固定消费。 */
private fun ImageProxy.toCompactNv21(): ByteArray {
    check(format == ImageFormat.YUV_420_888) {
        "Unsupported image format: $format"
    }
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val output = ByteArray(width * height * 3 / 2)
    var offset = 0

    for (row in 0 until height) {
        for (col in 0 until width) {
            output[offset++] = yPlane.buffer.get(row * yPlane.rowStride + col)
        }
    }

    val chromaWidth = width / 2
    val chromaHeight = height / 2
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
            val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
            output[offset++] = vPlane.buffer.get(vIndex)
            output[offset++] = uPlane.buffer.get(uIndex)
        }
    }
    return output
}
