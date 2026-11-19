package com.focusdu.app.vision

import com.focusdu.app.data.db.AppDatabase
import com.focusdu.app.data.db.BaselineEntity

/** 个人基线（T1-04 算法侧字段，R1 存储侧对齐） */
data class PersonalBaseline(
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
    val sceneTag: String,
)

/**
 * 基线读写入口。算法侧（R4）在 P04 校准完成后 save()，
 * 距离/姿态判定时 current() 取用；无基线时上层应引导重新校准。
 */
class BaselineStore(private val db: AppDatabase) {

    suspend fun current(): PersonalBaseline? =
        db.baselineDao().load()?.toModel()

    suspend fun save(b: PersonalBaseline) {
        db.baselineDao().upsert(b.toEntity())
    }

    private fun BaselineEntity.toModel() = PersonalBaseline(
        faceScale, pitchDeg, yawDeg, forwardDeg, shoulderTiltDeg,
        lumFace, lumBackground, confidence, sampleCount, createdAtWall, sceneTag,
    )

    private fun PersonalBaseline.toEntity() = BaselineEntity(
        faceScale = faceScale, pitchDeg = pitchDeg, yawDeg = yawDeg,
        forwardDeg = forwardDeg, shoulderTiltDeg = shoulderTiltDeg,
        lumFace = lumFace, lumBackground = lumBackground,
        confidence = confidence, sampleCount = sampleCount,
        createdAtWall = createdAtWall, sceneTag = sceneTag,
    )
}
