package com.focusdu.app.pomodoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.focusdu.app.FocusDuApp
import com.focusdu.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * T3-02 计时前台服务：息屏续计（引擎用单调时钟，服务只负责保活与通知）、
 * 相位变化刷通知、轮次结束后触发报告生成（T6-01 验收：结束 3s 内出报告）。
 */
class PomodoroService : Service() {

    private val container get() = (application as FocusDuApp).container
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> container.pomodoroEngine.pause()
            ACTION_RESUME -> container.pomodoroEngine.resume()
            ACTION_GIVE_UP -> container.appScope.launch { container.pomodoroEngine.giveUp() }
            else -> {
                startForegroundSafely()
                if (observeJob == null) observeState()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        observeJob = null
        super.onDestroy()
    }

    private fun observeState() {
        observeJob = container.appScope.launch {
            container.pomodoroEngine.state.collect { s ->
                NotificationManagerCompat.from(this@PomodoroService)
                    .notify(NOTIF_ID, buildNotification(s))
                if (s.phase == Phase.FINISHED && s.sessionId > 0) {
                    container.reportGenerator.generate(s.sessionId)
                    stopSelf()
                }
            }
        }
    }

    private fun startForegroundSafely() {
        val notification = buildNotification(container.pomodoroEngine.state.value)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(s: PomodoroState): Notification {
        val text = when (s.phase) {
            Phase.FOCUS -> "专注中 · 剩余 ${fmt(s.focusRemainingMs)}"
            Phase.FOCUS_PAUSED -> "已暂停 · 已专注 ${fmt(s.focusElapsedMs)}"
            Phase.BREAK -> "休息中 · 剩余 ${fmt(s.breakRemainingMs)}"
            Phase.FINISHED -> "本轮完成，报告已生成"
            Phase.IDLE -> "准备开始"
        }
        val pauseResume = if (s.phase == Phase.FOCUS) {
            action(ACTION_PAUSE, "暂停")
        } else {
            action(ACTION_RESUME, "继续")
        }
        return NotificationCompat.Builder(this, FocusDuApp.CHANNEL_POMODORO)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(s.phase == Phase.FOCUS || s.phase == Phase.BREAK)
            .addAction(0, pauseResume.first, pauseResume.second)
            .addAction(0, "放弃本轮", actionIntent(ACTION_GIVE_UP))
            .build()
    }

    private fun action(action: String, label: String) = label to actionIntent(action)

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PomodoroService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun fmt(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }

    companion object {
        const val ACTION_START = "com.focusdu.app.action.START"
        const val ACTION_PAUSE = "com.focusdu.app.action.PAUSE"
        const val ACTION_RESUME = "com.focusdu.app.action.RESUME"
        const val ACTION_GIVE_UP = "com.focusdu.app.action.GIVE_UP"
        const val NOTIF_ID = 1001
    }
}
