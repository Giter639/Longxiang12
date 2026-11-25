package com.focusdu.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.ImageFormat
import android.graphics.YuvImage
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.vision.algo.BlinkCounter
import com.focusdu.app.vision.algo.DistanceMonitor
import com.focusdu.app.vision.algo.DrowsinessDetector
import com.focusdu.app.vision.algo.DrowsinessGrader
import com.focusdu.app.vision.algo.FusionStateMachine
import com.focusdu.app.vision.algo.LightClassifier
import com.focusdu.app.vision.algo.OffScreenTracker
import com.focusdu.app.vision.algo.PostureDetector
import com.focusdu.app.vision.protocol.VisionSample
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * R4 真引擎（X00 选型：MediaPipe Tasks Vision FaceLandmarker）。
 *
 * 链路：CameraStreamer.frames() → NV21→Bitmap → FaceLandmarker(LIVE_STREAM)
 *      → FaceFeatureMapper（纯映射，单测锚定）→ vision/algo 判定层 → VisionSample。
 *
 * - 判定阈值全部在 vision/algo 各模块的 Config（DRAFT_v0.1）；
 * - 肩线检测降级：FaceLandmarker 无肩部关键点，本版 shoulderTiltDeg 恒 0（不困倦误判，T2-02 肩线子项留待 Pose 增强）；
 * - 模型资产 assets/face_landmarker.task，加载失败在构造期抛明确异常（协议第 6 节，不静默黑屏）；
 * - 默认绑定即本引擎（AppContainer）；联调异常时可一行切回 MockVisionEngine 对比；
 * - 基线由上层经 [setBaseline] 注入，没有基线时不判距离（回退「正常」档）。
 */
class MediaPipeVisionEngine(
    context: Context,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val cameraStreamer: CameraStreamer,
    config: Config = Config(),
) : VisionEngine {

    data class Config(
        val modelAssetPath: String = "face_landmarker.task",
        val confidence: Float = 0.95f,          // 管线健康即输出高置信，缺席用 facePresent 表达
        val lookAwayYawDeg: Float = 20f,        // 逐帧原始离屏判定（喂 OffScreenTracker 平滑）
        val lookDownPitchDeg: Float = 25f,
        val lookUpPitchDeg: Float = -10f,
        val grayDownscaleW: Int = 160,
        val grayDownscaleH: Int = 120,
    )

    private val cfg = config
    private val flow = MutableSharedFlow<VisionSample>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var job: Job? = null

    // —— 判定层（vision/algo），阈值都在各自 Config
    private var baseline: PersonalBaseline? = null

    // setBaseline 在主线程调用，判定在 MediaPipe 回调线程读取，必须保证可见性
    @Volatile private var distanceMonitor: DistanceMonitor? = null
    private val postureDetector = PostureDetector()
    private val blinkCounter = BlinkCounter()
    private val drowsinessDetector = DrowsinessDetector()
    private val offScreenTracker = OffScreenTracker()
    private val sittingMonitor = com.focusdu.app.vision.algo.SittingMonitor()
    private val fusion = FusionStateMachine()

    // 逐帧平滑状态
    private var headStabilityEma = 1f
    private var lastHeadYaw = 0f
    private var lastHeadPitch = 0f
    private var lastFaceAtMs = 0L

    private val landmarker: FaceLandmarker

    init {
        val baseOpts = BaseOptions.builder()
            .setModelAssetPath(cfg.modelAssetPath)
            .setDelegate(Delegate.CPU)
            .build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOpts)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, _ -> onLandmarkerResult(result) }
            .setErrorListener { e ->
                throw IllegalStateException("FaceLandmarker 运行错误：${e.message}", e)
            }
            .build()
        landmarker = try {
            FaceLandmarker.createFromOptions(context, opts)
        } catch (e: Exception) {
            throw IllegalStateException(
                "R4 真引擎模型加载失败（${cfg.modelAssetPath}）——检查 assets 与依赖，禁止静默黑屏", e,
            )
        }
    }

    /** 距离判定需要个人基线（P03 校准完成、P04 写入或 App 重启恢复后由上层注入） */
    override fun setBaseline(b: PersonalBaseline?) {
        baseline = b
        distanceMonitor = b?.let { DistanceMonitor(it).also { m -> m.reset() } }
    }

    override fun samples(): Flow<VisionSample> {
        ensureStarted()
        return flow
    }

    private fun ensureStarted() {
        if (job?.isActive == true) return
        // 每帧的格式转换与推理提交不能占用主线程（传入的 scope 是主线程域），放到后台线程收集
        job = scope.launch(Dispatchers.Default) {
            cameraStreamer.frames().collect { frame -> processFrame(frame) }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    // —— 帧处理：NV21 → Bitmap → FaceLandmarker

    private var lastDetectTsMs = -1L

    private fun processFrame(frame: CameraFrame) {
        val detectTs = if (frame.tsMs <= lastDetectTsMs) lastDetectTsMs + 1 else frame.tsMs
        lastDetectTsMs = detectTs
        val bitmap = nv21ToBitmap(frame) ?: return
        cacheGray(bitmap)
        val image: MPImage = BitmapImageBuilder(bitmap).build()
        landmarker.detectAsync(image, detectTs)
    }

    private fun nv21ToBitmap(frame: CameraFrame): Bitmap? = try {
        val yuv = YuvImage(frame.nv21, ImageFormat.NV21, frame.width, frame.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, frame.width, frame.height), 85, out)
        val decoded = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        if (decoded == null) null
        else if (frame.rotationDegrees == 0) decoded
        else {
            val m = Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        }
    } catch (e: Exception) {
        null // 单帧解码失败直接丢帧，取流层 KEEP_ONLY_LATEST 会补上
    }

    // —— 结果 → 协议样本

    private fun onLandmarkerResult(result: FaceLandmarkerResult) {
        val tsMs = result.timestampMs()
        val landmarks = result.faceLandmarks().firstOrNull()
        val facePresent = landmarks != null && landmarks.isNotEmpty()
        if (facePresent) lastFaceAtMs = tsMs

        // 头位
        var pitch = 0f; var yaw = 0f; var forward = 0f
        result.facialTransformationMatrixes().ifPresent { mats ->
            mats.firstOrNull()?.let { m ->
                val a = FaceFeatureMapper.eulerFromTransform(m)
                pitch = a.pitchDeg; yaw = a.yawDeg; forward = a.forwardDeg
            }
        }

        // 眼口
        var eyeOpen = 1f; var mouthOpen = 0f
        result.faceBlendshapes().ifPresent { bs ->
            bs.firstOrNull()?.let { cats ->
                val (e, m) = FaceFeatureMapper.eyeAndMouthFromBlendshapes(cats)
                eyeOpen = e; mouthOpen = m
            }
        }

        // 尺度与亮度
        val scale = if (facePresent && landmarks != null)
            FaceFeatureMapper.faceScale(landmarks, lastFrameW, lastFrameH) else 0f
        val box = if (facePresent && landmarks != null)
            FaceFeatureMapper.faceBox(landmarks, lastFrameW, lastFrameH) else null
        val lum = currentLumStats(box)

        // —— 判定层逐级计算
        val band = distanceMonitor?.update(tsMs, scale, cfg.confidence)
            ?: DistanceMonitor.BAND_NORMAL // 无基线不判距离（上层引导校准）
        val postureEvent = postureDetector.update(tsMs, pitch, forward, 0f, cfg.confidence)
        val blinkStats = blinkCounter.update(tsMs, eyeOpen, cfg.confidence, facePresent)
        val drowsyFeatures = drowsinessDetector.update(
            tsMs, eyeOpen, blinkStats.blinkRatePerMin, pitch, mouthOpen, cfg.confidence, facePresent,
        )
        val drowsiness = DrowsinessGrader.grade(drowsyFeatures)
        val rawOffScreen = if (kotlin.math.abs(yaw) >= cfg.lookAwayYawDeg ||
            pitch >= cfg.lookDownPitchDeg || pitch <= cfg.lookUpPitchDeg
        ) 1f else 0f
        val offStats = offScreenTracker.update(
            tsMs, rawOffScreen, headStabilityEma, yaw, pitch, cfg.confidence,
        )
        val atDesk = facePresent || tsMs - lastFaceAtMs <= FACE_GRACE_MS
        val sitting = sittingMonitor.update(tsMs, atDesk)
        val state = fusion.update(
            tsMs,
            FusionStateMachine.FusionInput(band, postureEvent, drowsiness, sitting.away || !atDesk, offStats.avgOffScreenRatio, facePresent),
        )

        // 头部朝向稳定性：朝向突变的 EMA（纯展示指标，不出分数口径）
        val turn = (kotlin.math.abs(yaw - lastHeadYaw) + kotlin.math.abs(pitch - lastHeadPitch)) / 30f
        headStabilityEma += 0.1f * ((1f - turn.coerceIn(0f, 1f)) - headStabilityEma)
        lastHeadYaw = yaw; lastHeadPitch = pitch

        flow.tryEmit(
            VisionSample(
                tsMs = tsMs,
                seq = tsMs,
                facePresent = facePresent,
                conf = cfg.confidence,
                faceScale = scale,
                headPitchDeg = pitch,
                headYawDeg = yaw,
                headForwardDeg = forward,
                shoulderTiltDeg = 0f, // FaceLandmarker 无肩部关键点，降级见类注释
                eyeOpenRatio = eyeOpen,
                blinkRatePerMin = blinkStats.blinkRatePerMin,
                mouthOpenRatio = mouthOpen,
                lumFace = lum.lumFace,
                lumDesktop = lum.lumDesktop,
                lumBackground = lum.lumBackground,
                overexposureRatio = lum.overexposureRatio,
                leftRightDelta = lum.leftRightDelta,
                lightClass = LightClassifier.classify(
                    lum.lumFace, lum.lumDesktop, lum.lumBackground,
                    lum.overexposureRatio, lum.leftRightDelta,
                ),
                atDesk = sitting.away.not() && atDesk,
                offScreenRatio = offStats.avgOffScreenRatio,
                headStability = headStabilityEma,
                distanceBand = band,
                postureEvent = postureEvent,
                drowsiness = drowsiness,
                state = state,
            ),
        )
    }

    // 最近一帧的灰度缓存（亮度统计与解码在帧线程，结果线程只读）
    @Volatile private var lastFrameW = 0
    @Volatile private var lastFrameH = 0
    @Volatile private var lastGray: IntArray? = null

    private fun currentLumStats(box: FaceFeatureMapper.FaceBox?): FaceFeatureMapper.LumStats {
        val gray = lastGray ?: return FaceFeatureMapper.LumStats(128f, 96f, 110f, 0f, 0f)
        return FaceFeatureMapper.lumStats(gray, lastFrameW, lastFrameH, box)
    }

    /** 帧线程调用：缓存降采样灰度图供亮度统计 */
    private fun cacheGray(bmp: Bitmap) {
        val w = cfg.grayDownscaleW; val h = cfg.grayDownscaleH
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { i ->
            val p = pixels[i]
            (0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)).toInt()
        }
        lastFrameW = w; lastFrameH = h; lastGray = gray
        if (small !== bmp) small.recycle()
    }

    private companion object {
        const val FACE_GRACE_MS = 10_000L // 人脸短暂丢失时保持在场，防离座误判（遮挡/低头出画）
    }
}
