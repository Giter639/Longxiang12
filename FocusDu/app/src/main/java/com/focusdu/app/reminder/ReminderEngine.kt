package com.focusdu.app.reminder

import com.focusdu.app.core.EventTypes
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.core.TrackEvent
import com.focusdu.app.track.Tracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * T3-03 分级提醒引擎 —— 任务卡标注「最易做烂的功能点」，规则顺序即优先级：
 *
 *   静默 → 短时只记录 → 同事件边缘压制 → 用户延后 → 冷却 → 次数上限 → 延后到休息 → 提醒
 *
 * 验收锚点（全部有单测覆盖）：
 * - 短时异常只记录；
 * - 持续风险才提示，且同一事件只提示一次（边缘触发）；
 * - 冷却 ≥3min 100% 生效；
 * - 健康优先 25min 内提醒 ≤3 次。
 */
class ReminderEngine(
    private val clock: MonotonicClock,
    private val tracker: Tracker,
    var policy: ReminderPolicy = ReminderPolicy(),
) {
    private val _decisions = MutableStateFlow<List<ReminderDecision>>(emptyList())
    val decisions: StateFlow<List<ReminderDecision>> = _decisions   // P08 页面消费，最多留 20 条

    // ---- 引擎状态 ----
    private val lastRemindAt = HashMap<RiskType, Long>()       // 每类上次提醒时刻
    private val snoozeUntil = HashMap<RiskType, Long>()        // 每类延后截止时刻
    private val episodeEnd = HashMap<RiskType, Long>()         // 每类当前持续事件的结束时刻
    private val episodeRemindedLevel = HashMap<RiskType, RiskLevel>()  // 本事件已提醒的最高等级
    private val remindTimes = ArrayDeque<Long>()               // 时间窗内已提醒时刻
    private val shownAt = HashMap<String, Long>()              // reminderId -> 提醒时刻（改善率统计用）

    /** 用户开关：静默（全部只记录） */
    @Volatile
    var silentMode: Boolean = false

    /** 用户开关：延后到休息（SEVERE 永不延后） */
    @Volatile
    var deferMode: Boolean = false

    private val deferredQueue = ArrayDeque<RiskEvent>()

    /** 算法侧/接线层投递一条已确认风险事件，返回决策 */
    fun onRiskEvent(e: RiskEvent): ReminderDecision {
        val now = clock.nowMs()
        val d = decide(e, now)
        record(e, d, now)
        return d
    }

    private fun decide(e: RiskEvent, now: Long): ReminderDecision {
        // 1. 静默模式：一切只记录
        if (silentMode) return ReminderDecision(e, ReminderAction.SILENT_LOGGED, "静默模式")

        // 2. 短时异常只记录
        val persist = policy.persistMsByType[e.type] ?: 15_000L
        if (e.durationMs < persist) return ReminderDecision(e, ReminderAction.LOG_ONLY, "短时只记录(${persist / 1000}s)")

        // 3. 同一持续事件只提醒一次（间隔 ≤episodeGapMs 视为同一事件）；升级可再提醒
        val prevEnd = episodeEnd[e.type]
        val sameEpisode = prevEnd != null && e.startMs - prevEnd <= policy.episodeGapMs
        if (sameEpisode) {
            val reminded = episodeRemindedLevel[e.type]
            if (reminded != null && e.level.ordinal <= reminded.ordinal) {
                return ReminderDecision(e, ReminderAction.EDGE_SUPPRESSED, "同事件边缘压制")
            }
        }

        // 4. 用户延后窗口
        val snooze = snoozeUntil[e.type]
        if (snooze != null && now < snooze) {
            return ReminderDecision(e, ReminderAction.SNOOZE_SUPPRESSED, "用户延后中")
        }

        // 5. 冷却 ≥3min（按类型）
        val last = lastRemindAt[e.type]
        if (last != null && now - last < policy.cooldownMs) {
            return ReminderDecision(e, ReminderAction.COOLDOWN_SUPPRESSED, "冷却${policy.cooldownMs / 1000}s")
        }

        // 6. 时间窗次数上限（健康优先验收：25min ≤3 次）
        while (remindTimes.isNotEmpty() && now - remindTimes.first() > policy.capWindowMs) remindTimes.removeFirst()
        if (remindTimes.size >= policy.capPerWindow) {
            return ReminderDecision(e, ReminderAction.CAP_SUPPRESSED, "窗口内上限${policy.capPerWindow}次")
        }

        // 7. 延后到休息（SEVERE 永不延后）
        if (deferMode && e.level != RiskLevel.SEVERE) {
            deferredQueue.addLast(e)
            return ReminderDecision(e, ReminderAction.DEFER_TO_REST, "延后到休息")
        }

        // 8. 提醒
        val reminderId = "R$now-${e.type.name}"
        return ReminderDecision(e, ReminderAction.REMIND, "持续风险提示", reminderId)
    }

    private fun record(e: RiskEvent, d: ReminderDecision, now: Long) {
        _decisions.value = (_decisions.value + d).takeLast(20)
        when (d.action) {
            ReminderAction.REMIND -> {
                lastRemindAt[e.type] = now
                remindTimes.addLast(now)
                episodeEnd[e.type] = e.startMs + e.durationMs
                val prev = episodeRemindedLevel[e.type]
                if (prev == null || e.level.ordinal > prev.ordinal) episodeRemindedLevel[e.type] = e.level
                shownAt[d.reminderId!!] = now
                tracker.log(TrackEvent(0L, now, EventTypes.REMINDER_SHOWN, refId = d.reminderId, detail = "${e.type}:${e.level}"))
            }
            ReminderAction.DEFER_TO_REST -> {
                episodeEnd[e.type] = e.startMs + e.durationMs
                tracker.log(TrackEvent(0L, now, EventTypes.REMINDER_DEFERRED, detail = "${e.type}:${e.level}"))
            }
            else -> Unit
        }
    }

    /** 用户在提醒上点「延后」 */
    fun snooze(type: RiskType) {
        snoozeUntil[type] = clock.nowMs() + policy.snoozeMs
    }

    /** 风险解除：通知引擎记录「提醒后 60s 内恢复」（T6-02 提醒改善率数据源） */
    fun onRiskCleared(reminderId: String) {
        val at = shownAt[reminderId] ?: return
        val now = clock.nowMs()
        tracker.log(TrackEvent(0L, now, EventTypes.RISK_CLEARED, refId = reminderId, metricMs = now - at))
        shownAt.remove(reminderId)
    }

    /** 休息开始：排空延后队列，返回本轮休息要展示的延迟摘要（docx：休息时查看延迟消息摘要） */
    fun drainDeferredForRest(): List<RiskEvent> {
        val list = deferredQueue.toList()
        deferredQueue.clear()
        return list
    }

    /** 开始新一轮番茄时重置会话内状态（冷却等跨轮保留由 policy 决定，骨架期全重置） */
    fun resetForNewRound() {
        lastRemindAt.clear()
        snoozeUntil.clear()
        episodeEnd.clear()
        episodeRemindedLevel.clear()
        remindTimes.clear()
        deferredQueue.clear()
        _decisions.value = emptyList()
    }
}
