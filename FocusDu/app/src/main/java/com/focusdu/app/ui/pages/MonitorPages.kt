package com.focusdu.app.ui.pages

import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.databinding.FragmentPageBinding
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.pomodoro.PomodoroMode
import com.focusdu.app.reminder.ReminderAction
import com.focusdu.app.ui.common.BasePageFragment
import com.focusdu.app.ui.common.RingCountdownView
import com.focusdu.app.ui.common.TrendLineChart
import com.focusdu.app.vision.StateStreamDebouncer
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper

/** P06 番茄钟 —— R1（T3-01 三模式 / T3-02 计时恢复）：环形倒计时 + 提醒边缘横幅 */
class P06PomodoroPage : BasePageFragment() {
    override val ownerTag = "R1 T3-01/T3-02 · 引擎+前台服务已接通（Mock 数据驱动）"
    override val hintText = "环形倒计时显示剩余进度；持续风险触发提醒时顶部横幅边缘提示（冷却/压制规则在引擎内，验收有单测）。"

    private var pauseResumeBtn: com.google.android.material.button.MaterialButton? = null
    private var ring: RingCountdownView? = null
    private var banner: TextView? = null
    private var reminderCountAtStart = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onPageReady(view: android.view.View) {
        addButton("开始 · 标准 25/5") { vm.startPomodoro(PomodoroMode.STANDARD) }
        addButton("开始 · 深度 50/10") { vm.startPomodoro(PomodoroMode.DEEP) }
        addButton("开始 · 健康优先 25/5") { vm.startPomodoro(PomodoroMode.HEALTH_FIRST) }
        pauseResumeBtn = addButton("暂停") { togglePause() }
        addButton("放弃本轮 / 跳过休息") { vm.giveUp() }

        // 环形倒计时
        ring = RingCountdownView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 640
            )
        }
        binding.pageBody.addView(ring, 0)

        // 提醒边缘横幅（REMIND 决策到达时显示 4 秒）
        banner = TextView(requireContext()).apply {
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#FDEBD0"))
            setTextColor(Color.parseColor("#8C4A0F"))
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 24 }
        }
        binding.pageBody.addView(banner, 0)
        reminderCountAtStart = vm.reminders.value.size

        viewLifecycleOwner.lifecycleScope.launch {
            vm.pomodoro.collect { s ->
                ring?.let { r ->
                    when (s.phase) {
                        Phase.FOCUS -> r.update(
                            s.focusRemainingMs.toFloat() / s.focusTargetMs.coerceAtLeast(1),
                            fmt(s.focusRemainingMs), "专注中 · 第 ${s.roundIndex} 轮", 0xFF2E7D6B.toInt(),
                        )
                        Phase.FOCUS_PAUSED -> r.update(
                            s.focusRemainingMs.toFloat() / s.focusTargetMs.coerceAtLeast(1),
                            fmt(s.focusRemainingMs), "已暂停", 0xFFC65A1E.toInt(),
                        )
                        Phase.BREAK -> r.update(
                            s.breakRemainingMs.toFloat() / s.breakTargetMs.coerceAtLeast(1),
                            fmt(s.breakRemainingMs), "休息中" + if (s.earlyBreakTriggered) "（已提前）" else "", 0xFF3B7EA1.toInt(),
                        )
                        Phase.FINISHED -> r.update(0f, "完成", "本轮结束", 0xFF2E7D6B.toInt())
                        Phase.IDLE -> r.update(1f, "--:--", "选择一种模式开始", 0xFF8A9A94.toInt())
                    }
                }
                pauseResumeBtn?.text = if (s.phase == Phase.FOCUS_PAUSED) "继续" else "暂停"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.reminders.collect { list ->
                // 只对本页打开后新出现的提醒弹横幅（历史决策不打扰）
                if (list.size > reminderCountAtStart) {
                    val last = list.lastOrNull() ?: return@collect
                    if (last.action == ReminderAction.REMIND) {
                        banner?.text = "⚠ ${last.event.type}（${last.event.level}）持续风险，请调整姿势或起身"
                        banner?.visibility = android.view.View.VISIBLE
                        handler.postDelayed({ banner?.visibility = android.view.View.GONE }, 4000)
                    }
                }
            }
        }
    }

    private fun togglePause() {
        if (vm.pomodoro.value.phase == Phase.FOCUS_PAUSED) vm.resume() else vm.pause()
    }

    private fun fmt(ms: Long): String {
        val t = ms / 1000
        return "%02d:%02d".format(t / 60, t % 60)
    }
}

/** P07 实时状态小窗 —— R1 接入层（X18，验收：断流 3s 内提示"检测中断"不白屏） */
class P07VisionMonitorPage : BasePageFragment() {
    override val ownerTag = "R1 接入 X18 · 状态源=Mock 引擎（R4 上线后自动切换）"
    override val hintText = "TODO：换成六态可视化小窗（色彩+图标）。防抖显示与断流兜底已在 StateStreamDebouncer 实现，本页只渲染其 UiState。"

    override fun onPageReady(view: android.view.View) {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.visionUi.collect { u ->
                binding.pageStatus.text = when (u) {
                    is StateStreamDebouncer.UiState.Idle -> "等待数据…"
                    is StateStreamDebouncer.UiState.Live ->
                        "当前状态：${u.stateLabel}｜置信度 ${"%.2f".format(u.conf)}｜距离档位 ${u.distanceBand}｜困倦 ${u.drowsiness}"
                    is StateStreamDebouncer.UiState.Interrupted ->
                        "⚠ 检测中断（3 秒无有效帧）—— 断流兜底生效，界面保留最后状态：${u.lastState?.label ?: "—"}，不白屏"
                }
            }
        }
    }
}

/** P08 提醒中心 —— R1 引擎（T3-03）+ R2 文案（T5-01，验收：36 条文案齐全无医学化表述） */
class P08ReminderCenterPage : BasePageFragment() {
    override val ownerTag = "R1 规则 T3-03 · R2 文案 T5-01"
    override val hintText = "TODO：提醒弹层/边缘提示条样式 + 三套人格文案（温和/教练/简洁严格）。规则链与冷却已在 ReminderEngine 实现，验收有单测。"

    private var silent = false
    private var defer = false

    override fun onPageReady(view: android.view.View) {
        addButton("切换静默模式（全部只记录）") {
            silent = !silent
            vm.setSilent(silent)
            binding.pageStatus.text = if (silent) "静默模式：开" else "静默模式：关"
        }
        addButton("切换延后到休息") {
            defer = !defer
            vm.setDefer(defer)
        }
        addButton("延后距离提醒 60s（演示）") { vm.snooze(com.focusdu.app.reminder.RiskType.DISTANCE) }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.reminders.collect { list ->
                if (list.isEmpty()) {
                    binding.pageStatus.text = "尚无提醒决策 —— 开一轮番茄后，Mock 引擎会持续产生风险事件"
                } else {
                    binding.pageStatus.text = list.takeLast(8).reversed().joinToString("\n") { d ->
                        when (d.action) {
                            ReminderAction.REMIND -> "🔔 提醒 ${d.event.type}(${d.event.level}) · ${d.rule}"
                            ReminderAction.LOG_ONLY -> "📝 只记录 ${d.event.type} · ${d.rule}"
                            ReminderAction.DEFER_TO_REST -> "⏩ 延后到休息 ${d.event.type}"
                            ReminderAction.COOLDOWN_SUPPRESSED -> "❄ 冷却压制 ${d.event.type}"
                            ReminderAction.SNOOZE_SUPPRESSED -> "💤 延后压制 ${d.event.type}"
                            ReminderAction.CAP_SUPPRESSED -> "🚫 上限压制 ${d.event.type}"
                            ReminderAction.EDGE_SUPPRESSED -> "↩ 同事件压制 ${d.event.type}"
                            ReminderAction.SILENT_LOGGED -> "🔇 静默记录 ${d.event.type}"
                        }
                    }
                }
            }
        }
    }
}

/** P09 休息页 —— R1（T3-04，验收：休息结束自动写入报告）+ 恢复校验结果展示 */
class P09RestPage : BasePageFragment() {
    override val ownerTag = "R1 休息页 T3-04 · R4 恢复识别 T2-03"
    override val hintText = "休息期间自动统计离开屏幕时长；休息结束时恢复校验结论展示在这里，并自动写入本轮报告（REST_VALIDATED）。"

    override fun onPageReady(view: android.view.View) {
        addButton(getString(R.string.p09_notification_summary_button)) {
            val summaries = vm.notificationSummary()
            binding.pageStatus.text = if (summaries.isEmpty()) {
                getString(R.string.p09_notification_summary_empty)
            } else {
                summaries.joinToString("\n") {
                    getString(R.string.p09_notification_summary_item, it.appLabel, it.count)
                }
            }
        }
        addButton("查看延后消息摘要") {
            val list = vm.drainDeferredForRest()
            binding.pageStatus.text = if (list.isEmpty()) "没有延后的提醒"
            else list.joinToString("\n") { "延后：${it.type}(${it.level}) 持续 ${it.durationMs / 1000}s" }
        }
        addButton("提前结束休息（进入下一轮）") { vm.giveUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.pomodoro.collect { s ->
                when (s.phase) {
                    Phase.BREAK -> {
                        val t = s.breakRemainingMs / 1000
                        val away = vm.currentAwayMs() / 1000
                        binding.pageStatus.text =
                            "休息中 · 剩余 %02d:%02d\n已离开屏幕 $away 秒（≥60 秒算有效休息）\n建议起身远眺".format(t / 60, t % 60)
                    }
                    Phase.FINISHED -> {
                        val check = vm.restCheck()
                        binding.pageStatus.text = check?.summary ?: "本轮结束"
                    }
                    else -> binding.pageStatus.text = "休息页仅在番茄休息阶段生效（当前：${s.phase}）；休息结束后的恢复校验结论也会展示在这里"
                }
            }
        }
    }
}
