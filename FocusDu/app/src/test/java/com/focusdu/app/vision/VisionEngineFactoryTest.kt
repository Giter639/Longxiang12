package com.focusdu.app.vision

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionEngineFactoryTest {

    @Test
    fun usesMediaPipeWhenAvailable() {
        val mediaPipe = fakeEngine("mediapipe")
        val mock = fakeEngine("mock")
        var fallbackCalled = false

        val binding = VisionEngineFactory.bind(
            mediaPipeFactory = { mediaPipe },
            mockFactory = { mock },
            onFallback = { fallbackCalled = true },
        )

        assertEquals(VisionEngineMode.MEDIAPIPE, binding.mode)
        assertEquals(mediaPipe, binding.engine)
        assertFalse(fallbackCalled)
    }

    @Test
    fun fallsBackToMockWhenNativeLibraryIsMissing() {
        val mock = fakeEngine("mock")
        var captured: Throwable? = null

        val binding = VisionEngineFactory.bind(
            mediaPipeFactory = { throw UnsatisfiedLinkError("libmediapipe_tasks_vision_jni.so not found") },
            mockFactory = { mock },
            onFallback = { captured = it },
        )

        assertEquals(VisionEngineMode.MOCK_FALLBACK, binding.mode)
        assertEquals(mock, binding.engine)
        assertTrue(captured is UnsatisfiedLinkError)
    }

    private fun fakeEngine(name: String): VisionEngine = object : VisionEngine {
        override fun samples(): Flow<com.focusdu.app.vision.protocol.VisionSample> = emptyFlow()
        override fun stop() = Unit
        override fun setBaseline(b: PersonalBaseline?) = Unit
        override fun toString(): String = name
    }
}
