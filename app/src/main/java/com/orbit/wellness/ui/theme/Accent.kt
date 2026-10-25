package com.orbit.wellness.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.orbit.wellness.R

enum class AccentOption(
    val id: String,
    val seed: Color,
    val labelRes: Int,
) {
    AURORA("aurora", Color(0xFF0D9488), R.string.accent_aurora),
    OCEAN("ocean", Color(0xFF2563EB), R.string.accent_ocean),
    VIOLET("violet", Color(0xFF7C3AED), R.string.accent_violet),
    SUNSET("sunset", Color(0xFFE11D48), R.string.accent_sunset),
    AMBER("amber", Color(0xFFD97706), R.string.accent_amber),
    FOREST("forest", Color(0xFF15803D), R.string.accent_forest),
    SLATE("slate", Color(0xFF475569), R.string.accent_slate),
    ;

    companion object {
        val DEFAULT = AURORA

        fun fromId(id: String?): AccentOption =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Keeps the default light/dark surfaces and only tints highlight tokens:
 * selected chips (`secondaryContainer`), filled buttons and sliders (`primary`).
 */
fun colorSchemeFromAccent(seed: Color, darkTheme: Boolean): ColorScheme {
    val primary = if (darkTheme) seed.shiftValue(0.18f) else seed
    val highlightContainer = if (darkTheme) {
        seed.shiftValue(-0.22f)
    } else {
        seed.mix(Color.White, 0.78f)
    }
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = primary.contrastingContent(),
        primaryContainer = highlightContainer,
        onPrimaryContainer = highlightContainer.contrastingContent(),
        secondaryContainer = highlightContainer,
        onSecondaryContainer = highlightContainer.contrastingContent(),
        inversePrimary = if (darkTheme) seed else seed.shiftValue(0.18f),
        surfaceTint = base.surface,
    )
}

private fun Color.contrastingContent(): Color {
    return if (luminance() > 0.45f) Color.Black else Color.White
}

private fun Color.mix(other: Color, otherFraction: Float): Color {
    val t = otherFraction.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * t,
        green = green + (other.green - green) * t,
        blue = blue + (other.blue - blue) * t,
        alpha = alpha,
    )
}

private fun Color.shiftValue(delta: Float): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(toArgb(), hsv)
    hsv[2] = (hsv[2] + delta).coerceIn(0f, 1f)
    return Color(AndroidColor.HSVToColor(hsv))
}
