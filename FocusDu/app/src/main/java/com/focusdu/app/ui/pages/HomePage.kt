package com.focusdu.app.ui.pages

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.ui.common.BasePageFragment
import kotlinx.coroutines.launch

/**
 * P05 今日概览（首页）：UI 改版后的产品首页。
 * 结构 = 今日概览卡（轮次/累计专注/平均风险率） + 主行动「开始专注」 + 快捷入口。
 * 数据全部来自 AppSessionViewModel（番茄状态流 + 本地报告聚合），不新增数据通路。
 */
class P05HomePage : BasePageFragment() {
    override val ownerTag = "R1 X02 · P05 今日概览"
    override val hintText = "今日概览与主行动入口；深层数据在 P12/P13/P14，设置在 P08/P10/P11。"

    private lateinit var metricRounds: TextView
    private lateinit var metricFocus: TextView
    private lateinit var metricRisk: TextView

    override fun onPageReady(view: android.view.View) {
        // ---- 今日概览卡：三个指标等宽排布 ----
        val summaryCard = addCard()
        val metricsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        summaryCard.addView(metricsRow)
        metricRounds = addMetric(metricsRow, getString(R.string.p05_metric_rounds))
        metricFocus = addMetric(metricsRow, getString(R.string.p05_metric_focus))
        metricRisk = addMetric(metricsRow, getString(R.string.p05_metric_risk))

        // ---- 主行动 ----
        addPrimaryButton(getString(R.string.p05_cta_start_focus)) { navigateTo(R.id.p06PomodoroPage) }
        val actionRow = addButtonRow()
        addRowButton(actionRow, getString(R.string.p05_cta_monitor)) { navigateTo(R.id.p07VisionMonitorPage) }
        addRowButton(actionRow, getString(R.string.p05_cta_calibration)) { navigateTo(R.id.p03CalibrationPage) }

        // ---- 快捷入口（两列网格） ----
        addSectionLabel(getString(R.string.p05_section_entries))
        entryRow(
            getString(R.string.p05_entry_positioning) to R.id.p02PositioningPage,
            getString(R.string.p05_entry_reminders) to R.id.p08ReminderCenterPage,
        )
        entryRow(
            getString(R.string.p05_entry_guard) to R.id.p10GuardInterceptPage,
            getString(R.string.p05_entry_persona) to R.id.p11PersonaThemePage,
        )
        entryRow(
            getString(R.string.p05_entry_trend) to R.id.p13HistoryTrendPage,
            getString(R.string.p05_entry_compare) to R.id.p14ComparisonPage,
        )
        entryRow(
            getString(R.string.p05_entry_guide) to R.id.p01GuidePage,
            getString(R.string.p05_entry_imu) to R.id.p15ImuBindingPage,
        )

        // ---- 状态与今日数据实时刷新 ----
        viewLifecycleOwner.lifecycleScope.launch {
            vm.pomodoro.collect { s ->
                binding.pageStatus.text = when (s.phase) {
                    Phase.IDLE -> getString(R.string.p05_status_idle)
                    Phase.FOCUS -> getString(R.string.p05_status_focus, s.roundIndex, s.focusRemainingMs / 1000)
                    Phase.FOCUS_PAUSED -> getString(R.string.p05_status_paused, s.roundIndex, s.focusElapsedMs / 1000)
                    Phase.BREAK -> getString(R.string.p05_status_break, s.breakRemainingMs / 1000)
                    Phase.FINISHED -> getString(R.string.p05_status_finished)
                }
                refreshTodaySummary()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { refreshTodaySummary() }
    }

    private suspend fun refreshTodaySummary() {
        val t = vm.todaySummary()
        metricRounds.text = "${t.rounds}"
        metricFocus.text = if (t.focusMs >= 60_000) "${t.focusMs / 60_000}" else "0"
        metricRisk.text = "${(t.avgRiskRate * 100).toInt()}%"
    }

    /** 一个指标列：大数字 + 小标签，等宽居中 */
    private fun addMetric(row: LinearLayout, label: String): TextView {
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(requireContext()).apply {
            textSize = 26f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_ink))
            text = "—"
        }
        val name = TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_text_hint))
            text = label
        }
        col.addView(value)
        col.addView(name)
        row.addView(col)
        return value
    }

    /** 两列入口行；right 为 null 时右侧留空 */
    private fun entryRow(left: Pair<String, Int>, right: Pair<String, Int>?) {
        val row = addButtonRow()
        addRowButton(row, left.first) { navigateTo(left.second) }
        if (right != null) {
            addRowButton(row, right.first) { navigateTo(right.second) }
        } else {
            val pad = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            row.addView(pad)
        }
    }
}
