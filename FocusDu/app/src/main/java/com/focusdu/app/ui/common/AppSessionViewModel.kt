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
import com.focusdu.app.data.db.ReportEntity
import com.focusdu.app.guard.GuardPermission
import com.focusdu.app.guard.GuardPermissionSnapshot
import com.focusdu.app.guard.LaunchableApp
import com.focusdu.app.pomodoro.PomodoroMode
import com.focusdu.app.pomodoro.PomodoroService
import com.focusdu.app.reminder.RiskType
import com.focusdu.app.vision.CalibrationIssue
import com.focusdu.app.vision.CalibrationState
import com.focusdu.app.vision.PersonalBaseline
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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
    val reminders = container.reminderEngine.decisions
    val cameraStatus = container.cameraStreamer.status
    val calibration = container.calibrationSession.state
    val restrictedPackages = container.guardSettings.restrictedPackages

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
    fun drainDeferredForRest() = container.reminderEngine.drainDeferredForRest()

    // ---- 休息恢复校验（T3-04） ----
    fun restCheck() = container.restValidator.lastCheck()
    fun currentAwayMs() = container.restValidator.currentAwayMs()

    // ---- 相机校准（X11 / T1-02 / T1-03） ----
    private var calibrationJob: Job? = null

    fun startCalibration(owner: LifecycleOwner, previewView: PreviewView) {
        calibrationJob?.cancel()
        container.calibrationSession.start()
        calibrationJob = viewModelScope.launch {
            try {
                container.cameraStreamer.start(
                    lifecycleOwner = owner,
                    previewView = previewView,
                    targetFps = CALIBRATION_TARGET_FPS,
                )
                while (isActive && container.calibrationSession.state.value is CalibrationState.Running) {
                    container.calibrationSession.tick()
                    delay(CALIBRATION_TICK_MS)
                }
            } catch (_: Throwable) {
                container.calibrationSession.fail(CalibrationIssue.CAMERA_UNAVAILABLE)
            }
        }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        container.calibrationSession.cancel()
        container.cameraStreamer.stop()
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

    // ---- 基线（T1-04 对接位） ----
    suspend fun currentBaseline(): PersonalBaseline? = container.baselineStore.current()

    /** 演示用：写入一组假基线让 R4 的距离/姿态比较链路可以先跑起来 */
    suspend fun saveDemoBaseline() {
        container.baselineStore.save(
            PersonalBaseline(
                faceScale = 0.30f, pitchDeg = 8f, yawDeg = 3f, forwardDeg = 4f,
                shoulderTiltDeg = 2f, lumFace = 128f, lumBackground = 110f,
                confidence = 0.95f, sampleCount = 160,
                createdAtWall = System.currentTimeMillis(), sceneTag = "书桌",
            )
        )
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
        const val CALIBRATION_TARGET_FPS = 15
        const val CALIBRATION_TICK_MS = 250L
    }
}
