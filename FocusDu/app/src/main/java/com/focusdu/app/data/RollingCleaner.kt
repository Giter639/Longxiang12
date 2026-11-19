package com.focusdu.app.data

import com.focusdu.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * T7-03 存储容量滚动与清理策略：
 * - 埋点 events 只留最近 200_000 条（口径重算的依据，优先保）
 * - 视觉原始样本只留最近 50_000 条；且报告生成完毕立即删除该轮原始样本
 * - 会话、报告体积小，暂不清理（复赛答辩要展示历史趋势）
 * 注：时间戳是单调时钟，重启会归零，所以只按条数/会话清理，不按时间清理。
 */
class RollingCleaner(
    private val db: AppDatabase,
    private val scope: CoroutineScope,
) {
    fun onAppStart() {
        scope.launch(Dispatchers.IO) {
            db.trackEventDao().trimTo(EVENT_CAP)
            db.visionSampleDao().trimTo(SAMPLE_CAP)
        }
    }

    fun onReportDone(sessionId: Long) {
        scope.launch(Dispatchers.IO) {
            db.visionSampleDao().deleteSession(sessionId)
        }
    }

    companion object {
        const val EVENT_CAP = 200_000
        const val SAMPLE_CAP = 50_000
    }
}
