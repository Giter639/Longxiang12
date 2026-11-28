package com.focusdu.app.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** X23 验收锚点：无蓝牙/初始化失败回退 Mock（X06 口径：不伪装真数据） */
class ImuEngineFactoryTest {

    private class FakeEngine : ImuEngine {
        override fun samples(): kotlinx.coroutines.flow.Flow<ImuSample> =
            kotlinx.coroutines.flow.emptyFlow()
        override fun stop() {}
    }

    @Test
    fun `BLE 引擎就绪时直接绑定`() {
        val real = FakeEngine()
        var fallbackCalled = false
        val binding = ImuEngineFactory.bind(
            bleFactory = { real },
            mockFactory = { throw AssertionError("不应创建 Mock") },
            onFallback = { fallbackCalled = true },
        )
        assertSame(real, binding.engine)
        assertEquals(ImuEngineMode.BLE, binding.mode)
        assertEquals(false, fallbackCalled)
    }

    @Test
    fun `初始化失败回退 Mock 并明确标注模式`() {
        val mock = FakeEngine()
        var reported: Throwable? = null
        val binding = ImuEngineFactory.bind(
            bleFactory = { error("no bluetooth") },
            mockFactory = { mock },
            onFallback = { reported = it },
        )
        assertSame(mock, binding.engine)
        assertEquals(ImuEngineMode.MOCK_FALLBACK, binding.mode)
        assertEquals("no bluetooth", reported?.message)
    }

    @Test
    fun `LinkageError 同样回退`() {
        val mock = FakeEngine()
        val binding = ImuEngineFactory.bind(
            bleFactory = { throw LinkageError("jni missing") },
            mockFactory = { mock },
            onFallback = {},
        )
        assertEquals(ImuEngineMode.MOCK_FALLBACK, binding.mode)
        assertSame(mock, binding.engine)
    }
}
