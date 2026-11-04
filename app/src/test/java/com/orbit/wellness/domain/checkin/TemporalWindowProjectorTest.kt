package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.ui.insights.InsightsPhase
import com.orbit.wellness.ui.insights.toInsightsContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TemporalWindowProjectorTest {
    @Test
    fun stateAtTheStartIsIncludedAndStateAtTheEndIsExcluded() {
        val dataset = dataset(
            states = listOf(
                state("start", 1_000),
                state("inside", 1_999),
                state("end", 2_000),
            ),
        )
        val observation = TemporalWindowProjector.project(dataset, window(1_000, 2_000))
        assertEquals(listOf("start", "inside"), observation.stateIds)
    }

    @Test
    fun intervalsAreContainedOverlappingOrOmittedWithoutSplittingContext() {
        val activities = listOf(
            ActivityObservationV2("walk", null, "Walk", 1),
            ActivityObservationV2("read", null, "Read", 2),
        )
        val spanning = interval("span", 500, 2_500, activities)
        val dataset = dataset(
            intervals = listOf(
                interval("inside", 1_200, 1_800),
                interval("cross-start", 500, 1_500),
                interval("cross-end", 1_500, 2_500),
                spanning,
                interval("before", 100, 500),
                interval("after", 2_000, 3_000),
                interval("touches-start", 0, 1_000),
            ),
        )
        val activitiesBefore = spanning.activity.activities
        val observation = TemporalWindowProjector.project(dataset, window(1_000, 2_000))

        assertEquals(listOf("inside"), observation.containedIntervalIds)
        assertEquals(listOf("cross-start", "span", "cross-end"), observation.overlappingIntervalIds)
        assertTrue("before" !in observation.containedIntervalIds)
        assertTrue("before" !in observation.overlappingIntervalIds)
        assertTrue("after" !in observation.overlappingIntervalIds)
        assertTrue("touches-start" !in observation.overlappingIntervalIds)
        assertSame(activitiesBefore, dataset.intervals.single { it.endingCheckInId == "span" }.activity.activities)
        assertEquals(listOf("Walk", "Read"), activitiesBefore.map { it.name })
        assertEquals(listOf("span"), observation.overlappingIntervalIds.filter { it == "span" })
    }

    @Test
    fun anIntervalEqualToTheWindowIsContained() {
        val dataset = dataset(intervals = listOf(interval("same", 1_000, 2_000)))
        val observation = TemporalWindowProjector.project(dataset, window(1_000, 2_000))
        assertEquals(listOf("same"), observation.containedIntervalIds)
        assertTrue(observation.overlappingIntervalIds.isEmpty())
    }

    @Test
    fun calendarDayBoundsFollowTheSuppliedZone() {
        val zone = ZoneId.of("America/Los_Angeles")
        val at = ZonedDateTime.of(2026, 11, 4, 7, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val dataset = dataset(states = listOf(state("s", at)))
        val losAngeles = TemporalWindowProjector.calendarDays(dataset, zone).single()
        val utc = TemporalWindowProjector.calendarDays(dataset, ZoneId.of("UTC")).single()
        assertEquals("calendar_day:America/Los_Angeles:2026-11-03", losAngeles.definition.id)
        assertEquals("calendar_day:UTC:2026-11-04", utc.definition.id)
        assertEquals(TemporalBoundaryBasisV1.CALENDAR_DAY, losAngeles.definition.boundaryBasis)
        val midnight = LocalDate.of(2026, 11, 4).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMidnight = LocalDate.of(2026, 11, 5).atStartOfDay(zone).toInstant().toEpochMilli()
        val day = TemporalWindowProjector.calendarDay(LocalDate.of(2026, 11, 4), zone)
        assertEquals(midnight, day.startAt)
        assertEquals(nextMidnight, day.endAt)
    }

    @Test
    fun aDaylightSavingTransitionUsesTheRealDuration() {
        val zone = ZoneId.of("America/Los_Angeles")
        val spring = TemporalWindowProjector.calendarDay(LocalDate.of(2026, 3, 8), zone)
        val autumn = TemporalWindowProjector.calendarDay(LocalDate.of(2026, 11, 1), zone)
        val ordinary = TemporalWindowProjector.calendarDay(LocalDate.of(2026, 1, 15), zone)
        assertEquals(23 * HOUR, spring.durationMillis)
        assertEquals(25 * HOUR, autumn.durationMillis)
        assertEquals(24 * HOUR, ordinary.durationMillis)
        assertEquals(spring.endAt, TemporalWindowProjector.calendarDay(LocalDate.of(2026, 3, 9), zone).startAt)
    }

    @Test
    fun rollingWindowsUseTheSameProjection() {
        val dataset = dataset(
            states = listOf(state("recent", 1_500), state("old", 100)),
            intervals = listOf(interval("inside", 1_200, 1_600)),
        )
        val explicit = TemporalWindowProjector.explicit(1_000, 2_000)
        val day = TemporalWindowProjector.rolling(2_000, Duration.ofMillis(1_000))
        val week = TemporalWindowProjector.rolling(2_000, Duration.ofDays(7))
        val explicitObservation = TemporalWindowProjector.project(dataset, explicit)
        val dayObservation = TemporalWindowProjector.project(dataset, day)
        val weekObservation = TemporalWindowProjector.project(dataset, week)

        assertEquals(TemporalWindowScaleV1.ROLLING, day.scale)
        assertEquals(TemporalBoundaryBasisV1.EXPLICIT, day.boundaryBasis)
        assertEquals(explicitObservation.stateIds, dayObservation.stateIds)
        assertEquals(explicitObservation.containedIntervalIds, dayObservation.containedIntervalIds)
        assertEquals(listOf("recent"), dayObservation.stateIds)
        assertEquals(listOf("old", "recent"), weekObservation.stateIds)
        assertEquals(listOf("inside"), weekObservation.containedIntervalIds)
    }

    @Test
    fun adjacentWindowsAreLinkedInChronologicalOrderOnly() {
        val zone = ZoneId.of("America/Los_Angeles")
        val first = TemporalWindowProjector.calendarDay(LocalDate.of(2026, 3, 8), zone)
        val second = TemporalWindowProjector.calendarDay(LocalDate.of(2026, 3, 9), zone)
        val third = TemporalWindowProjector.calendarDay(LocalDate.of(2026, 3, 10), zone)
        val links = TemporalWindowProjector.adjacentWindows(listOf(third, first, second))
        assertEquals(
            listOf(first.id to second.id, second.id to third.id),
            links.map { it.earlierWindowId to it.laterWindowId },
        )
        assertTrue(TemporalWindowProjector.adjacentWindows(listOf(first, third)).isEmpty())
        val overlap = TemporalWindowProjector.explicit(0, 2_000, id = "wide")
        val inner = TemporalWindowProjector.explicit(500, 700, id = "inner")
        assertTrue(TemporalWindowProjector.adjacentWindows(listOf(overlap, inner)).isEmpty())
    }

    @Test
    fun oneObservationRepresentsOneCalendarDayAndNoAdjacentPattern() {
        val zone = ZoneId.of("America/Los_Angeles")
        val at = ZonedDateTime.of(2026, 11, 4, 10, 15, 0, 0, zone).toInstant().toEpochMilli()
        val dataset = dataset(states = listOf(state("only", at)))
        val coverage = TemporalWindowProjector.calendarDayCoverage(dataset, zone)
        assertEquals(1, coverage.momentObservations)
        assertEquals(1, coverage.calendarDaysRepresented)
        assertEquals(0, coverage.fullyContainedIntervals)
        assertEquals(0, coverage.boundaryOverlappingIntervals)
        assertTrue(
            TemporalWindowProjector.adjacentWindows(coverage.windows.map { it.definition }).isEmpty(),
        )
        val snapshot = CheckInAnalysisSnapshot(dataset, summarizeCheckInAnalysis(dataset))
        val content = snapshot.toInsightsContent(zone)
        assertEquals(InsightsPhase.ONE_OBSERVATION, content.phase)
        assertEquals(1, content.momentObservations)
        assertEquals(1, content.calendarDaysRepresented)
        assertEquals(0, content.linkedTransitionCount)
        assertEquals(0, content.measuredTransitionCount)
    }

    @Test
    fun projectionDoesNotChangeTheSourceDataset() {
        val states = listOf(state("a", 1_500))
        val activities = listOf(ActivityObservationV2("walk", null, "Walk", 1))
        val intervals = listOf(interval("b", 500, 2_500, activities))
        val dataset = dataset(states = states, intervals = intervals)
        TemporalWindowProjector.project(dataset, window(1_000, 2_000))
        TemporalWindowProjector.calendarDayCoverage(dataset, ZoneId.of("UTC"))
        assertSame(states, dataset.states)
        assertSame(intervals, dataset.intervals)
        assertSame(activities, dataset.intervals.single().activity.activities)
        assertEquals(listOf("a"), dataset.states.map { it.checkInId })
        assertEquals(listOf("Walk"), dataset.intervals.single().activity.activities.map { it.name })
        assertTrue(dataset.transitions.isEmpty())
        assertTrue(dataset.issues.isEmpty())
    }

    private fun window(start: Long, end: Long) = TemporalWindowProjector.explicit(start, end)

    private fun dataset(
        states: List<StateObservationV2> = emptyList(),
        intervals: List<ContextIntervalObservationV2> = emptyList(),
    ) = CheckInAnalysisDatasetV2(
        states = states,
        intervals = intervals,
        transitions = emptyList(),
        issues = emptyList(),
    )

    private fun state(id: String, at: Long) = StateObservationV2(
        checkInId = id,
        recordedAt = at,
        localDate = "2026-11-04",
        axes = emptyMap(),
        core = CoreAxisViewV2(null, null, null, null),
    )

    private fun interval(
        id: String,
        start: Long,
        end: Long,
        activities: List<ActivityObservationV2> = emptyList(),
    ) = ContextIntervalObservationV2(
        endingCheckInId = id,
        previousCheckInId = null,
        windowStartAt = start,
        windowEndAt = end,
        durationMillis = end - start,
        startBasis = "wake_time",
        completedAt = null,
        activity = ActivityCategoryObservationV2(
            kind = if (activities.isEmpty()) {
                CategoryObservationKindV2.NONE
            } else {
                CategoryObservationKindV2.ENTRIES
            },
            reviewedAt = null,
            activities = activities,
        ),
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, null, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, null, emptyList()),
        usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, null, emptyList()),
    )

    private companion object {
        const val HOUR = 3_600_000L
    }
}
