package com.focusdu.app.ui.pages

import android.widget.CheckBox
import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.guard.GuardPermission
import com.focusdu.app.guard.GuardPermissionState
import com.focusdu.app.guard.LaunchableApp
import com.focusdu.app.ui.common.BasePageFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P10–P11：专注守护与个性化。
 */

/** P10 限制应用拦截页（软守护） —— R3（T4-03 触发延迟<300ms / T4-05 紧急放行零误拦） */
class P10GuardInterceptPage : BasePageFragment() {
    override val ownerTag: String
        get() = getString(R.string.p10_owner)
    override val hintText: String
        get() = getString(R.string.p10_hint)

    private val checkboxes = mutableMapOf<String, CheckBox>()
    private var appsLoaded = false
    private var permissionSummary = ""

    override fun onPageReady(view: android.view.View) {
        addButton(getString(R.string.p10_open_overlay_permission)) {
            runCatching {
                startActivity(
                    vm.permissionSettingsIntent(GuardPermission.OVERLAY)
                )
            }
        }
        addButton(getString(R.string.p10_open_usage_permission)) {
            runCatching {
                startActivity(
                    vm.permissionSettingsIntent(GuardPermission.USAGE_ACCESS)
                )
            }
        }
        addButton(getString(R.string.p10_refresh_permissions)) {
            refreshPermissionStatus()
        }
        addButton(getString(R.string.p10_back_home)) { navigateTo(R.id.p05HomePage) }

        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { vm.loadLaunchableApps() }
            renderApps(apps)
            appsLoaded = true
            updateSummary(vm.restrictedPackages.value.size)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.restrictedPackages.collect { selected ->
                checkboxes.forEach { (packageName, checkBox) ->
                    checkBox.setOnCheckedChangeListener(null)
                    checkBox.isChecked = packageName in selected
                    checkBox.setOnCheckedChangeListener { _, checked ->
                        vm.setRestricted(packageName, checked)
                    }
                }
                updateSummary(selected.size)
            }
        }
        refreshPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun renderApps(apps: List<LaunchableApp>) {
        apps.forEach { app ->
            val checkBox = CheckBox(requireContext()).apply {
                text = app.label
                isChecked = app.packageName in vm.restrictedPackages.value
                setOnCheckedChangeListener { _, checked ->
                    vm.setRestricted(app.packageName, checked)
                }
            }
            checkboxes[app.packageName] = checkBox
            binding.pageBody.addView(checkBox)
        }
        if (apps.isEmpty()) {
            binding.pageStatus.text = getString(R.string.p10_no_apps)
        }
    }

    private fun refreshPermissionStatus() {
        if (view == null) return
        val snapshot = vm.permissionSnapshot()
        val usage = snapshot[GuardPermission.USAGE_ACCESS]?.state
        val overlay = snapshot[GuardPermission.OVERLAY]?.state
        val ready = usage == GuardPermissionState.GRANTED &&
            overlay == GuardPermissionState.GRANTED
        permissionSummary = getString(
            R.string.p10_permission_status,
            stateText(usage),
            stateText(overlay),
            getString(if (ready) R.string.p10_ready else R.string.p10_not_ready),
        )
        updateSummary(vm.restrictedPackages.value.size)
    }

    private fun updateSummary(count: Int) {
        binding.pageStatus.text = if (appsLoaded) {
            getString(R.string.p10_permission_and_selection, permissionSummary, count)
        } else {
            permissionSummary
        }
    }

    private fun stateText(state: GuardPermissionState?): String = getString(
        when (state) {
            GuardPermissionState.GRANTED -> R.string.permission_granted
            GuardPermissionState.REVOKED -> R.string.permission_revoked
            else -> R.string.permission_denied
        }
    )
}

/** P11 提醒人格与主题 —— R2 文案（T5-01）；AI 只生成受约束参数、用户预览确认后应用 */
class P11PersonaThemePage : BasePageFragment() {
    override val ownerTag = "R2 人格文案 T5-01 · 可选增强：AI 主题参数"
    override val hintText = "TODO：温和/教练式/简洁严格三套人格选择，音色/语速/番茄钟主题调整；AI 生成主题参数必须经用户预览确认后才应用。"

    override fun onPageReady(view: android.view.View) {
        addButton("返回首页") { navigateTo(R.id.p05HomePage) }
    }
}
