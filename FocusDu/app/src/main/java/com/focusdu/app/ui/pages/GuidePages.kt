package com.focusdu.app.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.guard.GuardPermission
import com.focusdu.app.guard.GuardPermissionState
import com.focusdu.app.ui.common.BasePageFragment
import com.focusdu.app.vision.CalibrationIssue
import com.focusdu.app.vision.CalibrationState
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * P01–P04：引导与校准链。
 * 文案全部由 R2 提供（T1-01/T7-01），实现时把 hintText 换成真实引导内容并改布局。
 */

/** P01 首次引导与隐私说明 —— R2 文案（T7-01，验收 ≤3 屏）；R3 权限模块在此接入（T4-01） */
class P01GuidePage : BasePageFragment() {
    override val ownerTag: String
        get() = getString(R.string.p01_owner)
    override val hintText: String
        get() = getString(R.string.p01_hint)

    private val permissionButtons = mutableMapOf<GuardPermission, MaterialButton>()
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionUi() }

    override fun onPageReady(view: android.view.View) {
        GuardPermission.entries.forEach { permission ->
            val button = addButton("") { onPermissionClicked(permission) }
            permissionButtons[permission] = button
        }
        addButton(getString(R.string.p01_next)) { navigateTo(R.id.p02PositioningPage) }
        refreshPermissionUi()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
    }

    private fun onPermissionClicked(permission: GuardPermission) {
        val state = vm.permissionSnapshot()[permission]?.state ?: GuardPermissionState.DENIED
        if (permission == GuardPermission.CAMERA || permission == GuardPermission.NOTIFICATIONS) {
            if (state == GuardPermissionState.DENIED) {
                val runtimePermission = when (permission) {
                    GuardPermission.CAMERA -> Manifest.permission.CAMERA
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.POST_NOTIFICATIONS
                    } else {
                        null
                    }
                }
                if (runtimePermission != null) {
                    runtimePermissionLauncher.launch(runtimePermission)
                } else {
                    runCatching { startActivity(vm.permissionSettingsIntent(permission)) }
                }
            } else {
                runCatching { startActivity(vm.permissionSettingsIntent(permission)) }
            }
        } else {
            runCatching { startActivity(vm.permissionSettingsIntent(permission)) }
        }
    }

    private fun refreshPermissionUi() {
        if (view == null) return
        val snapshot = vm.permissionSnapshot()
        permissionButtons.forEach { (permission, button) ->
            val state = snapshot[permission]?.state ?: GuardPermissionState.DENIED
            button.text = getString(
                R.string.permission_button,
                permissionName(permission),
                permissionState(state),
            )
        }
        val missing = snapshot.count { it.value.state != GuardPermissionState.GRANTED }
        binding.pageStatus.text = getString(R.string.p01_permission_summary, missing)
    }

    private fun permissionName(permission: GuardPermission): String = getString(
        when (permission) {
            GuardPermission.CAMERA -> R.string.permission_camera
            GuardPermission.NOTIFICATIONS -> R.string.permission_notifications
            GuardPermission.USAGE_ACCESS -> R.string.permission_usage
            GuardPermission.NOTIFICATION_LISTENER -> R.string.permission_notification_listener
            GuardPermission.OVERLAY -> R.string.permission_overlay
        }
    )

    private fun permissionState(state: GuardPermissionState): String = getString(
        when (state) {
            GuardPermissionState.GRANTED -> R.string.permission_granted
            GuardPermissionState.DENIED -> R.string.permission_denied
            GuardPermissionState.REVOKED -> R.string.permission_revoked
        }
    )
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
    override val ownerTag: String
        get() = getString(R.string.p03_owner)
    override val hintText: String
        get() = getString(R.string.p03_hint)

    private lateinit var previewView: PreviewView
    private var nextButton: com.google.android.material.button.MaterialButton? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCalibration() else {
            binding.pageStatus.text = getString(R.string.p03_permission_denied)
        }
    }

    override fun onPageReady(view: android.view.View) {
        previewView = PreviewView(requireContext()).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360),
            )
        }
        binding.pageBody.addView(previewView, 0)

        addButton(getString(R.string.p03_start)) {
            if (hasCameraPermission()) {
                startCalibration()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        nextButton = addButton(getString(R.string.p03_next)) {
            navigateTo(R.id.p04BaselinePage)
        }.also { it.isEnabled = false }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.calibration.collect { state ->
                binding.pageStatus.text = when (state) {
                    CalibrationState.Idle -> getString(R.string.p03_idle)
                    is CalibrationState.Running -> runningText(state)
                    is CalibrationState.Completed -> {
                        nextButton?.isEnabled = true
                        getString(
                            R.string.p03_completed,
                            state.frameCount,
                            state.activeValidMs / 1000f,
                        )
                    }
                    is CalibrationState.Failed -> {
                        nextButton?.isEnabled = false
                        getString(R.string.p03_failed, issueText(state.reason))
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        vm.stopCalibration()
        super.onDestroyView()
    }

    private fun startCalibration() {
        nextButton?.isEnabled = false
        vm.startCalibration(viewLifecycleOwner, previewView)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    private fun runningText(state: CalibrationState.Running): String {
        val issue = state.issue?.let(::issueText)
        return if (issue == null) {
            getString(
                R.string.p03_running,
                state.frameCount,
                state.requiredFrames,
                state.activeValidMs / 1000f,
            )
        } else {
            getString(
                R.string.p03_running_issue,
                state.frameCount,
                state.requiredFrames,
                issue,
            )
        }
    }

    private fun issueText(issue: CalibrationIssue): String = getString(
        when (issue) {
            CalibrationIssue.OCCLUDED -> R.string.p03_issue_occluded
            CalibrationIssue.LOW_CONFIDENCE -> R.string.p03_issue_low_confidence
            CalibrationIssue.CAMERA_MOVING -> R.string.p03_issue_moving
            CalibrationIssue.STREAM_INTERRUPTED -> R.string.p03_issue_interrupted
            CalibrationIssue.INSUFFICIENT_FRAMES -> R.string.p03_issue_frames
            CalibrationIssue.CAMERA_UNAVAILABLE -> R.string.p03_issue_camera
        }
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
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
