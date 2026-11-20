package com.focusdu.app.ui.pages

import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.ui.common.BasePageFragment
import kotlinx.coroutines.launch

/**
 * P01–P04：引导与校准链。
 * 文案全部由 R2 提供（T1-01/T7-01），实现时把 hintText 换成真实引导内容并改布局。
 */

/** P01 首次引导与隐私说明 —— R2 文案（T7-01，验收 ≤3 屏）；R3 权限模块在此接入（T4-01） */
class P01GuidePage : BasePageFragment() {
    override val ownerTag = "R2 文案 T7-01 · R3 权限 T4-01"
    override val hintText = "TODO：三步引导 + 隐私要点（不上传/可主动退出）；权限逐项授权、三态可视（授予/拒绝/撤回）。POST_NOTIFICATIONS、相机、使用情况访问权限的请求代码放这里。"

    override fun onPageReady(view: android.view.View) {
        addButton("下一步：固定机位引导") { navigateTo(R.id.p02PositioningPage) }
    }
}

/** P02 固定机位引导 —— R2 示意图（T1-01，验收 3 人实测 2 人一次摆对）；R3 支架摆位图（X12） */
class P02PositioningPage : BasePageFragment() {
    override val ownerTag = "R2 文案 T1-01 · R3 摆位 X12"
    override val hintText = "TODO：摆放距离/角度/支架位置示意图，场景选择（书桌/床头/自习室）。"

    override fun onPageReady(view: android.view.View) {
        addButton("下一步：坐姿校准") { navigateTo(R.id.p03CalibrationPage) }
    }
}

/** P03 校准采集（5–10s） —— R3 实现（T1-02 采集 / T1-03 质量校验，验收采集成功率 ≥95%） */
class P03CalibrationPage : BasePageFragment() {
    override val ownerTag = "R3 采集 T1-02/T1-03"
    override val hintText = "TODO：稳定帧判定 → 采样 ≥150 帧 → 遮挡/低置信/摄像头移动三类拦截提示，中断可续。采集帧喂给 R4 的基线算法（T1-04）。"

    override fun onPageReady(view: android.view.View) {
        addButton("下一步：校准完成") { navigateTo(R.id.p04BaselinePage) }
    }
}

/** P04 校准完成/基线确认 —— R4 基线生成（T1-04，验收字段完整率 100%、重建偏差 <5%） */
class P04BaselinePage : BasePageFragment() {
    override val ownerTag = "R4 基线 T1-04 · 存储已就绪"
    override val hintText = "TODO：把校准帧算成个人基线并 save()；本页展示基线字段供用户确认。下方按钮写入演示基线，用于无相机时联调。"

    override fun onPageReady(view: android.view.View) {
        addButton("写入演示基线（联调用）") {
            viewLifecycleOwner.lifecycleScope.launch {
                vm.saveDemoBaseline()
                binding.pageStatus.text = "已写入演示基线：脸尺度0.30 / 俯角8° / 偏航3°（置信0.95，160帧）"
            }
        }
        addButton("去开一轮番茄") { navigateTo(R.id.p06PomodoroPage) }
    }
}
