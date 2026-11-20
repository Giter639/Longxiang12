package com.focusdu.app.pomodoro

/**
 * T3-01 三种番茄模式（docx 基础功能表）。
 * HEALTH_FIRST（健康优先）：出现重度风险时提前进入休息，且下一轮自动缩短（引擎内实现）。
 */
enum class PomodoroMode(
    val label: String,
    val focusMs: Long,
    val breakMs: Long,
    val healthFirst: Boolean,
) {
    STANDARD("标准", 25 * 60_000L, 5 * 60_000L, false),
    DEEP("深度", 50 * 60_000L, 10 * 60_000L, false),
    HEALTH_FIRST("健康优先", 25 * 60_000L, 5 * 60_000L, true);
}
