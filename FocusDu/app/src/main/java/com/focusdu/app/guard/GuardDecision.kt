package com.focusdu.app.guard

/** 限制应用和紧急放行的纯逻辑，便于在没有真机时做单元测试。 */
object GuardDecision {

    fun shouldIntercept(
        foregroundPackage: String?,
        restrictedPackages: Set<String>,
        ownPackage: String,
    ): Boolean {
        if (foregroundPackage == null) return false
        if (foregroundPackage == ownPackage) return false
        if (foregroundPackage !in restrictedPackages) return false
        return !isEmergencyAllowed(foregroundPackage)
    }

    fun isEmergencyAllowed(packageName: String): Boolean {
        if (packageName == "android") return true
        if (packageName in EXACT_ALLOWED) return true
        return ALLOWED_PARTS.any(packageName::contains)
    }

    private val EXACT_ALLOWED = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.incallui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.oplus.safecenter",
        "com.coloros.safecenter",
    )

    private val ALLOWED_PARTS = listOf(
        ".systemui",
        ".dialer",
        ".telecom",
        ".incallui",
        ".contacts",
        ".emergency",
        ".keyguard",
        ".safecenter",
        ".securitycenter",
    )
}
