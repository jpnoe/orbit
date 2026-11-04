package com.orbit.wellness.ui.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckInScoreLinesTest {
    @Test
    fun linesShowTheScoreEmotionAndADashWhenUnset() {
        assertEquals("Emotion: Calm", checkInEmotionLine("Calm", "-"))
        assertEquals("Emotion: -", checkInEmotionLine("", "-"))
        assertEquals("Emotion: -", checkInEmotionLine("   ", "-"))
        assertEquals("Mood: 6", checkInMoodLine(6, "-"))
        assertEquals("Mood: -", checkInMoodLine(null, "-"))
        assertEquals("Energy: 4", checkInAxisLine(DefaultCheckInAxes.ENERGY, 4, "-"))
        assertEquals("Confidence: -", checkInAxisLine(DefaultCheckInAxes.CONFIDENCE, null, "-"))
        assertEquals("Anxiety: 2", checkInAxisLine(DefaultCheckInAxes.ANXIETY, 2, "-"))
    }
}
