package com.focusdu.app.ui.pages

import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.databinding.FragmentPageBinding
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.pomodoro.PomodoroMode
import com.focusdu.app.reminder.ReminderAction
import com.focusdu.app.ui.common.BasePageFragment
import com.focusdu.app.vision.StateStreamDebouncer
import kotlinx.coroutines.launch

/** P06 番茄钟 —— R1（T3-01 三模式 / T3-02 计时恢复，验收：三模式可启动、息屏恢复误差<2s） */
class P06PomodoroPage : BasePageFragment() {
    override val ownerTag = "R1 T3-01/T3-02 · 引擎+前台服务已接通（Mock 数据驱动）"
    override val hintText = "TODO：把占位按钮换成环形倒计时与模式卡片；息屏续计已由引擎+前台服务实现，恢复逻辑在 PomodoroEngine.recoverIfPossible。"

    private var pauseResumeBtn: com.google.android.material.button.MaterialButton? = null

    override fun onPageReady(view: android.view.View) {
        addButton("开始 · 标准 25/5") { vm.startPomodoro(PomodoroMode.STANDARD) }
        addButton("开始 · 深度 50/10") { vm.startPomodoro(PomodoroMode.DEEP) }
        addButton("开始 · 健康优先 25/5") { vm.startPomodoro(PomodoroMode.HEALTH_FIRST) }
        pauseResumeBtn = addButton("暂停") { togglePause() }
        addButton("放弃本轮 / 跳过休息") { vm.giveUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.pomodoro.collect { s ->
                binding.pageStatus.text = when (s.phase) {
                    Phase.IDLE -> "未开始 · 选择一种模式"
                    Phase.FOCUS -> "第 ${s.roundIndex} 轮 ${s.mode?.label} 专注 · 剩余 ${fmt(s.focusRemainingMs)}" +
                            if (s.earlyBreakTriggered) "（已提前休息，下轮自动缩短）" else ""
                    Phase.FOCUS_PAUSED -> "已暂停 · 已专注 ${fmt(s.focusElapsedMs)}"
                    Phase.BREAK -> "休息 · 剩余 ${fmt(s.breakRemainingMs)}"
                    Phase.FINISHED -> "本轮完成 · 去看报告"
                }
                pauseResumeBtn?.text = if (s.phase == Phase.FOCUS_PAUSED) "继续" else "暂停"
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

/** P09 休息页 —— R1（T3-04，验收：休息结束自动写入报告）；R4 恢复判定（T2-03） */
class P09RestPage : BasePageFragment() {
    override val ownerTag = "R1 休息页 T3-04 · R4 恢复识别 T2-03"
    override val hintText = "TODO：休息倒计时大屏 + 起身引导 + 回来后的恢复校验展示（距离/姿态是否恢复）。延后消息摘要由 drainDeferredForRest 取。"

    override fun onPageReady(view: android.view.View) {
        addButton("查看延后消息摘要") {
            val list = vm.drainDeferredForRest()
            binding.pageStatus.text = if (list.isEmpty()) "没有延后的提醒"
            else list.joinToString("\n") { "延后：${it.type}(${it.level}) 持续 ${it.durationMs / 1000}s" }
        }
        addButton("提前结束休息（进入下一轮）") { vm.giveUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.pomodoro.collect { s ->
                if (s.phase == Phase.BREAK) {
                    val t = s.breakRemainingMs / 1000
                    binding.pageStatus.text = "休息中 · 剩余 %02d:%02d · 建议起身远眺".format(t / 60, t % 60)
                } else {
                    binding.pageStatus.text = "休息页仅在番茄休息阶段生效（当前：${s.phase}）"
                }
            }
        }
    }
}
