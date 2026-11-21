package com.focusdu.app.guard

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class GuardPermission {
    CAMERA,
    NOTIFICATIONS,
    USAGE_ACCESS,
    NOTIFICATION_LISTENER,
    OVERLAY,
}

enum class GuardPermissionState {
    GRANTED,
    DENIED,
    REVOKED,
}

data class GuardPermissionSnapshot(
    val state: GuardPermissionState,
)

/**
 * 系统特殊权限无法统一请求，只能检查并跳转到对应系统页面。
 * “撤回”通过曾经授予过、但当前已不可用识别；首次拒绝仍显示“拒绝”。
 */
class GuardPermissions(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(): Map<GuardPermission, GuardPermissionSnapshot> =
        GuardPermission.entries.associateWith { permission ->
            val granted = isGranted(permission)
            if (granted) {
                prefs.edit().putBoolean(seenKey(permission), true).apply()
            }
            val state = when {
                granted -> GuardPermissionState.GRANTED
                prefs.getBoolean(seenKey(permission), false) -> GuardPermissionState.REVOKED
                else -> GuardPermissionState.DENIED
            }
            GuardPermissionSnapshot(state)
        }

    fun isGranted(permission: GuardPermission): Boolean = when (permission) {
        GuardPermission.CAMERA ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        GuardPermission.NOTIFICATIONS ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        GuardPermission.USAGE_ACCESS -> hasUsageAccess()
        GuardPermission.NOTIFICATION_LISTENER ->
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        GuardPermission.OVERLAY -> Settings.canDrawOverlays(context)
    }

    fun settingsIntent(permission: GuardPermission): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        return when (permission) {
            GuardPermission.USAGE_ACCESS ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            GuardPermission.NOTIFICATION_LISTENER ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            GuardPermission.OVERLAY ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            GuardPermission.CAMERA,
            GuardPermission.NOTIFICATIONS ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun seenKey(permission: GuardPermission) = "seen_${permission.name}"

    private companion object {
        const val PREFS = "guard_permissions"
    }
}
