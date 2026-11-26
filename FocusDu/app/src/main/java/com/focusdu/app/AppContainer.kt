package com.focusdu.app

import android.content.Context
import com.focusdu.app.core.AndroidMonotonicClock
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.data.RollingCleaner
import com.focusdu.app.data.db.AppDatabase
import com.focusdu.app.guard.FocusGuardEngine
import com.focusdu.app.guard.GuardPermissions
import com.focusdu.app.guard.GuardSettings
import com.focusdu.app.guard.LaunchableAppRepository
import com.focusdu.app.guard.NotificationMemoryStore
import com.focusdu.app.perf.DevicePerformanceMonitor
import com.focusdu.app.pomodoro.PomodoroEngine
import com.focusdu.app.pomodoro.PomodoroStore
import com.focusdu.app.pomodoro.RoomPomodoroStore
import com.focusdu.app.reminder.ReminderEngine
import com.focusdu.app.reminder.ReminderPreferences
import com.focusdu.app.reminder.RiskEventWire
import com.focusdu.app.report.ReportGenerator
import com.focusdu.app.report.RestValidator
import com.focusdu.app.track.RoomTracker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

import com.focusdu.app.vision.BaselineStore
import com.focusdu.app.vision.CalibrationSession
import com.focusdu.app.vision.CameraStreamer
import com.focusdu.app.vision.CameraXStreamer
import com.focusdu.app.vision.MockVisionEngine
import com.focusdu.app.vision.MediaPipeVisionEngine
import com.focusdu.app.vision.SessionCameraGate
import com.focusdu.app.vision.StateSpanLogger
import com.focusdu.app.vision.StateStreamDebouncer
import com.focusdu.app.vision.VisionEngine
import com.focusdu.app.vision.VisionSampleRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * X02 骨架核心：手工依赖容器（比赛期不引入 Hilt，减少构建变量）。
 * 所有引擎都是进程级单例，UI 通过 AppSessionViewModel 订阅它们的状态流。
 *
 * 已接入：
 * - R3：cameraStreamer = CameraXStreamer（X11）；校准采集与番茄取流共用相机，开关统一走 SessionCameraGate
 * - R4：visionEngine = MediaPipeVisionEngine（X00/T2-10）；基线经 setBaseline 注入（T1-04）
 * - R2：不动代码，只改 res/values/strings.xml 文案（T5-01/T7-01）
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val clock: MonotonicClock = AndroidMonotonicClock()
    val db = AppDatabase.build(appContext)
    val tracker: RoomTracker = RoomTracker(db, appScope)
    val cleaner = RollingCleaner(db, appScope)
    val pomodoroStore: PomodoroStore = RoomPomodoroStore(db)

    val reminderEngine = ReminderEngine(clock, tracker)
    val reminderPreferences = ReminderPreferences(appContext)
    val pomodoroEngine = PomodoroEngine(clock, appScope, tracker, pomodoroStore)
    val guardPermissions = GuardPermissions(appContext)
    val guardSettings = GuardSettings(appContext)
    val launchableApps = LaunchableAppRepository(appContext)
    val notificationMemory = NotificationMemoryStore(clock)
    val focusGuardEngine = FocusGuardEngine(
        context = appContext,
        scope = appScope,
        clock = clock,
        pomodoroState = pomodoroEngine.state,
        settings = guardSettings,
        permissions = guardPermissions,
        notificationMemory = notificationMemory,
        tracker = tracker,
    )

    val cameraStreamer: CameraStreamer = CameraXStreamer(appContext, clock) // ← R3 接入点
    val calibrationSession = CalibrationSession(clock)
    val performanceMonitor = DevicePerformanceMonitor(
        context = appContext,
        scope = appScope,
        clock = clock,
        cameraStreamer = cameraStreamer,
    )
    // R4 真引擎（MediaPipe FaceLandmarker）为默认绑定。联调异常时把下一行的注释互换即可切回
    // MockVisionEngine（按剧本输出假数据），其余代码不用动。
    val visionEngine: VisionEngine = MediaPipeVisionEngine(appContext, clock, appScope, cameraStreamer)
    // val visionEngine: VisionEngine = MockVisionEngine(clock, appScope)
    val sessionCamera = SessionCameraGate(cameraStreamer, appScope)
    val debouncer = StateStreamDebouncer(appScope, clock)
    val baselineStore = BaselineStore(db)
    val reportGenerator = ReportGenerator(db, tracker, clock, cleaner)

    private val spanLogger = StateSpanLogger(clock, tracker, pomodoroEngine.state, debouncer.ui)
    private val riskWire = RiskEventWire(clock, reminderEngine, pomodoroEngine, debouncer.ui, pomodoroEngine.state)
    private val sampleRecorder = VisionSampleRecorder(db, pomodoroEngine.state)
    val restValidator = RestValidator(clock, tracker)

    fun onAppReady() {
        tracker.sessionBinder = { pomodoroEngine.state.value.sessionId }   // 埋点自动归入当前会话
        cleaner.onAppStart()
        focusGuardEngine.start()
        performanceMonitor.bindToStreaming()
        appScope.launch { pomodoroEngine.recoverIfPossible() }          // T3-02 异常恢复
        appScope.launch {
            // 重启后把已保存的个人基线交给引擎，距离判定无需重新校准（T1-04）
            visionEngine.setBaseline(baselineStore.current())
        }
        appScope.launch {
            // 番茄期间自动开关相机：专注、休息阶段开启，其余阶段关闭
            pomodoroEngine.state.map { it.phase }.distinctUntilChanged()
                .collect { sessionCamera.setSessionWanted(SessionCameraGate.sessionNeedsCamera(it)) }
        }
        appScope.launch { visionEngine.samples().collect { debouncer.onSample(it) } }
        appScope.launch { visionEngine.samples().collect { sampleRecorder.onSample(it) } }   // 采样落盘
        appScope.launch { visionEngine.samples().collect { calibrationSession.onVisionSample(it) } }
        appScope.launch { cameraStreamer.frames().collect { calibrationSession.onFrame(it) } }
        spanLogger.start(appScope)
        riskWire.start(appScope)
        // T3-04 休息恢复校验：观察相位变化 + 实时状态流
        appScope.launch {
            pomodoroEngine.state
                .map { it.phase to it.sessionId }
                .distinctUntilChanged()
                .collect { (phase, sid) -> restValidator.onPhase(phase, sid) }
        }
        appScope.launch {
            debouncer.ui.collect { u ->
                (u as? StateStreamDebouncer.UiState.Live)?.let { restValidator.onVisionState(it.state) }
            }
        }
    }
}
