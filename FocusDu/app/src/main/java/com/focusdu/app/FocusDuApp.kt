package com.focusdu.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class FocusDuApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        container = AppContainer(this)
        container.onAppReady()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_POMODORO,
            "番茄计时",
            NotificationManager.IMPORTANCE_LOW,   // 分级提醒走 P08 页内提示，通知本身保持安静
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_POMODORO = "pomodoro"
    }
}

/** 全局访问入口：页面里 (requireActivity().application as FocusDuApp).container */
val Context.focusDuApp: FocusDuApp
    get() = applicationContext as FocusDuApp
