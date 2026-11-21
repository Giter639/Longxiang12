package com.focusdu.app.guard

import com.focusdu.app.core.MonotonicClock

data class AppNotificationSummary(
    val packageName: String,
    val count: Int,
    val firstAtMs: Long,
    val lastAtMs: Long,
)

/**
 * 只记录“哪个应用在专注期间发过通知”，不读取标题、正文、联系人等内容。
 * 数据仅驻留内存；开始下一轮专注时清空，不写数据库、不上传。
 */
class NotificationMemoryStore(
    private val clock: MonotonicClock,
) {
    private val lock = Any()
    private var active = false
    private var currentSessionId = 0L
    private val records = linkedMapOf<String, MutableRecord>()

    fun beginSession(sessionId: Long) {
        synchronized(lock) {
            if (currentSessionId != sessionId) {
                records.clear()
                currentSessionId = sessionId
            }
            active = true
        }
    }

    fun endSession() {
        synchronized(lock) {
            active = false
        }
    }

    fun record(packageName: String) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            if (!active) return
            val now = clock.nowMs()
            val record = records.getOrPut(packageName) {
                MutableRecord(count = 0, firstAtMs = now, lastAtMs = now)
            }
            record.count++
            record.lastAtMs = now
        }
    }

    fun snapshot(): List<AppNotificationSummary> = synchronized(lock) {
        records.map { (packageName, value) ->
            AppNotificationSummary(
                packageName = packageName,
                count = value.count,
                firstAtMs = value.firstAtMs,
                lastAtMs = value.lastAtMs,
            )
        }.sortedBy { it.firstAtMs }
    }

    private data class MutableRecord(
        var count: Int = 0,
        val firstAtMs: Long,
        var lastAtMs: Long,
    )
}
