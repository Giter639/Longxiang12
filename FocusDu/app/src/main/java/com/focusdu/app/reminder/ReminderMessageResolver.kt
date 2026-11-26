package com.focusdu.app.reminder

import com.focusdu.app.R

enum class ReminderScenario {
    DISTANCE_LIGHT,
    DISTANCE_SUSTAINED,
    POSTURE_LIGHT,
    POSTURE_SUSTAINED,
    DROWSINESS_LIGHT,
    DROWSINESS_MODERATE,
    DROWSINESS_SEVERE,
    LIGHT_DARK,
    LIGHT_BACKLIGHT,
    LIGHT_UNEVEN,
    SEDENTARY,
    OFF_SCREEN,
}

object ReminderMessageResolver {

    fun scenario(event: RiskEvent): ReminderScenario = when (event.type) {
        RiskType.DISTANCE ->
            if (event.level == RiskLevel.LIGHT) ReminderScenario.DISTANCE_LIGHT
            else ReminderScenario.DISTANCE_SUSTAINED

        RiskType.POSTURE ->
            if (event.level == RiskLevel.LIGHT) ReminderScenario.POSTURE_LIGHT
            else ReminderScenario.POSTURE_SUSTAINED

        RiskType.DROWSINESS -> when (event.level) {
            RiskLevel.LIGHT -> ReminderScenario.DROWSINESS_LIGHT
            RiskLevel.MODERATE -> ReminderScenario.DROWSINESS_MODERATE
            RiskLevel.SEVERE -> ReminderScenario.DROWSINESS_SEVERE
        }

        // RiskEvent 协议当前没有光照子类型，暂用等级映射三种光照文案：
        // LIGHT=偏暗、MODERATE=逆光、SEVERE=眩光/不均。
        RiskType.LIGHT -> when (event.level) {
            RiskLevel.LIGHT -> ReminderScenario.LIGHT_DARK
            RiskLevel.MODERATE -> ReminderScenario.LIGHT_BACKLIGHT
            RiskLevel.SEVERE -> ReminderScenario.LIGHT_UNEVEN
        }

        RiskType.SEDENTARY -> ReminderScenario.SEDENTARY
        RiskType.OFF_SCREEN -> ReminderScenario.OFF_SCREEN
    }

    fun resourceId(event: RiskEvent, persona: ReminderPersona): Int =
        resourceId(scenario(event), persona)

    fun resourceId(scenario: ReminderScenario, persona: ReminderPersona): Int = when (scenario) {
        ReminderScenario.DISTANCE_LIGHT -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_distance_light_gentle
            ReminderPersona.COACH -> R.string.reminder_distance_light_coach
            ReminderPersona.STRICT -> R.string.reminder_distance_light_strict
        }
        ReminderScenario.DISTANCE_SUSTAINED -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_distance_sustained_gentle
            ReminderPersona.COACH -> R.string.reminder_distance_sustained_coach
            ReminderPersona.STRICT -> R.string.reminder_distance_sustained_strict
        }
        ReminderScenario.POSTURE_LIGHT -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_posture_light_gentle
            ReminderPersona.COACH -> R.string.reminder_posture_light_coach
            ReminderPersona.STRICT -> R.string.reminder_posture_light_strict
        }
        ReminderScenario.POSTURE_SUSTAINED -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_posture_sustained_gentle
            ReminderPersona.COACH -> R.string.reminder_posture_sustained_coach
            ReminderPersona.STRICT -> R.string.reminder_posture_sustained_strict
        }
        ReminderScenario.DROWSINESS_LIGHT -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_drowsiness_light_gentle
            ReminderPersona.COACH -> R.string.reminder_drowsiness_light_coach
            ReminderPersona.STRICT -> R.string.reminder_drowsiness_light_strict
        }
        ReminderScenario.DROWSINESS_MODERATE -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_drowsiness_moderate_gentle
            ReminderPersona.COACH -> R.string.reminder_drowsiness_moderate_coach
            ReminderPersona.STRICT -> R.string.reminder_drowsiness_moderate_strict
        }
        ReminderScenario.DROWSINESS_SEVERE -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_drowsiness_severe_gentle
            ReminderPersona.COACH -> R.string.reminder_drowsiness_severe_coach
            ReminderPersona.STRICT -> R.string.reminder_drowsiness_severe_strict
        }
        ReminderScenario.LIGHT_DARK -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_light_dark_gentle
            ReminderPersona.COACH -> R.string.reminder_light_dark_coach
            ReminderPersona.STRICT -> R.string.reminder_light_dark_strict
        }
        ReminderScenario.LIGHT_BACKLIGHT -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_light_backlight_gentle
            ReminderPersona.COACH -> R.string.reminder_light_backlight_coach
            ReminderPersona.STRICT -> R.string.reminder_light_backlight_strict
        }
        ReminderScenario.LIGHT_UNEVEN -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_light_uneven_gentle
            ReminderPersona.COACH -> R.string.reminder_light_uneven_coach
            ReminderPersona.STRICT -> R.string.reminder_light_uneven_strict
        }
        ReminderScenario.SEDENTARY -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_sedentary_gentle
            ReminderPersona.COACH -> R.string.reminder_sedentary_coach
            ReminderPersona.STRICT -> R.string.reminder_sedentary_strict
        }
        ReminderScenario.OFF_SCREEN -> when (persona) {
            ReminderPersona.GENTLE -> R.string.reminder_off_screen_gentle
            ReminderPersona.COACH -> R.string.reminder_off_screen_coach
            ReminderPersona.STRICT -> R.string.reminder_off_screen_strict
        }
    }
}
