package com.focusdu.app.core

/**
 * 埋点事件类型常量（X02 埋点接口）。
 * T6-02 指标引擎只认这里的类型名做重算，验收要求「与埋点重算误差 <1%」，
 * 所以任何模块不得绕过 Tracker 自行记录数据。
 */
object EventTypes {
    const val FOCUS_START = "FOCUS_START"
    const val FOCUS_END = "FOCUS_END"            // metricMs = 本段专注累计毫秒（不含暂停）
    const val FOCUS_PAUSE = "FOCUS_PAUSE"
    const val FOCUS_RESUME = "FOCUS_RESUME"
    const val BREAK_START = "BREAK_START"
    const val BREAK_END = "BREAK_END"            // metricMs = 本段休息毫秒
    const val GIVE_UP = "GIVE_UP"
    const val EARLY_BREAK = "EARLY_BREAK"        // 健康优先：重度风险提前进入休息
    const val STATE_SPAN = "STATE_SPAN"          // 风险状态持续段：detail=状态名, metricMs=持续毫秒, refId=FOCUS/BREAK
    const val REMINDER_SHOWN = "REMINDER_SHOWN"  // refId=reminderId, detail=type:level
    const val REMINDER_DEFERRED = "REMINDER_DEFERRED"
    const val RISK_CLEARED = "RISK_CLEARED"      // refId=reminderId, metricMs=提醒到恢复的毫秒
    const val REST_VALIDATED = "REST_VALIDATED"  // P09 恢复校验结果，detail=结论
    const val REPORT_GENERATED = "REPORT_GENERATED" // metricMs=生成耗时毫秒（验收：结束 3s 内）
    const val GUARD_TRIGGER = "GUARD_TRIGGER"    // P10 拦截页触发（R3 接入后使用）
    const val PERMISSION_STATE = "PERMISSION_STATE" // detail=权限名:授予/拒绝（R3 T4-01 接入后使用）
}

/** 埋点事件：报告、趋势、对比的唯一数据来源（落 Room，离线可用） */
data class TrackEvent(
    val sessionId: Long,
    val atMs: Long,           // 单调时钟毫秒
    val type: String,
    val refId: String? = null,
    val metricMs: Long = 0L,
    val detail: String? = null,
)
