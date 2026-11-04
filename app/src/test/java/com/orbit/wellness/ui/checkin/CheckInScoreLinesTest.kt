package com.orbit.wellness.ui.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckInScoreLinesTest {
    @Test
    fun linesShowTheScoreEmotionAndADashWhenUnset() {
        assertEquals("Mood 6 - Calm", checkInMoodLine(6, "Calm", "-"))
        assertEquals("Mood 6", checkInMoodLine(6, "", "-"))
        assertEquals("Mood -", checkInMoodLine(null, "Calm", "-"))
        assertEquals("Energy 4", checkInAxisLine(DefaultCheckInAxes.ENERGY, 4, "-"))
        assertEquals("Confidence -", checkInAxisLine(DefaultCheckInAxes.CONFIDENCE, null, "-"))
        assertEquals("Anxiety 2", checkInAxisLine(DefaultCheckInAxes.ANXIETY, 2, "-"))
    }
}
