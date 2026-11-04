package com.orbit.wellness.ui.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes

fun checkInEmotionLine(emotion: String, missing: String): String {
    return "Emotion: ${emotion.ifBlank { missing }}"
}

fun checkInMoodLine(score: Int?, missing: String): String {
    return "Mood: ${score?.toString() ?: missing}"
}

fun checkInAxisLine(axisId: String, score: Int?, missing: String): String {
    val name = DefaultCheckInAxes.ALL.firstOrNull { it.id == axisId }?.name ?: axisId
    return "$name: ${score?.toString() ?: missing}"
}
