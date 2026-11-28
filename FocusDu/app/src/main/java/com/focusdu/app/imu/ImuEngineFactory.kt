package com.focusdu.app.imu

enum class ImuEngineMode {
    BLE,
    MOCK_FALLBACK,
    MOCK,
}

data class ImuEngineBinding(
    val engine: ImuEngine,
    val mode: ImuEngineMode,
)

/**
 * IMU 引擎绑定（X23，与 vision/VisionEngineFactory 同构）：
 * BLE 真引擎优先；本机无蓝牙/初始化失败时回退 Mock 并通过 [ImuEngineMode.MOCK_FALLBACK]
 * 让绑定页明确标注，不把演示数据伪装成真数据（X06 口径）。
 * 权限缺失不回退（真引擎可用，status=NeedsPermission 由 P15 处理）。
 */
object ImuEngineFactory {
    fun bind(
        bleFactory: () -> ImuEngine,
        mockFactory: () -> ImuEngine,
        onFallback: (Throwable) -> Unit,
    ): ImuEngineBinding = try {
        ImuEngineBinding(bleFactory(), ImuEngineMode.BLE)
    } catch (error: LinkageError) {
        onFallback(error)
        ImuEngineBinding(mockFactory(), ImuEngineMode.MOCK_FALLBACK)
    } catch (error: Exception) {
        onFallback(error)
        ImuEngineBinding(mockFactory(), ImuEngineMode.MOCK_FALLBACK)
    }
}
