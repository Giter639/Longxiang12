package com.focusdu.app.ui.common

import android.app.Application
import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.focusdu.app.AppContainer
import com.focusdu.app.FocusDuApp
import com.focusdu.app.R
import com.focusdu.app.data.db.ReportEntity
import com.focusdu.app.guard.GuardPermission
import com.focusdu.app.guard.GuardPermissionSnapshot
import com.focusdu.app.guard.LaunchableApp
import com.focusdu.app.imu.ImuEngineMode
import com.focusdu.app.imu.ble.BleImuEngine
import com.focusdu.app.pomodoro.PomodoroMode
import com.focusdu.app.pomodoro.PomodoroService
import com.focusdu.app.reminder.RiskType
import com.focusdu.app.reminder.ReminderMessageResolver
import com.focusdu.app.reminder.ReminderPersona
import com.focusdu.app.vision.CalibrationIssue
import com.focusdu.app.vision.CalibrationState
import com.focusdu.app.vision.PersonalBaseline
import com.focusdu.app.vision.TrainingScene
import com.focusdu.app.vision.VisionEngineMode
import com.focusdu.app.vision.algo.BaselineCalculator
import com.focusdu.app.vision.algo.CalibrationFrameFeatures
import com.focusdu.app.vision.toCalibrationFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * X02 统一状态容器：全部页面共用这一个 ViewModel（activityViewModels），
 * 页面不得自行创建引擎或直连数据库 —— 状态只有这一份，报告口径才不会分叉。
 */
class AppSessionViewModel(private val container: AppContainer) : ViewModel() {

    val pomodoro = container.pomodoroEngine.state
    val visionUi = container.debouncer.ui
    val imuUi = container.imuDebouncer.ui   // X22 IMU 状态流（P07 IMU 小窗/调试用，UI 接入在 X23/X24）
    val imuMode: ImuEngineMode = container.imuMode   // X23 引擎模式（P15 绑定页标注，不伪装真数据）
    val imuEngineStatus: StateFlow<BleImuEngine.Status>? =
        (container.imuEngine as? BleImuEngine)?.status
    val reminders = container.reminderEngine.decisions
    val cameraStatus = container.cameraStreamer.status
    val visionMode: VisionEngineMode = container.visionMode
    val calibration = container.calibrationSession.state
    val restrictedPackages = container.guardSettings.restrictedPackages
    val reminderPersona = container.reminderPreferences.persona
    val trainingScene = container.scenePreferences.scene

    // ---- 番茄（T3-01/T3-02） ----
    fun startPomodoro(mode: PomodoroMode) = viewModelScope.launch {
        container.reminderEngine.resetForNewRound()
        val todayStart = calendarDayStart()
        val round = container.pomodoroStore.recentSessions(50).count { it.startedAtWall >= todayStart } + 1
        container.pomodoroEngine.start(mode, round)
        ContextCompat.startForegroundService(
            container.appContext,
            Intent(container.appContext, PomodoroService::class.java),
        )
    }

    fun pause() = container.pomodoroEngine.pause()
    fun resume() = container.pomodoroEngine.resume()
    fun giveUp() = viewModelScope.launch { container.pomodoroEngine.giveUp() }

    // ---- 提醒（T3-03） ----
    fun snooze(type: RiskType) = container.reminderEngine.snooze(type)
    fun setSilent(enabled: Boolean) { container.reminderEngine.silentMode = enabled }
    fun setDefer(enabled: Boolean) { container.reminderEngine.deferMode = enabled }
    fun setReminderPersona(persona: ReminderPersona) =
        container.reminderPreferences.setPersona(persona)

    fun reminderText(event: com.focusdu.app.reminder.RiskEvent): String =
        container.appContext.getString(
            ReminderMessageResolver.resourceId(event, reminderPersona.value)
        )
    fun drainDeferredForRest() = container.reminderEngine.drainDeferredForRest()

    // ---- IMU 设备绑定（X23） ----
    fun imuRequiredPermissions(): List<String> =
        (container.imuEngine as? BleImuEngine)?.requiredPermissions() ?: emptyList()

    fun imuRetryConnection() = (container.imuEngine as? BleImuEngine)?.retryConnection()

    fun imuDisconnect() = (container.imuEngine as? BleImuEngine)?.disconnect()

    fun modeLabelForImu(): String = container.appContext.getString(
        when (imuMode) {
            ImuEngineMode.BLE -> R.string.p15_mode_ble
            ImuEngineMode.MOCK_FALLBACK -> R.string.p15_mode_mock_fallback
            ImuEngineMode.MOCK -> R.string.p15_mode_mock
        },
    )

    // ---- 休息恢复校验（T3-04） ----
    fun restCheck() = container.restValidator.lastCheck()
    fun currentAwayMs() = container.restValidator.currentAwayMs()

    // ---- 相机校准（X11 / T1-02 / T1-03）与个人基线（T1-04） ----
    private var calibrationJob: Job? = null
    private var calibrationFrames = mutableListOf<CalibrationFrameFeatures>()

    private val _baselineOutcome = MutableStateFlow<BaselineOutcome>(BaselineOutcome.None)
    val baselineOutcome: StateFlow<BaselineOutcome> = _baselineOutcome.asStateFlow()

    init {
        // 每计入一个有效采集帧，就用该帧时刻的分析样本作为基线特征：基线与校准的帧数口径一致，
        // 校准一旦完成，基线所需的 150 帧就一定齐（相机帧率通常高于算法吞吐，只按分析样本数会不够）。
        container.calibrationSession.onValidFrame = { sample ->
            sample.toCalibrationFeatures()?.let { calibrationFrames.add(it) }
        }
    }

    fun startCalibration(owner: LifecycleOwner, previewView: PreviewView) {
        calibrationJob?.cancel()
        calibrationFrames = mutableListOf()
        container.calibrationSession.start()
        calibrationJob = viewModelScope.launch {
            try {
                container.sessionCamera.startCalibration(owner, previewView, CALIBRATION_TARGET_FPS)
                while (isActive && container.calibrationSession.state.value is CalibrationState.Running) {
                    container.calibrationSession.tick()
                    delay(CALIBRATION_TICK_MS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled // 离开页面或重新开始：状态由 stopCalibration 复位，不算「相机不可用」
            } catch (_: Exception) {
                container.calibrationSession.fail(CalibrationIssue.CAMERA_UNAVAILABLE)
            }
            finalizeBaseline()
        }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        container.calibrationSession.cancel()
        container.sessionCamera.endCalibration()   // 番茄仍在进行则自动恢复无预览取流
    }

    /** 校准循环自然结束后调用。保存放在独立协程里：校准一完成就离开页面也不会丢基线 */
    private fun finalizeBaseline() {
        if (container.calibrationSession.state.value !is CalibrationState.Completed) return
        val frames = calibrationFrames.toList()
        viewModelScope.launch {
            val baseline = BaselineCalculator.compute(
                frames,
                trainingScene.value.storageValue,
                System.currentTimeMillis(),
            )
            if (baseline == null) {
                // 正常不会发生（有效帧已满 150）；万一发生要能解释，且不写残缺基线
                _baselineOutcome.value = BaselineOutcome.InsufficientFrames(
                    frames.size, BaselineCalculator.MIN_VALID_FRAMES,
                )
                return@launch
            }
            container.baselineStore.save(baseline)
            container.visionEngine.setBaseline(baseline)
            _baselineOutcome.value = BaselineOutcome.Ready(baseline)
        }
    }

    // ---- 专注守护（T4-01/T4-03/T4-05） ----
    fun permissionSnapshot(): Map<GuardPermission, GuardPermissionSnapshot> =
        container.guardPermissions.snapshot()

    fun permissionSettingsIntent(permission: GuardPermission) =
        container.guardPermissions.settingsIntent(permission)

    fun setRestricted(packageName: String, restricted: Boolean) =
        container.guardSettings.setRestricted(packageName, restricted)

    suspend fun loadLaunchableApps(): List<LaunchableApp> = container.launchableApps.load()

    data class NotificationSummaryItem(
        val appLabel: String,
        val count: Int,
    )

    fun notificationSummary(): List<NotificationSummaryItem> {
        val packageManager = container.appContext.packageManager
        return container.notificationMemory.snapshot().map { item ->
            val label = runCatching {
                val info = packageManager.getApplicationInfo(item.packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(item.packageName)
            NotificationSummaryItem(label, item.count)
        }
    }

    fun setTrainingScene(scene: TrainingScene) = container.scenePreferences.setScene(scene)

    fun sceneLabel(storageValue: String): String =
        container.appContext.getString(TrainingScene.fromStorage(storageValue).labelRes)

    // ---- 报告（T6-01/02/03） ----
    suspend fun generateReport(): ReportEntity? {
        val sessionId = container.pomodoroStore.recentSessions(1).firstOrNull()?.sessionId ?: return null
        return container.reportGenerator.generate(sessionId)
    }

    suspend fun latestReport(): ReportEntity? = container.reportGenerator.latest()
    suspend fun recentReports(n: Int): List<ReportEntity> = container.reportGenerator.recent(n)
    suspend fun reportSections(sessionId: Long) = container.reportGenerator.buildSections(sessionId)

    /** P05 今日概览：今天的轮数 / 总专注 / 平均风险率 */
    data class TodaySummary(val rounds: Int, val focusMs: Long, val avgRiskRate: Double)

    suspend fun todaySummary(): TodaySummary {
        val today = container.reportGenerator.recent(30).filter { it.generatedAtWall >= calendarDayStart() }
        val risk = today.map { it.riskRate }.filter { it > 0 }
        return TodaySummary(
            rounds = today.size,
            focusMs = today.sumOf { it.focusMs },
            avgRiskRate = if (risk.isNotEmpty()) risk.average() else 0.0,
        )
    }

    // ---- 基线（T1-04） ----
    suspend fun currentBaseline(): PersonalBaseline? = container.baselineStore.current()

    /** 演示用：写入一组固定基线（仅无相机联调）。真机使用请走 P03 校准，真实基线在采集完成时自动生成 */
    suspend fun saveDemoBaseline() {
        val baseline = PersonalBaseline(
            faceScale = 0.30f, pitchDeg = 8f, yawDeg = 3f, forwardDeg = 4f,
            shoulderTiltDeg = 2f, lumFace = 128f, lumBackground = 110f,
            confidence = 0.95f, sampleCount = 160,
            createdAtWall = System.currentTimeMillis(),
            sceneTag = trainingScene.value.storageValue,
        )
        container.baselineStore.save(baseline)
        container.visionEngine.setBaseline(baseline)
        _baselineOutcome.value = BaselineOutcome.Ready(baseline)
    }

    private fun calendarDayStart(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppSessionViewModel((app as FocusDuApp).container) as T
    }

    private companion object {
        /** 校准取流帧率。150 帧要在 10 秒有效时长内采满：15fps 没有任何余量（相机预热就会超时），30fps 对应 5 秒档 */
        const val CALIBRATION_TARGET_FPS = 30
        const val CALIBRATION_TICK_MS = 250L

    }
}

/** 校准对个人基线的结果，P04 据此展示 */
sealed interface BaselineOutcome {
    data object None : BaselineOutcome
    data class Ready(val baseline: PersonalBaseline) : BaselineOutcome
    data class InsufficientFrames(val validFrames: Int, val required: Int) : BaselineOutcome
}
