package com.focusdu.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 番茄会话主表：一轮 = 一条 */
@Entity(tableName = "sessions")
data class PomodoroSessionEntity(
    @PrimaryKey val sessionId: Long,      // 引擎用单调时钟生成
    val mode: String,                     // STANDARD / DEEP / HEALTH_FIRST
    val roundIndex: Int,                  // 当日第几轮
    val focusTargetMs: Long,
    val breakTargetMs: Long,
    val startedAtWall: Long,              // 可读墙钟，仅展示用
    val endedAtWall: Long? = null,
    val outcome: String? = null,          // FINISHED / GAVE_UP
    val healthEarlyBreak: Boolean = false,
)

/** 恢复检查点（T3-02）：进程被杀/息屏后按单调时钟数学恢复，不靠倒计时续命 */
@Entity(tableName = "checkpoint")
data class PomodoroCheckpointEntity(
    @PrimaryKey val id: Int = 1,          // 恒为单例行
    val sessionId: Long,
    val phase: String,                    // FOCUS / FOCUS_PAUSED / BREAK
    val mode: String,
    val segmentStartElapsed: Long,        // 当前段开始的单调时刻
    val pausedAccumMs: Long,              // 本段已累计的暂停毫秒
    val pauseStartElapsed: Long,          // 处于暂停态时的暂停起点（非暂停态=0）
    val savedAtElapsed: Long,
    val focusTargetMs: Long,
    val breakTargetMs: Long,
    val roundIndex: Int,
    val earlyBreakTriggered: Boolean,
)

/** 埋点落盘（口径重算的数据底座，验收：与埋点重算误差 <1%） */
@Entity(tableName = "events", indices = [Index("sessionId"), Index("atMs")])
data class TrackEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val atMs: Long,
    val type: String,
    val refId: String? = null,
    val metricMs: Long = 0,
    val detail: String? = null,
)

/** 视觉原始样本（滚动清理：容量上限策略见 RollingCleaner，不永久保留） */
@Entity(tableName = "vision_samples", indices = [Index("sessionId"), Index("atMs")])
data class VisionSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val atMs: Long,
    val state: String,
    val distanceBand: String,
    val drowsiness: String,
    val conf: Float,
    val faceScale: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
    val lumFace: Float,
    val lumBackground: Float,
    val lightClass: String,
)

/** 本轮报告（T6-01）：结束后本地生成，不上传 */
@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey val sessionId: Long,
    val generatedAtWall: Long,
    val summaryText: String,          // 人读摘要（骨架期不用 JSON，避免依赖）
    val focusMs: Long,
    val effectiveMs: Long,
    val riskRate: Double,
    val reminderCount: Int,
    val improvementRate: Double,
    val effectiveRestCount: Int,
    val completeness: Double,
)

/** 个人基线（T1-04，R4 算法侧产出，单例行） */
@Entity(tableName = "baseline")
data class BaselineEntity(
    @PrimaryKey val id: Int = 1,
    val faceScale: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
    val forwardDeg: Float,
    val shoulderTiltDeg: Float,
    val lumFace: Float,
    val lumBackground: Float,
    val confidence: Float,
    val sampleCount: Int,
    val createdAtWall: Long,
    val sceneTag: String,             // 书桌/床头/自习室
)
