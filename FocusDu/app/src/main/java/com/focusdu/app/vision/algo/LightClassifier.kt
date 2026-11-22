package com.focusdu.app.vision.algo

/**
 * T2-04 光照质量检测（算法端；阈值 DRAFT_v0.1，归 R4 调）。
 *
 * 输入全部为无量纲图像统计（协议约束：不出 lux 值）：
 * - lumFace / lumDesktop / lumBackground：三区 0–255 亮度；
 * - overexposureRatio：过曝像素占比；
 * - leftRightDelta：左右半帧亮度差。
 * 分类优先级：夜间 > 眩光 > 逆光 > 偏暗 > 不均 > 正常（先场景级后局部级）。
 * 一致率 ≥85% 的验收依赖 X08 场景样本库；本类只固化判定规则，单测锚定各类典型样本。
 */
object LightClassifier {

    const val CLASS_NORMAL = "normal"
    const val CLASS_DARK = "dark"
    const val CLASS_BACKLIGHT = "backlight"
    const val CLASS_GLARE = "glare"
    const val CLASS_UNEVEN = "uneven"
    const val CLASS_NIGHT = "night"

    // DRAFT_v0.1 阈值（0–255 亮度口径）
    const val NIGHT_BG_LUM = 40f
    const val NIGHT_FACE_LUM = 60f
    const val BACKLIGHT_RATIO = 1.8f
    const val BACKLIGHT_FACE_LUM = 80f
    const val DARK_FACE_LUM = 70f
    const val GLARE_OVEREXPOSURE = 0.15f
    const val UNEVEN_LR_DELTA = 60f

    fun classify(
        lumFace: Float,
        lumDesktop: Float,
        lumBackground: Float,
        overexposureRatio: Float,
        leftRightDelta: Float,
    ): String = when {
        lumBackground < NIGHT_BG_LUM && lumFace < NIGHT_FACE_LUM -> CLASS_NIGHT
        overexposureRatio > GLARE_OVEREXPOSURE -> CLASS_GLARE
        lumBackground > lumFace * BACKLIGHT_RATIO && lumFace < BACKLIGHT_FACE_LUM -> CLASS_BACKLIGHT
        lumFace < DARK_FACE_LUM -> CLASS_DARK
        leftRightDelta > UNEVEN_LR_DELTA -> CLASS_UNEVEN
        else -> CLASS_NORMAL
    }
}
