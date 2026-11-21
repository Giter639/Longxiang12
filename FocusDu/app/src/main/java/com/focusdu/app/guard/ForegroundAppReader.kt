package com.focusdu.app.guard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * UsageStats 没有“实时前台回调”，只能轮询 UsageEvents。
 * ColorOS 可能延迟上报，因此 300ms 是产品目标值，必须以真机日志验证。
 */
class ForegroundAppReader(context: Context) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private var lastQueryWallMs = 0L
    private var lastForegroundPackage: String? = null

    @Suppress("DEPRECATION")
    fun poll(): String? {
        val nowWall = System.currentTimeMillis()
        val begin = if (lastQueryWallMs == 0L) nowWall - INITIAL_LOOKBACK_MS
        else (lastQueryWallMs - OVERLAP_MS).coerceAtMost(nowWall)
        lastQueryWallMs = nowWall

        val events = usageStats?.queryEvents(begin, nowWall) ?: return lastForegroundPackage
        val usageEvent = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(usageEvent)
            if (usageEvent.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                usageEvent.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                usageEvent.packageName?.let { lastForegroundPackage = it }
            }
        }
        return lastForegroundPackage
    }

    fun reset() {
        lastQueryWallMs = 0L
        lastForegroundPackage = null
    }

    private companion object {
        const val INITIAL_LOOKBACK_MS = 15_000L
        const val OVERLAP_MS = 1_000L
    }
}
