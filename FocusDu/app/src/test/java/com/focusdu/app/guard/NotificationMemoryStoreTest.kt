package com.focusdu.app.guard

import com.focusdu.app.core.FakeMonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMemoryStoreTest {

    @Test
    fun recordsOnlyPackageCountsDuringActiveSession() {
        val clock = FakeMonotonicClock()
        val store = NotificationMemoryStore(clock)
        store.beginSession(10)
        store.record("com.example.chat")
        clock.advance(100)
        store.record("com.example.chat")
        clock.advance(100)
        store.record("com.example.mail")
        store.endSession()
        store.record("com.example.chat")

        val summary = store.snapshot()
        assertEquals(2, summary.size)
        assertEquals(2, summary.first { it.packageName == "com.example.chat" }.count)
        assertEquals(1, summary.first { it.packageName == "com.example.mail" }.count)
    }

    @Test
    fun resumeSameSessionKeepsSummaryAndNewSessionClearsIt() {
        val clock = FakeMonotonicClock()
        val store = NotificationMemoryStore(clock)
        store.beginSession(10)
        store.record("com.example.chat")
        store.endSession()

        store.beginSession(10)
        store.record("com.example.chat")
        assertEquals(2, store.snapshot().single().count)

        store.beginSession(11)
        assertTrue(store.snapshot().isEmpty())
    }
}
