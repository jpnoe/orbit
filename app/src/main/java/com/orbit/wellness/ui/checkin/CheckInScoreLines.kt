package com.orbit.wellness.ui.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes

fun checkInMoodLine(score: Int?, emotion: String, missing: String): String {
    val shown = score?.toString() ?: missing
    return if (score != null && emotion.isNotBlank()) {
        "Mood $shown - $emotion"
    } else {
        "Mood $shown"
    }
}

fun checkInAxisLine(axisId: String, score: Int?, missing: String): String {
    val name = DefaultCheckInAxes.ALL.firstOrNull { it.id == axisId }?.name ?: axisId
    return "$name ${score?.toString() ?: missing}"
}
