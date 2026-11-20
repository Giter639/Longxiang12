package com.focusdu.app.reminder

/**
 * 分级提醒引擎（T3-03）的数据模型。
 * 输入是 R4 算法侧已确认的风险事件（多帧确认、起止误差≤1s），客户端不再做阈值判断。
 */
enum class RiskType { DISTANCE, POSTURE, DROWSINESS, LIGHT, SEDENTARY, OFF_SCREEN }

enum class RiskLevel { LIGHT, MODERATE, SEVERE }

data class RiskEvent(
    val type: RiskType,
    val level: RiskLevel,
    val startMs: Long,      // 单调时钟：事件开始
    val durationMs: Long,   // 事件持续时长
)

enum class ReminderAction {
    LOG_ONLY,              // 短时异常只记录（验收硬规则）
    REMIND,                // 边缘提示
    DEFER_TO_REST,         // 延后到休息阶段
    COOLDOWN_SUPPRESSED,   // 冷却期压制（验收：冷却 ≥3min 100% 生效）
    SNOOZE_SUPPRESSED,     // 用户延后窗口内压制
    CAP_SUPPRESSED,        // 时间窗内次数上限压制（健康优先验收：25min ≤3 次）
    EDGE_SUPPRESSED,       // 同一持续事件重复压制（边缘触发）
    SILENT_LOGGED,         // 静默模式只记录
}

data class ReminderDecision(
    val event: RiskEvent,
    val action: ReminderAction,
    val rule: String,           // 命中的规则名，答辩可解释性用
    val reminderId: String? = null,
)

data class ReminderPolicy(
    /** 持续低于该时长的异常只记录不提醒（毫秒）——「短时异常只记录」 */
    val persistMsByType: Map<RiskType, Long> = mapOf(
        RiskType.DISTANCE to 15_000L,
        RiskType.POSTURE to 10_000L,
        RiskType.DROWSINESS to 8_000L,
        RiskType.LIGHT to 20_000L,
        RiskType.SEDENTARY to 30 * 60_000L,
        RiskType.OFF_SCREEN to 5_000L,
    ),
    val cooldownMs: Long = 180_000L,           // 验收：≥3min，100% 生效
    val snoozeMs: Long = 60_000L,              // 用户「延后」窗口
    val capPerWindow: Int = 3,                 // 健康优先验收：25min 内提醒 ≤3 次
    val capWindowMs: Long = 25 * 60_000L,
    val episodeGapMs: Long = 5_000L,           // 同类型间隔 ≤5s 视为同一持续事件
)
