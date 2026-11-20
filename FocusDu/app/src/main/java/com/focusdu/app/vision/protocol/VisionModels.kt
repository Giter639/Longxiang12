package com.focusdu.app.vision.protocol

/**
 * R4（T2-10）六态融合状态机输出。客户端 UI、提醒、报告全部只认这六个态，
 * 阈值与滞回在算法端，客户端（X18）只做防抖显示与断流兜底。
 */
enum class FusionState(val label: String) {
    NORMAL("正常"),
    NEAR("偏近"),
    HEAD_DOWN("低头"),
    DROWSY("困倦"),
    OFF_SCREEN("离屏"),
    AWAY("离开"),
}

/**
 * 接口协议 v0（docs/接口协议v0.md）的内存对象。
 * R4 的真引擎（MediaPipe 等）负责产出它；JSON 形态仅用于落盘对齐与复赛文档，进程内传对象即可。
 * 字段含义与单位见协议文档，改动协议必须双方（R1+R4）同时确认。
 */
data class VisionSample(
    val tsMs: Long,               // 单调时钟毫秒
    val seq: Long,                // 递增帧号
    // 人脸
    val facePresent: Boolean,
    val conf: Float,              // 检测置信度 0~1，<0.5 的样本客户端视为无效
    val faceScale: Float,         // 脸部尺度（与个人基线比较得距离档位）
    val headPitchDeg: Float,      // 俯仰：正=低头
    val headYawDeg: Float,        // 偏航
    val headForwardDeg: Float,    // 前倾（侧视模式才有意义）
    val shoulderTiltDeg: Float,   // 肩线倾角
    // 眼部
    val eyeOpenRatio: Float,      // 0=闭眼 1=睁眼
    val blinkRatePerMin: Float,
    val mouthOpenRatio: Float,
    // 光照
    val lumFace: Float,
    val lumDesktop: Float,
    val lumBackground: Float,
    val overexposureRatio: Float,
    val leftRightDelta: Float,
    val lightClass: String,       // normal/dark/backlight/glare/uneven/night
    // 在场
    val atDesk: Boolean,
    val offScreenRatio: Float,    // 视线离开屏幕占比
    val headStability: Float,
    // 算法判定层（阈值归 R4）
    val distanceBand: String,     // normal / near / veryNear
    val postureEvent: String,     // none / headDown / leaningForward / shoulderTilt
    val drowsiness: String,       // none / mild / moderate / severe
    val state: FusionState,       // 六态融合输出
)
