package com.focusdu.app.ui.pages

import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.databinding.FragmentPageBinding
import com.focusdu.app.report.HistoryComparator
import com.focusdu.app.ui.common.BasePageFragment
import com.focusdu.app.ui.common.TrendLineChart
import kotlinx.coroutines.launch

/**
 * P12–P14：报告与对比。数据全部来自 Room 埋点重算（口径见 MetricDefs，D5 前与 R2 定稿）。
 */

/** P12 本轮报告 —— R1（T6-01 结束 3s 内出报告 / T6-02 指标引擎）：按 docx 七区块卡片化 */
class P12RoundReportPage : BasePageFragment() {
    override val ownerTag = "R1 报告 T6-01/T6-02 · 口径 DRAFT_v0.1 待 R2 定稿"
    override val hintText = "按 docx 报告内容表分七个区块展示（摘要/风险构成/干预效果/休息恢复/历史对比/可信度/建议），数据全部来自埋点重算。"

    override fun onPageReady(view: android.view.View) {
        addButton("生成本轮报告") {
            viewLifecycleOwner.lifecycleScope.launch {
                val report = vm.generateReport()
                binding.pageStatus.text = report?.summaryText
                    ?: "暂无可生成报告的会话 —— 先在 P06 开始并完成（或放弃）一轮番茄"
                report?.let { renderSections(vm.reportSections(it.sessionId)) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.latestReport()?.let { r ->
                binding.pageStatus.text = r.summaryText
                renderSections(vm.reportSections(r.sessionId))
            }
        }
    }

    /** 动态渲染区块卡片（重复渲染前先清掉旧的，保留按钮）；UI 改版：白卡+粗体标题+灰字条目 */
    private fun renderSections(sections: List<com.focusdu.app.report.ReportGenerator.ReportSection>) {
        val ctx = requireContext()
        for (i in (binding.pageBody.childCount - 1) downTo 0) {
            if (binding.pageBody.getChildAt(i)?.tag == "section_card") {
                binding.pageBody.removeViewAt(i)
            }
        }
        sections.forEach { sec ->
            val card = LinearLayout(ctx).apply {
                tag = "section_card"
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_status_card)
                setPadding(44, 34, 44, 40)   // px：≈16dp 卡内边距（2x 密度）
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 32 }
            }
            val title = TextView(ctx).apply {
                text = sec.title
                textSize = 14.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.focusdu_ink))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val body = TextView(ctx).apply {
                text = sec.lines.joinToString("\n") { "· $it" }
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.focusdu_ink_secondary))
                setLineSpacing(6f, 1f)
            }
            card.addView(title)
            card.addView(body)
            binding.pageBody.addView(card)
        }
    }
}

/** P13 历史趋势 —— R1 存储（T7-03 离线可用）：近 7 次折线（有效学习时长 / 风险率），零依赖自绘 */
class P13HistoryTrendPage : BasePageFragment() {
    override val ownerTag = "R1 存储 T7-03 · 报告全本地不上传"
    override val hintText = "绿线=有效学习时长（分），橙线=高风险占比（%）。数据来自本地报告历史；样本不足 2 轮时显示提示。"

    override fun onPageReady(view: android.view.View) {
        val chart = TrendLineChart(requireContext())
        chart.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 620
        )
        binding.pageBody.addView(chart, 0)

        fun refresh() {
            viewLifecycleOwner.lifecycleScope.launch {
                val reports = vm.recentReports(7).sortedBy { it.generatedAtWall }
                if (reports.size < 2) {
                    chart.setData(emptyList())
                    binding.pageStatus.text = "完成至少 2 轮番茄后显示趋势（当前 ${reports.size} 轮）"
                } else {
                    chart.setData(
                        listOf(
                            TrendLineChart.Series(
                                "有效学习(分)",
                                reports.map { it.effectiveMs / 60000f },
                                { "${it.toInt()}分" },
                            ),
                            TrendLineChart.Series(
                                "高风险(%)",
                                reports.map { (it.riskRate * 100).toFloat() },
                                { "${it.toInt()}%" },
                            ),
                        )
                    )
                    binding.pageStatus.text = "近 ${reports.size} 轮趋势（时间从左到右）"
                }
            }
        }
        addButton("刷新趋势") { refresh() }
        refresh()
    }
}

/** P14 前后对比 —— R1（T6-03，验收：样本 <3 次提示数据不足，不硬出结论） */
class P14ComparisonPage : BasePageFragment() {
    override val ownerTag = "R1 对比 T6-03 · 已单测验证降级规则"
    override val hintText = "TODO：本轮 vs 上一轮 vs 近 7 次中位数的表格 + 箭头方向；降级提示样式。"

    override fun onPageReady(view: android.view.View) {
        addButton("生成对比") {
            viewLifecycleOwner.lifecycleScope.launch {
                val current = vm.latestReport()
                if (current == null) {
                    binding.pageStatus.text = "暂无本轮报告 —— 先完成一轮番茄并在 P12 生成报告"
                    return@launch
                }
                val history = vm.recentReports(10).filter { it.sessionId != current.sessionId }
                val result = HistoryComparator.compare(current, history)
                binding.pageStatus.text = if (!result.ready) {
                    "数据不足：${result.reason}"
                } else {
                    result.reason + "\n" + result.rows.joinToString("\n") { row ->
                        val arrow = when (HistoryComparator.direction(row)) {
                            1 -> "↑变差"
                            -1 -> "↓变好"
                            else -> "→持平"
                        }
                        "${row.name}: 本轮 ${fmt1(row.current)} / 上一轮 ${row.previous?.let { fmt1(it) } ?: "—"} / 近7中位 ${row.medianLast7?.let { fmt1(it) } ?: "—"}  $arrow"
                    }
                }
            }
        }
    }

    private fun fmt1(v: Double): String = if (v == v.toLong().toDouble()) "${v.toLong()}" else "%.1f".format(v)
}
