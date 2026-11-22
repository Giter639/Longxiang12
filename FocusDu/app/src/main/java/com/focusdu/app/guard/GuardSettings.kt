package com.focusdu.app.guard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class LaunchableApp(
    val packageName: String,
    val label: String,
)

/** 限制应用集合只保存包名，不读取应用内容。 */
class GuardSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _restrictedPackages = MutableStateFlow(
        prefs.getStringSet(KEY_RESTRICTED, emptySet()).orEmpty().toSet()
    )
    val restrictedPackages: StateFlow<Set<String>> = _restrictedPackages.asStateFlow()

    fun setRestricted(packageName: String, restricted: Boolean) {
        val next = _restrictedPackages.value.toMutableSet().apply {
            if (restricted) add(packageName) else remove(packageName)
        }
        prefs.edit().putStringSet(KEY_RESTRICTED, next).apply()
        _restrictedPackages.value = next
    }

    private companion object {
        const val PREFS = "guard_settings"
        const val KEY_RESTRICTED = "restricted_packages"
    }
}

class LaunchableAppRepository(private val context: Context) {

    suspend fun load(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName ||
                    GuardDecision.isEmergencyAllowed(packageName)
                ) {
                    return@mapNotNull null
                }
                val label = info.loadLabel(packageManager).toString()
                LaunchableApp(packageName, label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
