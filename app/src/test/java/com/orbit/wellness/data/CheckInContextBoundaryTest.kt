package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInContextBoundaryTest {
    private val day = "2026-11-03"

    @Test
    fun previousSameDayCheckInSetsTheBoundary() {
        val resolved = CheckInContextBoundary.resolve(
            checkInId = "noon",
            localDate = day,
            recordedAt = 1_200,
            dayStartAt = 0,
            sameDayCheckIns = listOf(
                PriorCheckIn("other-day", "2026-11-02", 1_100),
                PriorCheckIn("early", day, 400),
                PriorCheckIn("later", day, 1_500),
                PriorCheckIn("mid", day, 800),
                PriorCheckIn("noon", day, 1_200),
            ),
            wakeTimeAt = 100,
        )
        assertEquals(800, resolved.windowStartAt)
        assertEquals(1_200, resolved.windowEndAt)
        assertEquals("mid", resolved.previousCheckInId)
        assertEquals(CheckInContextStartBasis.PREVIOUS_CHECK_IN, resolved.startBasis)
    }

    @Test
    fun wakeTimeIsUsedWhenNoEarlierSameDayCheckInExists() {
        val resolved = CheckInContextBoundary.resolve(
            checkInId = "morning",
            localDate = day,
            recordedAt = 1_000,
            dayStartAt = 0,
            sameDayCheckIns = listOf(PriorCheckIn("later", day, 1_500)),
            wakeTimeAt = 300,
        )
        assertEquals(300, resolved.windowStartAt)
        assertEquals(1_000, resolved.windowEndAt)
        assertNull(resolved.previousCheckInId)
        assertEquals(CheckInContextStartBasis.WAKE_TIME, resolved.startBasis)
    }

    @Test
    fun wakeTimeEqualToTheCheckInIsStillAWakeBoundary() {
        val resolved = CheckInContextBoundary.resolve(
            checkInId = "wake",
            localDate = day,
            recordedAt = 300,
            dayStartAt = 0,
            sameDayCheckIns = emptyList(),
            wakeTimeAt = 300,
        )
        assertEquals(300, resolved.windowStartAt)
        assertEquals(CheckInContextStartBasis.WAKE_TIME, resolved.startBasis)
    }

    @Test
    fun laterWakeTimeFallsBackToLocalDayStart() {
        val resolved = CheckInContextBoundary.resolve(
            checkInId = "early",
            localDate = day,
            recordedAt = 200,
            dayStartAt = 0,
            sameDayCheckIns = emptyList(),
            wakeTimeAt = 900,
        )
        assertEquals(0, resolved.windowStartAt)
        assertEquals(200, resolved.windowEndAt)
        assertNull(resolved.previousCheckInId)
        assertEquals(CheckInContextStartBasis.DAY_START, resolved.startBasis)
    }

    @Test
    fun missingWakeTimeFallsBackToLocalDayStart() {
        val resolved = CheckInContextBoundary.resolve(
            checkInId = "only",
            localDate = day,
            recordedAt = 500,
            dayStartAt = 0,
            sameDayCheckIns = emptyList(),
            wakeTimeAt = null,
        )
        assertEquals(0, resolved.windowStartAt)
        assertEquals(CheckInContextStartBasis.DAY_START, resolved.startBasis)
    }

    @Test
    fun rejectsADayStartAfterTheCheckIn() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CheckInContextBoundary.resolve(
                checkInId = "bad",
                localDate = day,
                recordedAt = 100,
                dayStartAt = 500,
                sameDayCheckIns = emptyList(),
                wakeTimeAt = null,
            )
        }
        assertTrue(error.message!!.contains("must not be later"))
    }

    @Test
    fun newWindowsStartWithFourNoneCategories() {
        val rows = CheckInContextBoundary.defaultCategories("check-in")
        assertEquals(CheckInContextCategories.ALL, rows.map { it.category })
        assertTrue(rows.all { it.status == CheckInContextStatus.NONE && it.reviewedAt == null })
    }
}
