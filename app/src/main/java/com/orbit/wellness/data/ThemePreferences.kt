package com.orbit.wellness.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orbit.wellness.ui.theme.AccentOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "orbit_theme")

class ThemePreferences(context: Context) {
    private val dataStore = context.applicationContext.themeDataStore

    val accent: Flow<AccentOption> = dataStore.data.map { prefs ->
        AccentOption.fromId(prefs[ACCENT_KEY])
    }

    suspend fun setAccent(option: AccentOption) {
        dataStore.edit { prefs ->
            prefs[ACCENT_KEY] = option.id
        }
    }

    companion object {
        private val ACCENT_KEY = stringPreferencesKey("accent_option")
    }
}
