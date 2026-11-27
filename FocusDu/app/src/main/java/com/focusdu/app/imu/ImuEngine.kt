package com.focusdu.app.imu

import kotlinx.coroutines.flow.Flow

/**
 * IMU 引擎抽象（X22 接口协议，与 vision/VisionEngine 同构）。
 * X23 的 BLE 真实现（建议类名 BleImuEngine）完成后到 AppContainer 换绑定即可，
 * 下游消费方一行不用动；无硬件时由 [MockImuEngine] 驱动全链验证。
 */
interface ImuEngine {
    /** 25Hz 样本流；实现方负责流的异常重启（与 VisionEngine 同一约定） */
    fun samples(): Flow<ImuSample>
    fun stop()

    /**
     * 注入个人基线（X24）：绑定页静坐采集后由上层调用，null 表示无基线。
     * 不依赖基线的实现（如 Mock）保持默认空实现。
     */
    fun setBaseline(baseline: ImuBaseline?) {}
}
