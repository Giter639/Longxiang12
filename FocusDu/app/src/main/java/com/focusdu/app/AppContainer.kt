package com.focusdu.app

import android.content.Context
import com.focusdu.app.core.AndroidMonotonicClock
import com.focusdu.app.core.MonotonicClock
import com.focusdu.app.data.RollingCleaner
import com.focusdu.app.data.db.AppDatabase
import com.focusdu.app.pomodoro.PomodoroEngine
import com.focusdu.app.pomodoro.PomodoroStore
import com.focusdu.app.pomodoro.RoomPomodoroStore
import com.focusdu.app.reminder.ReminderEngine
import com.focusdu.app.reminder.RiskEventWire
import com.focusdu.app.report.ReportGenerator
import com.focusdu.app.track.RoomTracker

import com.focusdu.app.vision.BaselineStore
import com.focusdu.app.vision.CameraStreamer
import com.focusdu.app.vision.MockVisionEngine
import com.focusdu.app.vision.NoCameraStreamer
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
 * 三人接入点（改绑定，不改调用方）：
 * - R3：cameraStreamer 换成 CameraXStreamer 实现（X11）
 * - R4：visionEngine 换成 MediaPipeVisionEngine 实现（X00/T2-10）
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
    val pomodoroEngine = PomodoroEngine(clock, appScope, tracker, pomodoroStore)

    val cameraStreamer: CameraStreamer = NoCameraStreamer()                 // ← R3 接入点
    val visionEngine: VisionEngine = MockVisionEngine(clock, appScope)     // ← R4 接入点
    val debouncer = StateStreamDebouncer(appScope, clock)
    val baselineStore = BaselineStore(db)
    val reportGenerator = ReportGenerator(db, tracker, clock, cleaner)

    private val spanLogger = StateSpanLogger(clock, tracker, pomodoroEngine.state, debouncer.ui)
    private val riskWire = RiskEventWire(clock, reminderEngine, pomodoroEngine, debouncer.ui, pomodoroEngine.state)
    private val sampleRecorder = VisionSampleRecorder(db, pomodoroEngine.state)

    fun onAppReady() {
        tracker.sessionBinder = { pomodoroEngine.state.value.sessionId }   // 埋点自动归入当前会话
        cleaner.onAppStart()
        appScope.launch { pomodoroEngine.recoverIfPossible() }          // T3-02 异常恢复
        appScope.launch { visionEngine.samples().collect { debouncer.onSample(it) } }
        appScope.launch { visionEngine.samples().collect { sampleRecorder.onSample(it) } }   // 采样落盘
        spanLogger.start(appScope)
        riskWire.start(appScope)
    }
}
