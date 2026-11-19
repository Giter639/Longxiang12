package com.focusdu.app.track

import com.focusdu.app.core.TrackEvent
import com.focusdu.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * X02 埋点接口：任何模块记录数据只允许调用 Tracker.log()。
 * 实现 = 内存镜像（本轮快速读取）+ Room 落盘（离线可用、跨会话重算）。
 */
interface Tracker {
    fun log(event: TrackEvent)
    fun sessionEvents(sessionId: Long): List<TrackEvent>
}

class RoomTracker(
    private val db: AppDatabase,
    private val scope: CoroutineScope,
) : Tracker {

    /**
     * 会话绑定器：番茄引擎自带的埋点有 sessionId，提醒/状态段等组件的埋点没有——
     * 由容器注入"当前会话"读取器，log 时自动归入，保证报告重算不漏数据。
     */
    @Volatile
    var sessionBinder: () -> Long = { 0L }

    // sessionId -> 本进程内已记录事件（RoomTracker 生命周期=进程，单轮会话量级安全）
    private val mirror = ConcurrentHashMap<Long, MutableList<TrackEvent>>()

    override fun log(event: TrackEvent) {
        val bound = if (event.sessionId <= 0) sessionBinder() else event.sessionId
        val e = if (bound > 0 && bound != event.sessionId) event.copy(sessionId = bound) else event
        mirror.getOrPut(e.sessionId) { mutableListOf() }.add(e)
        scope.launch(Dispatchers.IO) {
            db.trackEventDao().insert(
                com.focusdu.app.data.db.TrackEventEntity(
                    sessionId = e.sessionId,
                    atMs = e.atMs,
                    type = e.type,
                    refId = e.refId,
                    metricMs = e.metricMs,
                    detail = e.detail,
                )
            )
        }
    }

    override fun sessionEvents(sessionId: Long): List<TrackEvent> =
        mirror[sessionId]?.toList().orEmpty()
}
