package com.focusdu.app.vision

import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * MediaPipe FaceLandmarker 结果 → 协议特征 的纯映射层（X00 选型文档的字段映射落点）。
 * 无 Android 依赖，JVM 单测可锚定：欧拉角分解往返、faceScale、blendshape 映射、亮度统计。
 *
 * 头位符号约定（协议 v0：pitch 正=低头）由 [PITCH_SIGN]/[YAW_SIGN]/[FORWARD_SIGN] 控制，
 * 真机联调（X06/T2-02）时若实测方向相反，只翻符号常量，不动算法。
 */
object FaceFeatureMapper {

    // FaceMesh 468/478 拓扑关键点索引
    const val IDX_LEFT_EYE_OUTER = 33
    const val IDX_RIGHT_EYE_OUTER = 263
    const val IDX_NOSE_TIP = 1
    const val IDX_CHIN = 152
    const val IDX_FOREHEAD = 10

    // blendshape 名称（tasks-vision 输出）
    const val BS_EYE_BLINK_LEFT = "eyeBlinkLeft"
    const val BS_EYE_BLINK_RIGHT = "eyeBlinkRight"
    const val BS_JAW_OPEN = "jawOpen"

    // 头位符号校准常量（真机联调时按实测翻转，不动其余代码）
    const val PITCH_SIGN = 1f
    const val YAW_SIGN = 1f
    const val FORWARD_SIGN = 1f

    /** 亮度统计（协议 T2-04 输入，全部无量纲 0–255，不出 lux） */
    data class LumStats(
        val lumFace: Float,
        val lumDesktop: Float,
        val lumBackground: Float,
        val overexposureRatio: Float,
        val leftRightDelta: Float,
    )

    data class AngleTriple(val pitchDeg: Float, val yawDeg: Float, val forwardDeg: Float)

    /**
     * 4x4 列主序变换矩阵 → 欧拉角（ZYX 约定）。
     * R[row][col] = matrix[col*4 + row]。
     */
    fun eulerFromTransform(matrix: FloatArray): AngleTriple {
        require(matrix.size >= 16) { "facial transformation matrix 必须是 4x4=16 元素" }
        val r00 = matrix[0]; val r01 = matrix[4]; val r02 = matrix[8]
        val r10 = matrix[1]; val r11 = matrix[5]; val r12 = matrix[9]
        val r20 = matrix[2]; val r21 = matrix[6]; val r22 = matrix[10]
        val pitch = Math.toDegrees(atan2(r21.toDouble(), r22.toDouble())).toFloat() * PITCH_SIGN
        val yaw = Math.toDegrees(asin(r20.toDouble().coerceIn(-1.0, 1.0))).toFloat() * YAW_SIGN
        val forward = Math.toDegrees(atan2(r10.toDouble(), r00.toDouble())).toFloat() * FORWARD_SIGN
        return AngleTriple(pitch, yaw, forward)
    }

    /** 脸部尺度：左右眼外眦像素距离 / 图像宽度（与基线同口径，Mock 正常值 ≈0.30） */
    fun faceScale(landmarks: List<NormalizedLandmark>, imageWidth: Int, imageHeight: Int): Float {
        if (landmarks.size <= IDX_RIGHT_EYE_OUTER || imageWidth <= 0 || imageHeight <= 0) return 0f
        val l = landmarks[IDX_LEFT_EYE_OUTER]
        val r = landmarks[IDX_RIGHT_EYE_OUTER]
        val dx = (r.x() - l.x()) * imageWidth
        val dy = (r.y() - l.y()) * imageHeight
        return sqrt(dx * dx + dy * dy) / imageWidth
    }

    /** 人脸外接框（像素坐标），供亮度分区与性能统计 */
    data class FaceBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun faceBox(landmarks: List<NormalizedLandmark>, imageWidth: Int, imageHeight: Int): FaceBox? {
        if (landmarks.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (lm in landmarks) {
            if (lm.x() < minX) minX = lm.x()
            if (lm.y() < minY) minY = lm.y()
            if (lm.x() > maxX) maxX = lm.x()
            if (lm.y() > maxY) maxY = lm.y()
        }
        return FaceBox(
            (minX * imageWidth).toInt(), (minY * imageHeight).toInt(),
            (maxX * imageWidth).toInt(), (maxY * imageHeight).toInt(),
        )
    }

    /** blendshape → 眼口指标：eyeOpenRatio=1−眨眼均值，mouthOpenRatio=jawOpen */
    fun eyeAndMouthFromBlendshapes(blendshapes: List<Category>): Pair<Float, Float> {
        var blinkSum = 0f; var blinkCount = 0; var jaw = 0f
        for (c in blendshapes) {
            when (c.categoryName()) {
                BS_EYE_BLINK_LEFT -> { blinkSum += c.score(); blinkCount++ }
                BS_EYE_BLINK_RIGHT -> { blinkSum += c.score(); blinkCount++ }
                BS_JAW_OPEN -> jaw = c.score()
            }
        }
        val eyeOpen = if (blinkCount == 0) 1f else 1f - (blinkSum / blinkCount)
        return eyeOpen.coerceIn(0f, 1f) to jaw.coerceIn(0f, 1f)
    }

    /**
     * 三区亮度统计（灰度图 IntArray 0–255）。
     * face=外接框内；desktop=下 1/3（框外）；background=上 1/4（框外）；左右差=左右半帧均差。
     */
    fun lumStats(gray: IntArray, w: Int, h: Int, box: FaceBox?): LumStats {
        require(gray.size == w * h) { "灰度图尺寸不匹配" }
        var faceSum = 0L; var faceN = 0
        var deskSum = 0L; var deskN = 0
        var bgSum = 0L; var bgN = 0
        var leftSum = 0L; var leftN = 0
        var rightSum = 0L; var rightN = 0
        var over = 0
        val inFace: (Int, Int) -> Boolean = { x, y ->
            box != null && x >= box.left && x <= box.right && y >= box.top && y <= box.bottom
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = gray[y * w + x]
                if (v > 250) over++
                if (x < w / 2) { leftSum += v; leftN++ } else { rightSum += v; rightN++ }
                when {
                    y >= h * 2 / 3 && !inFace(x, y) -> { deskSum += v; deskN++ }
                    y < h / 4 && !inFace(x, y) -> { bgSum += v; bgN++ }
                    inFace(x, y) -> { faceSum += v; faceN++ }
                }
            }
        }
        val avg = { s: Long, n: Int -> if (n == 0) 128f else s.toFloat() / n }
        return LumStats(
            lumFace = avg(faceSum, faceN),
            lumDesktop = avg(deskSum, deskN),
            lumBackground = avg(bgSum, bgN),
            overexposureRatio = over.toFloat() / gray.size,
            leftRightDelta = kotlin.math.abs(avg(leftSum, leftN) - avg(rightSum, rightN)),
        )
    }
}
