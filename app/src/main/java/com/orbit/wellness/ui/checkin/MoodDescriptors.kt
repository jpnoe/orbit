package com.orbit.wellness.ui.checkin

import com.orbit.wellness.R

fun moodDescriptorResId(score: Int): Int {
    return when (score.coerceIn(0, 10)) {
        0, 1, 2 -> R.string.mood_descriptor_rough
        3, 4 -> R.string.mood_descriptor_low
        5 -> R.string.mood_descriptor_neutral
        6, 7 -> R.string.mood_descriptor_okay
        8, 9 -> R.string.mood_descriptor_good
        else -> R.string.mood_descriptor_excellent
    }
}
