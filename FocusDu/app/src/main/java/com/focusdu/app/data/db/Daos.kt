package com.focusdu.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackEventDao {
    @Insert
    suspend fun insert(e: TrackEventEntity)

    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY atMs")
    suspend fun bySession(sessionId: Long): List<TrackEventEntity>

    @Query("SELECT * FROM events ORDER BY atMs")
    suspend fun all(): List<TrackEventEntity>

    /** 容量滚动：只留最近 cap 条（T7-03） */
    @Query("DELETE FROM events WHERE id <= (SELECT MAX(id) FROM events) - :cap")
    suspend fun trimTo(cap: Int)
}

@Dao
interface VisionSampleDao {
    @Insert
    suspend fun insert(s: VisionSampleEntity)

    @Query("SELECT COUNT(*) FROM vision_samples WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("DELETE FROM vision_samples WHERE id <= (SELECT MAX(id) FROM vision_samples) - :cap")
    suspend fun trimTo(cap: Int)

    /** 报告生成后原始样本即无用（口径重算只用 events），立即释放空间 */
    @Query("DELETE FROM vision_samples WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: PomodoroSessionEntity)

    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    suspend fun byId(id: Long): PomodoroSessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAtWall DESC LIMIT :n")
    suspend fun recent(n: Int): List<PomodoroSessionEntity>
}

@Dao
interface CheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(c: PomodoroCheckpointEntity)

    @Query("SELECT * FROM checkpoint WHERE id = 1")
    suspend fun load(): PomodoroCheckpointEntity?

    @Query("DELETE FROM checkpoint")
    suspend fun clear()
}

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: ReportEntity)

    @Query("SELECT * FROM reports ORDER BY generatedAtWall DESC LIMIT :n")
    suspend fun recent(n: Int): List<ReportEntity>
}

@Dao
interface BaselineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(b: BaselineEntity)

    @Query("SELECT * FROM baseline WHERE id = 1")
    suspend fun load(): BaselineEntity?
}
