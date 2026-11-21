package com.focusdu.app.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDecisionTest {

    @Test
    fun interceptsOnlySelectedNonEmergencyApps() {
        assertTrue(
            GuardDecision.shouldIntercept(
                foregroundPackage = "com.example.video",
                restrictedPackages = setOf("com.example.video"),
                ownPackage = "com.focusdu.app",
            )
        )
        assertFalse(
            GuardDecision.shouldIntercept(
                foregroundPackage = "com.example.chat",
                restrictedPackages = setOf("com.example.video"),
                ownPackage = "com.focusdu.app",
            )
        )
        assertFalse(
            GuardDecision.shouldIntercept(
                foregroundPackage = "com.focusdu.app",
                restrictedPackages = setOf("com.focusdu.app"),
                ownPackage = "com.focusdu.app",
            )
        )
    }

    @Test
    fun phoneContactsAndSystemSafetyAlwaysPass() {
        val protected = listOf(
            "com.android.dialer",
            "com.google.android.contacts",
            "com.android.server.telecom",
            "com.android.systemui",
            "com.oplus.safecenter",
            "android",
        )
        protected.forEach {
            assertTrue("$it should be allowed", GuardDecision.isEmergencyAllowed(it))
        }
    }

    @Test
    fun restrictedEmergencyPackageIsNeverIntercepted() {
        assertFalse(
            GuardDecision.shouldIntercept(
                foregroundPackage = "com.android.dialer",
                restrictedPackages = setOf("com.android.dialer"),
                ownPackage = "com.focusdu.app",
            )
        )
    }
}
