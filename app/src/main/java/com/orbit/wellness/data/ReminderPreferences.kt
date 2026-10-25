package com.orbit.wellness.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.reminderDataStore by preferencesDataStore(name = "orbit_reminder")

class ReminderPreferences(context: Context) {
    private val dataStore = context.applicationContext.reminderDataStore

    val reminderTime: Flow<LocalTime> = dataStore.data.map { prefs ->
        LocalTime.of(
            (prefs[HOUR_KEY] ?: DEFAULT_HOUR).coerceIn(0, 23),
            (prefs[MINUTE_KEY] ?: DEFAULT_MINUTE).coerceIn(0, 59),
        )
    }

    suspend fun setReminderTime(time: LocalTime) {
        dataStore.edit { prefs ->
            prefs[HOUR_KEY] = time.hour
            prefs[MINUTE_KEY] = time.minute
        }
    }

    companion object {
        const val DEFAULT_HOUR = 20
        const val DEFAULT_MINUTE = 30
        val DEFAULT_TIME: LocalTime = LocalTime.of(DEFAULT_HOUR, DEFAULT_MINUTE)

        private val HOUR_KEY = intPreferencesKey("reminder_hour")
        private val MINUTE_KEY = intPreferencesKey("reminder_minute")
    }
}
