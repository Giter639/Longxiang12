package com.focusdu.app.core

/**
 * 单调时钟：全工程所有计时（番茄、风险持续、冷却）一律走它。
 * 底层是 SystemClock.elapsedRealtime()，息屏/深度睡眠不漂移 —— 这是
 * T3-02「息屏 30min 恢复误差 <2s」能够成立的技术根基，禁止改用 currentTimeMillis。
 */
interface MonotonicClock {
    fun nowMs(): Long
}

class AndroidMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}

/** 仅单元测试用：手动拨动的假时钟 */
class FakeMonotonicClock(startMs: Long = 0L) : MonotonicClock {
    var now: Long = startMs
        private set

    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}
