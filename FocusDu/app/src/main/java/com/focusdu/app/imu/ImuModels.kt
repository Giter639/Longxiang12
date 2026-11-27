package com.focusdu.app.imu

/**
 * X22 IMU 接口协议 v0（R4 定稿）。与 vision/protocol/VisionModels.kt 同一套约定：
 * 阈值与滞回在算法端（X24 规则状态机），客户端只做防抖显示与断流兜底，不做判定。
 *
 * IMU 能判的五态（视觉六态管不了的盲区）：低头/侧趴看姿态角，乱动看运动强度，
 * 离座看长时间静止。偏近/困倦/离屏仍是视觉专属，融合仲裁在 X26。
 */
enum class ImuState(val label: String) {
    NORMAL("正常"),
    HEAD_DOWN("低头"),
    LEAN("侧趴"),
    FIDGET("乱动"),
    AWAY("离开"),
}

/**
 * BLE 20 字节帧（X25 固件）→ App 解包后的内存对象，采样率 25Hz。
 * 原始 6 轴由设备端上报；pitch/roll/motionRms 由引擎实现方的特征层填充
 * （Mock 直接给脚本值，X23/X24 真实现按下方单位约定计算）。
 */
data class ImuSample(
    val tsMs: Long,          // 单调时钟毫秒（App 侧接收时刻，设备端不计时）
    val seq: Long,           // 递增帧号（BLE 帧内 seq，用于断连查漏）
    // 原始 6 轴
    val axMg: Float, val ayMg: Float, val azMg: Float,    // 加速度（毫g）
    val gxDps: Float, val gyDps: Float, val gzDps: Float, // 角速度（°/s）
    // 特征层输出（X24 判定依据）
    val pitchDeg: Float,     // 俯仰：正=低头（重力低通解算）
    val rollDeg: Float,      // 横滚：正=右倾
    val motionRms: Float,    // 500ms 滑窗合加速度 RMS（g），静止≈0.02、乱动>0.3
    // R4 判定层
    val state: ImuState,
    val conf: Float,         // 判定置信度 0~1，<0.5 的样本客户端视为无效（与视觉同规）
)

/**
 * 个人基线（X24）：P0X 绑定页静坐采集 5s 后写入，判定阈值在基线上叠加；
 * 无基线时引擎用协议默认阈值。经 [ImuEngine.setBaseline] 注入，与视觉 T1-04 同机制。
 */
data class ImuBaseline(
    val pitchDeg: Float,     // 静坐基线俯仰
    val rollDeg: Float,      // 静坐基线横滚
    val motionRms: Float,    // 静坐基线运动强度
)
