package com.focusdu.app.ui.pages

import com.focusdu.app.R
import com.focusdu.app.ui.common.BasePageFragment

/**
 * P10–P11：专注守护与个性化。
 */

/** P10 限制应用拦截页（软守护） —— R3（T4-03 触发延迟<300ms / T4-05 紧急放行零误拦） */
class P10GuardInterceptPage : BasePageFragment() {
    override val ownerTag = "R3 拦截 T4-03/T4-05 · 软模式可主动退出"
    override val hintText = "TODO：进入限制应用时显示专注页 + 退出二次确认；紧急电话/联系人/系统安全功能始终放行；权限被拒自动降级为软守护。不提供不可解除的锁机。埋点打 GUARD_TRIGGER。"

    override fun onPageReady(view: android.view.View) {
        addButton("返回首页") { navigateTo(R.id.p05HomePage) }
    }
}

/** P11 提醒人格与主题 —— R2 文案（T5-01）；AI 只生成受约束参数、用户预览确认后应用 */
class P11PersonaThemePage : BasePageFragment() {
    override val ownerTag = "R2 人格文案 T5-01 · 可选增强：AI 主题参数"
    override val hintText = "TODO：温和/教练式/简洁严格三套人格选择，音色/语速/番茄钟主题调整；AI 生成主题参数必须经用户预览确认后才应用。"

    override fun onPageReady(view: android.view.View) {
        addButton("返回首页") { navigateTo(R.id.p05HomePage) }
    }
}
