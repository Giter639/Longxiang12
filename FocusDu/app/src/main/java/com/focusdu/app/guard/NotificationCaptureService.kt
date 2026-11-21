package com.focusdu.app.guard

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.focusdu.app.FocusDuApp

/**
 * 只在内存里记录通知来源包名，不读取或展示通知正文，也不取消原通知。
 * 因此应用内消息仍保持未读状态。
 */
class NotificationCaptureService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as? FocusDuApp ?: return
        val packageName = sbn.packageName ?: return
        if (packageName == this.packageName) return
        app.container.notificationMemory.record(packageName)
    }
}
