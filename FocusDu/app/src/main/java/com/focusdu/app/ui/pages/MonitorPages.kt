package com.focusdu.app.ui.pages

import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.databinding.FragmentPageBinding
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.pomodoro.PomodoroMode
import com.focusdu.app.reminder.ReminderAction
import com.focusdu.app.reminder.ReminderMessageResolver
import com.focusdu.app.reminder.ReminderPersona
import com.focusdu.app.imu.ble.BleImuEngine
import com.focusdu.app.ui.common.BasePageFragment
import com.focusdu.app.ui.common.RingCountdownView
import com.focusdu.app.ui.common.TrendLineChart
import com.focusdu.app.vision.CameraState
import com.focusdu.app.vision.CameraStatus
import com.focusdu.app.vision.StateStreamDebouncer
import com.focusdu.app.vision.VisionEngineMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper

/** P06 番茄钟 —— R1（T3-01 三模式 / T3-02 计时恢复）：环形倒计时 + 提醒边缘横幅 */
class P06PomodoroPage : BasePageFragment() {
    override val ownerTag = "R1 T3-01/T3-02 · 引擎+前台服务已接通"
    override val hintText = "环形倒计时显示剩余进度；持续风险触发提醒时顶部横幅边缘提示（冷却/压制规则在引擎内，验收有单测）。"

    private var pauseResumeBtn: com.google.android.material.button.MaterialButton? = null
    private var ring: RingCountdownView? = null
    private var banner: TextView? = null
    private var reminderCountAtStart = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onPageReady(view: android.view.View) {
        // 环形倒计时（大数字主视觉）
        ring = RingCountdownView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 640
            )
        }
        binding.pageBody.addView(ring, 0)

        // 三种模式并排选择（次级样式，等宽）
        val modeRow = addButtonRow()
        addRowButton(modeRow, getString(R.string.p06_mode_standard)) { vm.startPomodoro(PomodoroMode.STANDARD) }
        addRowButton(modeRow, getString(R.string.p06_mode_deep)) { vm.startPomodoro(PomodoroMode.DEEP) }
        addRowButton(modeRow, getString(R.string.p06_mode_health)) { vm.startPomodoro(PomodoroMode.HEALTH_FIRST) }
        pauseResumeBtn = addButton(getString(R.string.p06_pause)) { togglePause() }
        addButton(getString(R.string.p06_give_up)) { vm.giveUp() }

        // 提醒边缘横幅（REMIND 决策到达时显示 4 秒；语义色走资源）
        banner = TextView(requireContext()).apply {
            setPadding(24, 20, 24, 20)
            setBackgroundResource(R.drawable.bg_banner_warning)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_warning))
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
                        banner?.text = vm.reminderText(last.event)
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
    override val ownerTag: String
        get() = getString(R.string.p07_owner)
    override val hintText: String
        get() = getString(R.string.p07_hint)

    override fun onPageReady(view: android.view.View) {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(vm.visionUi, vm.cameraStatus) { ui, camera ->
                Triple(ui, camera, vm.visionMode)
            }.collect { (ui, camera, mode) ->
                binding.pageStatus.text = statusText(ui, camera, mode)
            }
        }

        // X26：IMU 通道状态卡（观测用，不进埋点；连接状态 + 实时判定）
        val imuCard = addCard(getString(R.string.p07_imu_section))
        val imuConn = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_ink_secondary))
        }
        val imuState = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_ink_secondary))
        }
        imuCard.addView(imuConn)
        imuCard.addView(imuState)

        viewLifecycleOwner.lifecycleScope.launch {
            vm.imuEngineStatus?.collect { st ->
                val conn = when (st) {
                    is BleImuEngine.Status.Connected -> getString(R.string.p07_imu_conn_connected, st.deviceName)
                    is BleImuEngine.Status.Reconnecting -> getString(R.string.p07_imu_conn_reconnecting)
                    is BleImuEngine.Status.Connecting, is BleImuEngine.Status.Scanning ->
                        getString(R.string.p07_imu_conn_pending)
                    is BleImuEngine.Status.NeedsPermission -> getString(R.string.p07_imu_conn_permission)
                    else -> getString(R.string.p07_imu_conn_off)
                }
                imuConn.text = getString(R.string.p07_imu_conn_fmt, conn) +
                    "　·　" + getString(R.string.p07_imu_mode_fmt, vm.modeLabelForImu())
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.imuUi.collect { u ->
                imuState.text = getString(
                    R.string.p07_imu_state_fmt,
                    when (u) {
                        is StateStreamDebouncer.UiState.Live -> u.stateLabel
                        is StateStreamDebouncer.UiState.Interrupted ->
                            getString(R.string.p07_imu_state_interrupted)
                        StateStreamDebouncer.UiState.Idle -> getString(R.string.p07_imu_state_idle)
                    },
                )
            }
        }
    }

    /** 相机没在取流时先说明原因（没开番茄 / 启动中 / 启动失败），避免「等待数据」让人以为是卡死 */
    private fun statusText(
        ui: StateStreamDebouncer.UiState,
        camera: CameraStatus,
        mode: VisionEngineMode,
    ): String {
        val modeText = getString(R.string.p07_runtime_mode, modeLabel(mode))
        if (camera.state != CameraState.STREAMING) {
            val base = when (camera.state) {
                CameraState.STARTING -> getString(R.string.p07_camera_starting)
                CameraState.UNAVAILABLE -> getString(R.string.p07_camera_unavailable)
                CameraState.ERROR -> getString(R.string.p07_camera_error, camera.errorCode ?: "-")
                else -> getString(R.string.p07_camera_idle)
            }
            return "$base\n$modeText"
        }
        val base = when (ui) {
            is StateStreamDebouncer.UiState.Live -> getString(
                R.string.p07_live, ui.stateLabel, ui.conf, ui.distanceBand, ui.drowsiness,
            )
            is StateStreamDebouncer.UiState.Interrupted -> getString(
                R.string.p07_interrupted, ui.lastState?.label ?: "—",
            )
            StateStreamDebouncer.UiState.Idle -> getString(R.string.p07_camera_waiting)
        }
        return "$base\n$modeText"
    }

    private fun modeLabel(mode: VisionEngineMode): String = getString(
        when (mode) {
            VisionEngineMode.MEDIAPIPE -> R.string.p07_mode_mediapipe
            VisionEngineMode.MOCK_FALLBACK -> R.string.p07_mode_mock_fallback
            VisionEngineMode.MOCK -> R.string.p07_mode_mock
        }
    )
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
            combine(vm.reminders, vm.reminderPersona) { list, persona ->
                list to persona
            }.collect { (list, persona) ->
                if (list.isEmpty()) {
                    binding.pageStatus.text = "尚无提醒决策 —— 开一轮番茄后，Mock 引擎会持续产生风险事件"
                } else {
                    binding.pageStatus.text = list.takeLast(8).reversed().joinToString("\n") { d ->
                        when (d.action) {
                            ReminderAction.REMIND ->
                                "🔔 ${getString(ReminderMessageResolver.resourceId(d.event, persona))}"
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
