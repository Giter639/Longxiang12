package com.focusdu.app.reminder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReminderPersona(val storageValue: String) {
    GENTLE("gentle"),
    COACH("coach"),
    STRICT("strict");

    companion object {
        fun fromStorage(value: String?): ReminderPersona =
            entries.firstOrNull { it.storageValue == value } ?: GENTLE
    }
}

class ReminderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _persona = MutableStateFlow(
        ReminderPersona.fromStorage(prefs.getString(KEY_PERSONA, null))
    )
    val persona: StateFlow<ReminderPersona> = _persona.asStateFlow()

    fun setPersona(persona: ReminderPersona) {
        prefs.edit().putString(KEY_PERSONA, persona.storageValue).apply()
        _persona.value = persona
    }

    private companion object {
        const val PREFS = "reminder_preferences"
        const val KEY_PERSONA = "reminder_persona"
    }
}
