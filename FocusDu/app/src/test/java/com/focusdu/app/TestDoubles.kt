package com.focusdu.app

import com.focusdu.app.core.TrackEvent
import com.focusdu.app.data.db.PomodoroCheckpointEntity
import com.focusdu.app.data.db.PomodoroSessionEntity
import com.focusdu.app.pomodoro.PomodoroStore
import com.focusdu.app.track.Tracker

/** 测试替身：内存存储 + 事件收集器 */
class FakeTracker : Tracker {
    val events = mutableListOf<TrackEvent>()
    override fun log(event: TrackEvent) {
        events.add(event)
    }
    override fun sessionEvents(sessionId: Long): List<TrackEvent> = events.filter { it.sessionId == sessionId }
}

class FakePomodoroStore : PomodoroStore {
    val sessions = mutableMapOf<Long, PomodoroSessionEntity>()
    var checkpoint: PomodoroCheckpointEntity? = null

    override suspend fun saveSession(e: PomodoroSessionEntity) {
        sessions[e.sessionId] = e
    }

    override suspend fun updateOutcome(sessionId: Long, outcome: String, endedAtWall: Long, healthEarlyBreak: Boolean) {
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(outcome = outcome, endedAtWall = endedAtWall, healthEarlyBreak = healthEarlyBreak)
        }
    }

    override suspend fun saveCheckpoint(c: PomodoroCheckpointEntity) {
        checkpoint = c
    }

    override suspend fun loadCheckpoint(): PomodoroCheckpointEntity? = checkpoint
    override suspend fun clearCheckpoint() {
        checkpoint = null
    }

    override suspend fun recentSessions(n: Int): List<PomodoroSessionEntity> =
        sessions.values.sortedByDescending { it.startedAtWall }.take(n)
}
