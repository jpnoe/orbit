package com.orbit.wellness.data

import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableMethod
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind
import com.orbit.wellness.data.local.WearableUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInIntervalWearablesTest {
    private val start = 1_000L
    private val end = 5_000L

    @Test
    fun exactAggregateIsShownAndAWiderStepsTotalIsNot() {
        val exact = steps(start, end, 800.0, WearableMethod.AGGREGATE_TOTAL)
        val dayTotal = steps(0L, 9_000L, 4_000.0, WearableMethod.AGGREGATE_TOTAL)
        val partial = steps(1_200L, 2_000L, 100.0, WearableMethod.STEPS_RECORD)
        val selected = CheckInIntervalWearables.forInterval(start, end, listOf(dayTotal, partial, exact))
        assertEquals(listOf(exact.id), selected.map { it.id })
        assertSame(exact, selected.single())
        assertEquals("800", historyWearableAmount(exact))
    }

    @Test
    fun containedStepsStayWhenNoExactAggregateExists() {
        val partial = steps(1_200L, 2_000L, 100.0, WearableMethod.STEPS_RECORD)
        val selected = CheckInIntervalWearables.forInterval(start, end, listOf(partial))
        assertEquals(listOf(partial.id), selected.map { it.id })
    }

    @Test
    fun pointsInsideTheIntervalAreLabeledAndTheEndIsExcluded() {
        val resting = point(
            metric = WearableMetric.RESTING_HEART_RATE,
            method = WearableMethod.RESTING_HEART_RATE_RECORD,
            at = 2_000L,
            value = 58.0,
        )
        val sample = point(
            metric = WearableMetric.HEART_RATE_SAMPLE,
            method = WearableMethod.HEART_RATE_SAMPLE,
            at = 3_000L,
            value = 72.0,
        )
        val atEnd = point(
            metric = WearableMetric.HEART_RATE_SAMPLE,
            method = WearableMethod.HEART_RATE_SAMPLE,
            at = end,
            value = 90.0,
        )
        val before = point(
            metric = WearableMetric.RESTING_HEART_RATE,
            method = WearableMethod.RESTING_HEART_RATE_RECORD,
            at = 500L,
            value = 50.0,
        )
        val average = heartAverage(start, end, 64.0)
        val wideAverage = heartAverage(0L, 8_000L, 70.0)
        val selected = CheckInIntervalWearables.forInterval(
            start,
            end,
            listOf(wideAverage, atEnd, sample, before, average, resting),
        )
        assertEquals(
            listOf(WearableMetric.RESTING_HEART_RATE, WearableMetric.HEART_RATE_AVERAGE, WearableMetric.HEART_RATE_SAMPLE),
            selected.map { it.metric },
        )
        assertEquals("58 bpm", historyWearableAmount(selected[0]))
        assertEquals("64 bpm", historyWearableAmount(selected[1]))
        assertEquals("72 bpm", historyWearableAmount(selected[2]))
        assertSame(resting, selected[0])
        assertTrue(selected.none { it.id == wideAverage.id || it.id == atEnd.id || it.id == before.id })
    }

    @Test
    fun severalRestingReadingsInOneIntervalShowTheLatestOnce() {
        val earlier = resting(at = 2_000L, value = 60.0, recordId = "yesterday")
        val later = resting(at = 4_000L, value = 62.0, recordId = "today")
        val sameTime = resting(at = 4_000L, value = 62.0, recordId = "today-again")
        val average = heartAverage(start, end, 70.0)
        val selected = CheckInIntervalWearables.forInterval(
            start,
            end,
            listOf(earlier, sameTime, later, average),
        )
        assertEquals(
            listOf(WearableMetric.RESTING_HEART_RATE, WearableMetric.HEART_RATE_AVERAGE),
            selected.map { it.metric },
        )
        assertEquals(1, selected.count { it.metric == WearableMetric.RESTING_HEART_RATE })
        assertEquals(62.0, selected.first().value, 0.0)
        assertTrue(selected.first().sourceRecordId in setOf("today", "today-again"))
        assertSame(average, selected[1])
    }

    @Test
    fun missingBoundsOrNoContainedRowsLeaveTheCheckInUntouched() {
        val steps = steps(start, end, 10.0, WearableMethod.AGGREGATE_TOTAL)
        assertTrue(CheckInIntervalWearables.forInterval(null, end, listOf(steps)).isEmpty())
        assertTrue(CheckInIntervalWearables.forInterval(end, end, listOf(steps)).isEmpty())
        assertTrue(CheckInIntervalWearables.forInterval(start, end, emptyList()).isEmpty())
        val sleep = WearableObservation.create(
            metric = WearableMetric.SLEEP_DURATION,
            value = 7.5,
            unit = WearableUnit.HOURS,
            observedStartAt = start,
            observedEndAt = end,
            temporalKind = WearableTemporalKind.MEASURED_INTERVAL,
            method = WearableMethod.SLEEP_SESSION,
            importedAt = end,
        )
        assertTrue(CheckInIntervalWearables.forInterval(start, end, listOf(sleep)).isEmpty())
    }

    private fun steps(start: Long, end: Long, value: Double, method: String) = WearableObservation.create(
        metric = WearableMetric.STEPS,
        value = value,
        unit = WearableUnit.COUNT,
        observedStartAt = start,
        observedEndAt = end,
        temporalKind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = method,
        importedAt = end,
    )

    private fun heartAverage(start: Long, end: Long, value: Double) = WearableObservation.create(
        metric = WearableMetric.HEART_RATE_AVERAGE,
        value = value,
        unit = WearableUnit.BEATS_PER_MINUTE,
        observedStartAt = start,
        observedEndAt = end,
        temporalKind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = WearableMethod.HEART_RATE_AVERAGE,
        importedAt = end,
    )

    private fun resting(at: Long, value: Double, recordId: String) = point(
        metric = WearableMetric.RESTING_HEART_RATE,
        method = WearableMethod.RESTING_HEART_RATE_RECORD,
        at = at,
        value = value,
        recordId = recordId,
    )

    private fun point(
        metric: String,
        method: String,
        at: Long,
        value: Double,
        recordId: String = "$metric-$at",
    ) = WearableObservation.create(
        metric = metric,
        value = value,
        unit = WearableUnit.BEATS_PER_MINUTE,
        observedStartAt = at,
        observedEndAt = at,
        temporalKind = WearableTemporalKind.POINT,
        method = method,
        sourceRecordId = recordId,
        importedAt = at,
    )
}
