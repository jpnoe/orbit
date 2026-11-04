package com.orbit.wellness.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DateUtilsTimeRangeTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun rangeKeepsTheDateOnTheFirstTimeWhenBothAreTheSameDay() {
        val start = at(2026, 11, 4, 7, 30)
        val end = at(2026, 11, 4, 10, 15)
        assertEquals(
            "Wed Nov 4, 7:30 AM – 10:15 AM",
            DateUtils.timeRangeLabel(start, end, zone),
        )
    }

    @Test
    fun rangeRepeatsTheDateWhenTheEndFallsOnAnotherDay() {
        val start = at(2026, 11, 3, 23, 0)
        val end = at(2026, 11, 4, 7, 30)
        assertEquals(
            "Tue Nov 3, 11:00 PM – Wed Nov 4, 7:30 AM",
            DateUtils.timeRangeLabel(start, end, zone),
        )
    }

    @Test
    fun wakeupRangeUsesTheStartDateAndOmitsASecondDateOnTheSameDay() {
        val start = at(2026, 11, 4, 0, 0)
        val end = at(2026, 11, 4, 10, 15)
        assertEquals(
            "Wed Nov 4, Wakeup – 10:15 AM",
            DateUtils.wakeupRangeLabel(start, end, "Wakeup", zone),
        )
    }

    @Test
    fun singleEntryStillIncludesTheWeekdayAndDate() {
        assertEquals(
            "Wed Nov 4, 10:15 AM",
            DateUtils.datedTimeLabel(at(2026, 11, 4, 10, 15), zone),
        )
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
    }
}
