package com.orbit.wellness.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.orbit.wellness.OrbitApplication

@Composable
fun OrbitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val themePreferences = (context.applicationContext as OrbitApplication).themePreferences
    val accent by themePreferences.accent.collectAsState(initial = AccentOption.DEFAULT)

    val colorScheme = remember(darkTheme, accent) {
        colorSchemeFromAccent(accent.seed, darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
