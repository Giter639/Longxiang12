package com.focusdu.app.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderMessageResolverTest {

    @Test
    fun mapsTwelveScenariosToThirtySixDistinctResources() {
        val events = listOf(
            event(RiskType.DISTANCE, RiskLevel.LIGHT),
            event(RiskType.DISTANCE, RiskLevel.MODERATE),
            event(RiskType.POSTURE, RiskLevel.LIGHT),
            event(RiskType.POSTURE, RiskLevel.MODERATE),
            event(RiskType.DROWSINESS, RiskLevel.LIGHT),
            event(RiskType.DROWSINESS, RiskLevel.MODERATE),
            event(RiskType.DROWSINESS, RiskLevel.SEVERE),
            event(RiskType.LIGHT, RiskLevel.LIGHT),
            event(RiskType.LIGHT, RiskLevel.MODERATE),
            event(RiskType.LIGHT, RiskLevel.SEVERE),
            event(RiskType.SEDENTARY, RiskLevel.MODERATE),
            event(RiskType.OFF_SCREEN, RiskLevel.MODERATE),
        )
        val resources = events.flatMap { risk ->
            ReminderPersona.entries.map { persona ->
                ReminderMessageResolver.resourceId(risk, persona)
            }
        }

        assertEquals(36, resources.size)
        assertEquals(36, resources.toSet().size)
        assertTrue(resources.all { it != 0 })
    }

    @Test
    fun distanceAndPostureUseDifferentSeverityScenarios() {
        val light = event(RiskType.DISTANCE, RiskLevel.LIGHT)
        val severe = event(RiskType.DISTANCE, RiskLevel.SEVERE)
        assertEquals(ReminderScenario.DISTANCE_LIGHT, ReminderMessageResolver.scenario(light))
        assertEquals(ReminderScenario.DISTANCE_SUSTAINED, ReminderMessageResolver.scenario(severe))
    }

    @Test
    fun personaStorageFallsBackToGentle() {
        assertEquals(ReminderPersona.GENTLE, ReminderPersona.fromStorage(null))
        assertEquals(ReminderPersona.COACH, ReminderPersona.fromStorage("coach"))
        assertEquals(ReminderPersona.GENTLE, ReminderPersona.fromStorage("unknown"))
    }

    private fun event(type: RiskType, level: RiskLevel) = RiskEvent(
        type = type,
        level = level,
        startMs = 1_000L,
        durationMs = 20_000L,
    )
}
