package com.focusdu.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * T7-03 本地数据存储与离线：全 App 唯一数据库。
 * 表结构变更必须过 Code Review（R1），因为涉及历史报告兼容。
 */
@Database(
    entities = [
        PomodoroSessionEntity::class,
        PomodoroCheckpointEntity::class,
        TrackEventEntity::class,
        VisionSampleEntity::class,
        ReportEntity::class,
        BaselineEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackEventDao(): TrackEventDao
    abstract fun visionSampleDao(): VisionSampleDao
    abstract fun sessionDao(): SessionDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun reportDao(): ReportDao
    abstract fun baselineDao(): BaselineDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "focusdu.db").build()
    }
}
