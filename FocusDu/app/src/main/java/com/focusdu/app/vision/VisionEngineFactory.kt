package com.focusdu.app.vision

enum class VisionEngineMode {
    MEDIAPIPE,
    MOCK_FALLBACK,
    MOCK,
}

data class VisionEngineBinding(
    val engine: VisionEngine,
    val mode: VisionEngineMode,
)

/**
 * MediaPipe 的 Android AAR 不包含 x86_64 JNI，因此 Intel/AMD 模拟器会在类初始化时抛
 * UnsatisfiedLinkError。真机仍优先使用 MediaPipe；仅在本机原生库不可用或模型初始化失败时
 * 回退 Mock，并通过 [VisionEngineMode.MOCK_FALLBACK] 让 UI 明确标注，避免伪装成真实识别。
 */
object VisionEngineFactory {
    fun bind(
        mediaPipeFactory: () -> VisionEngine,
        mockFactory: () -> VisionEngine,
        onFallback: (Throwable) -> Unit,
    ): VisionEngineBinding = try {
        VisionEngineBinding(mediaPipeFactory(), VisionEngineMode.MEDIAPIPE)
    } catch (error: UnsatisfiedLinkError) {
        onFallback(error)
        VisionEngineBinding(mockFactory(), VisionEngineMode.MOCK_FALLBACK)
    } catch (error: LinkageError) {
        onFallback(error)
        VisionEngineBinding(mockFactory(), VisionEngineMode.MOCK_FALLBACK)
    } catch (error: Exception) {
        onFallback(error)
        VisionEngineBinding(mockFactory(), VisionEngineMode.MOCK_FALLBACK)
    }
}
