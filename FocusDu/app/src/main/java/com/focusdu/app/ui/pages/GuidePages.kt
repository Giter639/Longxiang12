package com.focusdu.app.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.focusdu.app.R
import com.focusdu.app.guard.GuardPermission
import com.focusdu.app.guard.GuardPermissionState
import com.focusdu.app.ui.common.AppSessionViewModel
import com.focusdu.app.ui.common.BasePageFragment
import com.focusdu.app.ui.common.BaselineOutcome
import com.focusdu.app.ui.common.PlacementDiagramView
import com.focusdu.app.vision.CalibrationIssue
import com.focusdu.app.vision.CalibrationState
import com.focusdu.app.vision.TrainingScene
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
    private val expandedPrivacy = mutableSetOf<Int>()
    private var currentScreen = 0

    private lateinit var screenTitle: TextView
    private lateinit var screenBody: TextView
    private lateinit var screenNote: TextView
    private lateinit var progressText: TextView
    private lateinit var privacyContainer: LinearLayout
    private lateinit var permissionContainer: LinearLayout
    private lateinit var privacyDetails: List<TextView>
    private lateinit var previousButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentScreen = savedInstanceState?.getInt(KEY_SCREEN, 0) ?: 0
        savedInstanceState?.getBooleanArray(KEY_PRIVACY)?.forEachIndexed { index, expanded ->
            if (expanded) expandedPrivacy += index
        }
    }

    override fun onPageReady(view: android.view.View) {
        binding.pageHint.visibility = android.view.View.GONE

        progressText = TextView(requireContext()).apply {
            gravity = Gravity.END
            textSize = 13f
        }
        screenTitle = TextView(requireContext()).apply {
            textSize = 24f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_ink))
            setPadding(0, dp(12), 0, dp(8))
        }
        screenBody = TextView(requireContext()).apply {
            textSize = 16f
            setLineSpacing(0f, 1.25f)
        }
        screenNote = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_ink_secondary))
            setPadding(0, dp(12), 0, 0)
        }
        privacyContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        privacyDetails = buildPrivacyItems()
        permissionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, 0)
        }
        buildPermissionButtons()

        val navigation = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        previousButton = pageButton(getString(R.string.onboarding_previous)) {
            if (currentScreen > 0) {
                currentScreen--
                renderScreen()
            }
        }
        nextButton = pageButton("") {
            if (currentScreen < LAST_SCREEN) {
                currentScreen++
                renderScreen()
            } else {
                navigateTo(R.id.p02PositioningPage)
            }
        }.apply {
            backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.focusdu_primary)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_on_primary))
        }
        navigation.addView(previousButton, weightedButtonParams())
        navigation.addView(nextButton, weightedButtonParams(start = dp(12)))

        binding.pageBody.addView(progressText)
        binding.pageBody.addView(screenTitle)
        binding.pageBody.addView(screenBody)
        binding.pageBody.addView(screenNote)
        binding.pageBody.addView(privacyContainer)
        binding.pageBody.addView(permissionContainer)
        binding.pageBody.addView(navigation)
        renderScreen()
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
        binding.pageStatus.visibility =
            if (currentScreen == LAST_SCREEN) android.view.View.VISIBLE
            else android.view.View.GONE
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCREEN, currentScreen)
        outState.putBooleanArray(
            KEY_PRIVACY,
            BooleanArray(PRIVACY_ITEMS.size) { it in expandedPrivacy },
        )
    }

    private fun buildPermissionButtons() {
        GuardPermission.entries.forEach { permission ->
            val button = pageButton("") { onPermissionClicked(permission) }
            permissionButtons[permission] = button
            permissionContainer.addView(button)
        }
        permissionContainer.addView(
            pageButton(getString(R.string.onboarding_permissions_later)) {
                navigateTo(R.id.p02PositioningPage)
            }
        )
    }

    private fun buildPrivacyItems(): List<TextView> {
        val details = mutableListOf<TextView>()
        PRIVACY_ITEMS.forEachIndexed { index, (titleRes, detailRes) ->
            val header = TextView(requireContext()).apply {
                text = getString(titleRes)
                textSize = 16f
                setPadding(0, dp(12), 0, dp(8))
                isClickable = true
                setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_primary))
                setOnClickListener {
                    if (!expandedPrivacy.add(index)) expandedPrivacy.remove(index)
                    details[index].visibility =
                        if (index in expandedPrivacy) android.view.View.VISIBLE
                        else android.view.View.GONE
                }
            }
            val detail = TextView(requireContext()).apply {
                text = getString(detailRes)
                textSize = 14f
                setLineSpacing(0f, 1.2f)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_ink_secondary))
                visibility =
                    if (index in expandedPrivacy) android.view.View.VISIBLE
                    else android.view.View.GONE
            }
            privacyContainer.addView(header)
            privacyContainer.addView(detail)
            details += detail
        }
        return details
    }

    private fun renderScreen() {
        val (titleRes, bodyRes, noteRes) = when (currentScreen) {
            0 -> Triple(
                R.string.onboarding_value_title,
                R.string.onboarding_value_body,
                R.string.onboarding_value_note,
            )
            1 -> Triple(
                R.string.onboarding_flow_title,
                R.string.onboarding_flow_body,
                R.string.onboarding_flow_note,
            )
            else -> Triple(
                R.string.onboarding_privacy_title,
                R.string.onboarding_privacy_body,
                0,
            )
        }
        progressText.text = "${currentScreen + 1} / ${LAST_SCREEN + 1}"
        screenTitle.setText(titleRes)
        screenBody.setText(bodyRes)
        screenNote.visibility =
            if (noteRes == 0) android.view.View.GONE else android.view.View.VISIBLE
        if (noteRes != 0) screenNote.setText(noteRes)
        privacyContainer.visibility =
            if (currentScreen == LAST_SCREEN) android.view.View.VISIBLE
            else android.view.View.GONE
        permissionContainer.visibility =
            if (currentScreen == LAST_SCREEN) android.view.View.VISIBLE
            else android.view.View.GONE
        binding.pageStatus.visibility =
            if (currentScreen == LAST_SCREEN) android.view.View.VISIBLE
            else android.view.View.GONE
        previousButton.visibility =
            if (currentScreen == 0) android.view.View.INVISIBLE else android.view.View.VISIBLE
        nextButton.setText(
            if (currentScreen == LAST_SCREEN) R.string.onboarding_start
            else R.string.onboarding_next
        )
    }

    private fun pageButton(label: String = "", onClick: () -> Unit): MaterialButton =
        MaterialButton(requireContext()).apply {
            text = label
            isAllCaps = false
            cornerRadius = resources.getDimensionPixelSize(R.dimen.button_radius)
            backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.focusdu_primary_tint)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_primary_dark))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }

    private fun weightedButtonParams(start: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = start
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LAST_SCREEN = 2
        const val KEY_SCREEN = "p01_screen"
        const val KEY_PRIVACY = "p01_privacy"
        val PRIVACY_ITEMS = listOf(
            R.string.privacy_camera_title to R.string.privacy_camera_detail,
            R.string.privacy_usage_title to R.string.privacy_usage_detail,
            R.string.privacy_notification_title to R.string.privacy_notification_detail,
            R.string.privacy_overlay_title to R.string.privacy_overlay_detail,
            R.string.privacy_data_title to R.string.privacy_data_detail,
        )
    }
}

/** P02 固定机位引导 —— R2 示意图（T1-01，验收 3 人实测 2 人一次摆对）；R3 支架摆位图（X12） */
class P02PositioningPage : BasePageFragment() {
    override val ownerTag: String
        get() = getString(R.string.p02_owner)
    override val hintText: String
        get() = getString(R.string.p02_hint)

    private val sceneButtons = mutableMapOf<TrainingScene, MaterialButton>()

    override fun onPageReady(view: android.view.View) {
        binding.pageBody.addView(TextView(requireContext()).apply {
            setText(R.string.p02_intro)
            textSize = 22f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_ink))
        })
        binding.pageBody.addView(PlacementDiagramView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(240),
            ).apply { topMargin = dp(16) }
        })

        addSectionLabel(getString(R.string.p02_scene_label))

        val sceneRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        TrainingScene.entries.forEach { scene ->
            val button = MaterialButton(requireContext()).apply {
                text = getString(scene.labelRes)
                isAllCaps = false
                cornerRadius = resources.getDimensionPixelSize(R.dimen.button_radius)
                backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.focusdu_primary_tint)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_primary_dark))
                setOnClickListener { vm.setTrainingScene(scene) }
            }
            sceneButtons[scene] = button
            sceneRow.addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                },
            )
        }
        binding.pageBody.addView(sceneRow)

        addSectionLabel(getString(R.string.p02_scene_label_steps))
        val stepsCard = addCard()
        stepsCard.addView(TextView(requireContext()).apply {
            text = listOf(
                R.string.p02_step_distance,
                R.string.p02_step_height,
                R.string.p02_step_angle,
                R.string.p02_step_frame,
                R.string.p02_step_light,
                R.string.p02_step_stable,
            ).joinToString("\n") { "• ${getString(it)}" }
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_ink_secondary))
        })

        val issueCard = addCard(getString(R.string.p02_common_issue_title))
        issueCard.addView(TextView(requireContext()).apply {
            text = listOf(
                R.string.p02_issue_forehead,
                R.string.p02_issue_half_face,
                R.string.p02_issue_dark_face,
                R.string.p02_issue_shake,
            ).joinToString("\n") { "• ${getString(it)}" }
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_ink_secondary))
        })

        addPrimaryButton(getString(R.string.p02_ready)) {
            navigateTo(R.id.p03CalibrationPage)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.trainingScene.collect { selected ->
                sceneButtons.forEach { (scene, button) ->
                    if (scene == selected) {
                        button.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.focusdu_primary)
                        button.setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_on_primary))
                    } else {
                        button.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.focusdu_primary_tint)
                        button.setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_primary_dark))
                    }
                }
                binding.pageStatus.text = getString(selected.hintRes)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
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
        val previewCard = addCard()
        previewCard.addView(previewView)
        previewCard.clipToOutline = true
        binding.pageBody.removeView(previewCard)     // addCard 默认挂到末尾，预览卡要置顶：先摘再插回首位
        binding.pageBody.addView(previewCard, 0)

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

/** P04 校准完成/基线确认 —— R4 基线生成（T1-04，验收字段完整率 100%、重建偏差 <5%）；校准完成即自动生成并生效 */
class P04BaselinePage : BasePageFragment() {
    override val ownerTag: String
        get() = getString(R.string.p04_owner)
    override val hintText: String
        get() = getString(R.string.p04_hint)

    override fun onPageReady(view: android.view.View) {
        addButton(getString(R.string.p04_demo_baseline)) {
            viewLifecycleOwner.lifecycleScope.launch {
                vm.saveDemoBaseline()
                binding.pageStatus.text = getString(R.string.p04_demo_written)
            }
        }
        addButton(getString(R.string.p04_go_focus)) { navigateTo(R.id.p06PomodoroPage) }

        viewLifecycleOwner.lifecycleScope.launch {
            binding.pageStatus.text = statusText()
        }
    }

    private suspend fun statusText(): String {
        vm.currentBaseline()?.let {
            return getString(
                R.string.p04_ready,
                it.faceScale,
                it.pitchDeg,
                it.yawDeg,
                it.sampleCount,
                vm.sceneLabel(it.sceneTag),
            )
        }
        val outcome = vm.baselineOutcome.value
        return if (outcome is BaselineOutcome.InsufficientFrames) {
            getString(R.string.p04_insufficient, outcome.validFrames, outcome.required)
        } else {
            getString(R.string.p04_none)
        }
    }
}
