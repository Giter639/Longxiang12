package com.focusdu.app.ui.pages

import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.databinding.FragmentPageBinding
import com.focusdu.app.pomodoro.Phase
import com.focusdu.app.ui.common.BasePageFragment
import kotlinx.coroutines.launch

/**
 * P05 今日概览（首页）：骨架期同时充当「页面索引」，让三人各自独立测试自己的页面。
 * 复赛版式可改成卡片式概览（今日累计时长/风险率摘要），数据源用 recentReports。
 */
class P05HomePage : BasePageFragment() {
    override val ownerTag = "R1 骨架 X02 · 页面索引（开发期导航）"
    override val hintText = "骨架工程已可运行：番茄/提醒/报告全链路接通（Mock 引擎驱动）。点下方按钮直达各页面；页内灰字是接手 TODO。"

    override fun onPageReady(view: android.view.View) {
        val entries = listOf(
            "P01 欢迎" to R.id.p01GuidePage,
            "P02 机位引导" to R.id.p02PositioningPage,
            "P03 坐姿校准" to R.id.p03CalibrationPage,
            "P04 校准完成" to R.id.p04BaselinePage,
            "P06 番茄钟" to R.id.p06PomodoroPage,
            "P07 实时状态" to R.id.p07VisionMonitorPage,
            "P08 提醒中心" to R.id.p08ReminderCenterPage,
            "P09 休息页" to R.id.p09RestPage,
            "P10 专注守护" to R.id.p10GuardInterceptPage,
            "P11 人格与主题" to R.id.p11PersonaThemePage,
            "P12 本轮报告" to R.id.p12RoundReportPage,
            "P13 历史趋势" to R.id.p13HistoryTrendPage,
            "P14 前后对比" to R.id.p14ComparisonPage,
        )
        entries.forEach { (label, id) -> addButton(label) { navigateTo(id) } }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.pomodoro.collect { s ->
                binding.pageStatus.text = when (s.phase) {
                    Phase.IDLE -> "当前无进行中的番茄轮"
                    Phase.FOCUS -> "第 ${s.roundIndex} 轮专注中 · 剩余 ${s.focusRemainingMs / 1000} 秒"
                    Phase.FOCUS_PAUSED -> "第 ${s.roundIndex} 轮已暂停 · 已专注 ${s.focusElapsedMs / 1000} 秒"
                    Phase.BREAK -> "休息中 · 剩余 ${s.breakRemainingMs / 1000} 秒"
                    Phase.FINISHED -> "本轮完成 · 报告见 P12"
                }
            }
        }
    }
}
