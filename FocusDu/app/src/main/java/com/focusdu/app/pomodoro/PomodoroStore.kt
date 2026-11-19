package com.focusdu.app.pomodoro

import com.focusdu.app.data.db.AppDatabase
import com.focusdu.app.data.db.PomodoroCheckpointEntity
import com.focusdu.app.data.db.PomodoroSessionEntity

/** 会话与检查点的持久化抽象：引擎不依赖 Room 细节，测试用内存假件替换 */
interface PomodoroStore {
    suspend fun saveSession(e: PomodoroSessionEntity)
    suspend fun updateOutcome(sessionId: Long, outcome: String, endedAtWall: Long, healthEarlyBreak: Boolean)
    suspend fun saveCheckpoint(c: PomodoroCheckpointEntity)
    suspend fun loadCheckpoint(): PomodoroCheckpointEntity?
    suspend fun clearCheckpoint()
    suspend fun recentSessions(n: Int): List<PomodoroSessionEntity>
}

class RoomPomodoroStore(private val db: AppDatabase) : PomodoroStore {

    override suspend fun saveSession(e: PomodoroSessionEntity) = db.sessionDao().upsert(e)

    override suspend fun updateOutcome(sessionId: Long, outcome: String, endedAtWall: Long, healthEarlyBreak: Boolean) {
        val old = db.sessionDao().byId(sessionId) ?: return
        db.sessionDao().upsert(old.copy(outcome = outcome, endedAtWall = endedAtWall, healthEarlyBreak = healthEarlyBreak))
    }

    override suspend fun saveCheckpoint(c: PomodoroCheckpointEntity) = db.checkpointDao().save(c)
    override suspend fun loadCheckpoint(): PomodoroCheckpointEntity? = db.checkpointDao().load()
    override suspend fun clearCheckpoint() = db.checkpointDao().clear()
    override suspend fun recentSessions(n: Int): List<PomodoroSessionEntity> = db.sessionDao().recent(n)
}
