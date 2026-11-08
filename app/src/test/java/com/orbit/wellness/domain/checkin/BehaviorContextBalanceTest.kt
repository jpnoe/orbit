package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.ui.insights.formatClockMinute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class BehaviorContextBalanceTest {
    @Test
    fun circularClockTreatsMidnightAsAdjacent() {
        assertEquals(60.0, circularClockGapMinutes(23 * 60 + 30, 30), 0.0)
        val across = circularClockSummary(listOf(23 * 60 + 30, 30))
        assertEquals(0.0, across!!.meanMinute, 1e-6)
        assertTrue(across.concentration > 0.9)
        val one = circularClockSummary(listOf(90))
        assertEquals(1.0, one!!.concentration, 1e-12)
        assertFalse(one.meanWeaklyRepresentative)
        assertNull(circularClockSummary(emptyList()))
        val spread = circularClockSummary(listOf(0, 6 * 60, 12 * 60, 18 * 60))
        assertEquals(0.0, spread!!.concentration, 1e-9)
        assertTrue(spread.meanWeaklyRepresentative)
        assertEquals("12:00 AM", formatClockMinute(0.0))
        assertEquals("11:30 PM", formatClockMinute((23 * 60 + 30).toDouble()))
        assertEquals("12:30 AM", formatClockMinute(30.0))
    }

    @Test
    fun localClockUsesTheSuppliedZoneAcrossDaylightSaving() {
        val zone = ZoneId.of("America/Los_Angeles")
        val before = ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, zone).toInstant().toEpochMilli()
        val after = ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(60.0 * 60 * 1000, (after - before).toDouble(), 0.0)
        assertEquals(90, localClockMinute(before, zone))
        assertEquals(210, localClockMinute(after, zone))
        val first = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, zone).withEarlierOffsetAtOverlap()
        val second = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, zone).withLaterOffsetAtOverlap()
        assertEquals(90, localClockMinute(first.toInstant().toEpochMilli(), zone))
        assertEquals(90, localClockMinute(second.toInstant().toEpochMilli(), zone))
        assertTrue(second.toInstant().toEpochMilli() - first.toInstant().toEpochMilli() == HOUR)
        val descriptor = balance(clockDataset(before, after), zone).intervals.single { it.key.raw == "end" }
        assertEquals(zone.id, descriptor.zoneId)
        assertEquals(90, descriptor.startClockMinute)
        assertEquals(210, descriptor.endClockMinute)
    }

    @Test
    fun midpointRequiresAWellOrderedWindow() {
        val ordered = balance(linkedDataset(startMood = 2, endMood = 8)).intervals.single { it.key.raw == "present" }
        val expected = localClockMinute(ordered.startAt + (ordered.endAt - ordered.startAt) / 2, ZoneOffset.UTC)
        assertEquals(expected, ordered.midpointClockMinute)
        assertEquals(expected, ordered.midpointClock?.minute)
        val reversed = balance(reversedWindowDataset()).intervals.single { it.key.raw == "reversed" }
        assertTrue(reversed.startAt > reversed.endAt)
        assertNull(reversed.midpointClockMinute)
        assertNull(reversed.midpointClock)
    }

    @Test
    fun wakeAnchorsStayStoredAndUnambiguous() {
        val zone = ZoneOffset.UTC
        val day = LocalDate.of(2026, 6, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val clear = balance(wakeDataset(day))
        val later = clear.intervals.single { it.key.raw == "later" }
        assertEquals(2.0, later.timeSinceWakeStartHours!!, 1e-9)
        assertEquals(3.0, later.timeSinceWakeEndHours!!, 1e-9)
        assertTrue(clear.wakeDiagnostics.isEmpty())
        val dayStart = balance(dayStartDataset(day)).intervals.single { it.key.raw == "later" }
        assertNull(dayStart.timeSinceWakeStartHours)
        assertNull(dayStart.timeSinceWakeEndHours)
        val stale = balance(staleWakeDataset(day)).intervals.single { it.key.raw == "next" }
        assertNull(stale.timeSinceWakeStartHours)
        val duplicate = balance(duplicateWakeDataset(day))
        assertTrue(duplicate.wakeDiagnostics.any { it.kind == WakeAnchorIssueKind.DUPLICATE_INSTANT })
        assertNull(duplicate.intervals.single { it.key.raw == "later" }.timeSinceWakeStartHours)
        val crossed = balance(crossedWakeDataset(day))
        val spanned = crossed.intervals.single { it.key.raw == "spanned" }
        assertNull(spanned.timeSinceWakeStartHours)
        assertNull(spanned.timeSinceWakeEndHours)
        assertTrue(crossed.wakeDiagnostics.any { it.kind == WakeAnchorIssueKind.INCOMPATIBLE_ENDPOINTS && it.keys.single().raw == "spanned" })
    }

    @Test
    fun summariesReuseAssociationClassesAndLeaveMissingAxesMissing() {
        val profile = balance(classifiedDataset()).profiles.single { it.identity.key == "option:opt-shower" }
        assertEquals(1, profile.present.intervalCount)
        assertEquals(1, profile.absent.intervalCount)
        assertEquals(1, profile.present.startClock!!.count)
        val presentStart = checkNotNull(profile.present.startClock)
        assertEquals(1.0, presentStart.concentration, 1e-12)
        assertEquals(10 * 60.0, presentStart.meanMinute, 1e-6)
        assertEquals(14 * 60.0, profile.absent.startClock!!.meanMinute, 1e-6)
        assertEquals(8.0, profile.present.startingAxes.single { it.axisId == DefaultCheckInAxes.ANXIETY }.summary!!.mean, 0.0)
        assertNull(profile.present.startingAxes.single { it.axisId == DefaultCheckInAxes.ENERGY }.summary)
        assertEquals(4.0, profile.present.startingAxes.single { it.axisId == DefaultCheckInAxes.MOOD }.summary!!.mean, 0.0)
        val pair = profile.pairs.single()
        assertEquals(0, pair.sharedStartingAxisCount)
        assertNull(pair.startingAxisRms)
        assertEquals(1, profile.nearest.single { it.measure == OppositeContextMeasure.STARTING_AXIS_RMS }.unavailablePresentCount)
        assertNull(profile.nearest.single { it.measure == OppositeContextMeasure.STARTING_AXIS_RMS }.distances)
    }

    @Test
    fun sharedAxesUseRawDifferencesAndIgnoreOutcomeChanges() {
        val low = balance(outcomeDataset(endMood = 1)).profiles.single()
        val high = balance(outcomeDataset(endMood = 9)).profiles.single()
        assertEquals(low.pairs, high.pairs)
        val pair = low.pairs.single()
        assertEquals(1, pair.sharedStartingAxisCount)
        assertEquals(6.0, pair.startingAxisRms!!, 1e-9)
        assertTrue(pair.endClockGapMinutes >= 0.0)
        val alone = balance(presentOnlyDataset()).profiles.single()
        alone.nearest.forEach { nearest ->
            assertNull(nearest.distances)
            assertEquals(1, nearest.unavailablePresentCount)
            assertTrue(nearest.matches.isEmpty())
        }
    }

    @Test
    fun nearestTiesBreakByIntervalTimeThenKey() {
        val nearest = balance(tieDataset()).profiles.single()
            .nearest.single { it.measure == OppositeContextMeasure.END_CLOCK }
        assertEquals("early", nearest.matches.single().absentKey.raw)
        assertEquals(60.0, nearest.matches.single().distance, 0.0)
        val sameEnd = balance(keyTieDataset()).profiles.single()
            .nearest.single { it.measure == OppositeContextMeasure.END_CLOCK }
        assertEquals("aa", sameEnd.matches.single().absentKey.raw)
    }

    @Test
    fun reversedInputsStayIdenticalAndMismatchesAreRejected() {
        val forward = projectBehavioralEvidence(classifiedDataset(), ZoneOffset.UTC)
        val associations = projectBehaviorAssociations(forward)
        val balance = projectBehaviorContextBalance(forward, associations, ZoneOffset.UTC)
        val reversedEvidence = forward.copy(
            catalog = forward.catalog.reversed(),
            intervals = forward.intervals.reversed(),
            calendarDays = forward.calendarDays.reversed(),
        )
        val reversedAssociations = associations.copy(
            profiles = associations.profiles.reversed().map { profile ->
                profile.copy(intervals = profile.intervals.reversed())
            },
        )
        assertEquals(balance, projectBehaviorContextBalance(reversedEvidence, reversedAssociations, ZoneOffset.UTC))
        val reversedSource = classifiedDataset().let { dataset ->
            dataset.copy(states = dataset.states.reversed(), intervals = dataset.intervals.reversed())
        }
        assertEquals(balance, balance(reversedSource))
        assertThrows(IllegalArgumentException::class.java) {
            projectBehaviorContextBalance(
                forward,
                associations.copy(profiles = associations.profiles.drop(1)),
                ZoneOffset.UTC,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            projectBehaviorContextBalance(forward, associations, ZoneId.of("America/Los_Angeles"))
        }
        assertEquals(BEHAVIOR_CONTEXT_BALANCE_PROTOCOL, balance.protocol)
        assertFalse(balance.causal || balance.predictive || balance.adjustedBehaviorEffect || balance.suitableForRecommendation)
        assertEquals(INTERVAL_MIDPOINT_LIMIT, balance.midpointLimit)
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/BehaviorContextBalance.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/BehaviorContextBalance.kt",
        ).readText()
        assertFalse(source.contains("Dao"))
        assertFalse(source.contains("RoomDatabase"))
        assertFalse(source.contains("projectStateMap"))
        assertFalse(source.contains("propensity"))
        assertFalse(source.contains("weighted"))
        assertFalse(source.contains("pValue"))
    }

    private fun balance(
        dataset: CheckInAnalysisDatasetV2,
        zone: ZoneId = ZoneOffset.UTC,
    ): BehaviorContextBalanceDataset {
        val evidence = projectBehavioralEvidence(dataset, zone)
        return projectBehaviorContextBalance(evidence, projectBehaviorAssociations(evidence), zone)
    }

    private fun clockDataset(start: Long, end: Long): CheckInAnalysisDatasetV2 {
        return dataset(
            states = listOf(state("end", end, mood = 3)),
            intervals = listOf(
                interval(
                    "end",
                    null,
                    start,
                    end,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
            ),
        )
    }

    private fun linkedDataset(startMood: Int, endMood: Int): CheckInAnalysisDatasetV2 {
        val start = LocalDate.of(2026, 6, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 10 * HOUR
        return dataset(
            states = listOf(
                state("start", start, mood = startMood),
                state("present", start + 2 * HOUR, mood = endMood),
            ),
            intervals = listOf(
                interval(
                    "present",
                    "start",
                    start,
                    start + 2 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
            ),
        )
    }

    private fun reversedWindowDataset(): CheckInAnalysisDatasetV2 {
        val start = 30L * DAY
        return dataset(
            states = listOf(
                state("reversed", start, mood = 1),
                state("future", start + 2 * HOUR, mood = 2),
            ),
            intervals = listOf(
                interval(
                    "reversed",
                    "future",
                    start + 2 * HOUR,
                    start,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
            ),
        )
    }

    private fun wakeDataset(day: Long) = dataset(
        states = listOf(
            state("wake", day + 8 * HOUR, mood = 2),
            state("later", day + 10 * HOUR, mood = 4),
        ),
        intervals = listOf(
            interval("wake", null, day + 7 * HOUR, day + 8 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            interval(
                "later",
                "wake",
                day + 9 * HOUR,
                day + 10 * HOUR,
                CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                activity = CategoryObservationKindV2.ENTRIES,
                activities = listOf(activity("shower", "opt-shower")),
            ),
        ),
    )

    private fun dayStartDataset(day: Long) = dataset(
        states = listOf(
            state("day", day + 8 * HOUR, mood = 2),
            state("later", day + 10 * HOUR, mood = 4),
        ),
        intervals = listOf(
            interval("day", null, day, day + 8 * HOUR, CheckInContextStartBasis.DAY_START),
            interval(
                "later",
                "day",
                day + 9 * HOUR,
                day + 10 * HOUR,
                CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                activity = CategoryObservationKindV2.ENTRIES,
                activities = listOf(activity("shower", "opt-shower")),
            ),
        ),
    )

    private fun staleWakeDataset(day: Long) = dataset(
        states = listOf(
            state("wake", day + 8 * HOUR, mood = 2),
            state("next", day + DAY + 10 * HOUR, mood = 4),
        ),
        intervals = listOf(
            interval("wake", null, day + 7 * HOUR, day + 8 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            interval(
                "next",
                null,
                day + DAY + 9 * HOUR,
                day + DAY + 10 * HOUR,
                CheckInContextStartBasis.DAY_START,
                activity = CategoryObservationKindV2.ENTRIES,
                activities = listOf(activity("shower", "opt-shower")),
            ),
        ),
    )

    private fun duplicateWakeDataset(day: Long) = dataset(
        states = listOf(
            state("wake-a", day + 8 * HOUR, mood = 2),
            state("wake-b", day + 9 * HOUR, mood = 3),
            state("later", day + 11 * HOUR, mood = 4),
        ),
        intervals = listOf(
            interval("wake-a", null, day + 7 * HOUR, day + 8 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            interval("wake-b", null, day + 7 * HOUR, day + 9 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            interval(
                "later",
                "wake-b",
                day + 10 * HOUR,
                day + 11 * HOUR,
                CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                activity = CategoryObservationKindV2.ENTRIES,
                activities = listOf(activity("shower", "opt-shower")),
            ),
        ),
    )

    private fun crossedWakeDataset(day: Long) = dataset(
        states = listOf(
            state("wake-a", day + 8 * HOUR, mood = 2),
            state("wake-b", day + 16 * HOUR, mood = 3),
            state("spanned", day + 17 * HOUR, mood = 4),
        ),
        intervals = listOf(
            interval("wake-a", null, day + 7 * HOUR, day + 8 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            interval("wake-b", null, day + 15 * HOUR, day + 16 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            interval(
                "spanned",
                "wake-a",
                day + 9 * HOUR,
                day + 17 * HOUR,
                CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                activity = CategoryObservationKindV2.ENTRIES,
                activities = listOf(activity("shower", "opt-shower")),
            ),
        ),
    )

    private fun classifiedDataset(): CheckInAnalysisDatasetV2 {
        val day = LocalDate.of(2026, 6, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return dataset(
            states = listOf(
                state("p-start", day + 10 * HOUR, mood = 4, anxiety = 8),
                state("present", day + 11 * HOUR, mood = 9),
                state("a-start", day + 14 * HOUR, energy = 6),
                state("absent", day + 15 * HOUR, mood = 1),
                state("secret", day + 18 * HOUR, mood = 0),
                state("open", day + 20 * HOUR, mood = 3),
            ),
            intervals = listOf(
                interval(
                    "present",
                    "p-start",
                    day + 10 * HOUR,
                    day + 11 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
                interval(
                    "absent",
                    "a-start",
                    day + 14 * HOUR,
                    day + 15 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                ),
                interval(
                    "secret",
                    null,
                    day + 17 * HOUR,
                    day + 18 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.UNREVIEWED,
                ),
                interval(
                    "open",
                    null,
                    day + 19 * HOUR,
                    day + 20 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    completed = false,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("open-shower", "opt-shower")),
                ),
            ),
        )
    }

    private fun outcomeDataset(endMood: Int): CheckInAnalysisDatasetV2 {
        val day = LocalDate.of(2026, 6, 16).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return dataset(
            states = listOf(
                state("p-start", day + 8 * HOUR, mood = 2),
                state("present", day + 9 * HOUR, mood = endMood),
                state("a-start", day + 12 * HOUR, mood = 8),
                state("absent", day + 13 * HOUR, mood = 1),
            ),
            intervals = listOf(
                interval(
                    "present",
                    "p-start",
                    day + 8 * HOUR,
                    day + 9 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
                interval(
                    "absent",
                    "a-start",
                    day + 12 * HOUR,
                    day + 13 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                ),
            ),
        )
    }

    private fun presentOnlyDataset(): CheckInAnalysisDatasetV2 {
        val day = LocalDate.of(2026, 6, 17).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return dataset(
            states = listOf(
                state("p-start", day + 8 * HOUR, mood = 2),
                state("present", day + 9 * HOUR, mood = 5),
            ),
            intervals = listOf(
                interval(
                    "present",
                    "p-start",
                    day + 8 * HOUR,
                    day + 9 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
            ),
        )
    }

    private fun tieDataset(): CheckInAnalysisDatasetV2 {
        val day = LocalDate.of(2026, 6, 18).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return dataset(
            states = listOf(
                state("present", day + 12 * HOUR, mood = 5),
                state("early", day + 11 * HOUR, mood = 1),
                state("late", day + 13 * HOUR, mood = 1),
            ),
            intervals = listOf(
                interval(
                    "present",
                    null,
                    day + 12 * HOUR,
                    day + 12 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    duration = 0L,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
                interval("early", null, day + 11 * HOUR, day + 11 * HOUR, CheckInContextStartBasis.DAY_START, duration = 0L),
                interval("late", null, day + 13 * HOUR, day + 13 * HOUR, CheckInContextStartBasis.DAY_START, duration = 0L),
            ),
        )
    }

    private fun keyTieDataset(): CheckInAnalysisDatasetV2 {
        val day = LocalDate.of(2026, 6, 19).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = day + 12 * HOUR
        return dataset(
            states = listOf(
                state("present", end, mood = 5),
                state("bb", end + HOUR, mood = 1),
                state("aa", end + 2 * HOUR, mood = 1),
            ),
            intervals = listOf(
                interval(
                    "present",
                    null,
                    end,
                    end,
                    CheckInContextStartBasis.WAKE_TIME,
                    duration = 0L,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
                interval("bb", null, end - HOUR, end, CheckInContextStartBasis.DAY_START),
                interval("aa", null, end - HOUR, end, CheckInContextStartBasis.DAY_START),
            ),
        )
    }

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun state(
        id: String,
        at: Long,
        mood: Int? = null,
        energy: Int? = null,
        anxiety: Int? = null,
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
            DefaultCheckInAxes.ANXIETY to anxiety,
        ).mapNotNull { (axis, raw) -> reading(axis, raw) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-06-15",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                null,
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ANXIETY },
            ),
        )
    }

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
        completed: Boolean = true,
        duration: Long = endAt - start,
        activity: CategoryObservationKindV2 = CategoryObservationKindV2.NONE,
        activities: List<ActivityObservationV2> = emptyList(),
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = duration,
        startBasis = basis,
        completedAt = if (completed) endAt else null,
        activity = ActivityCategoryObservationV2(activity, if (completed) endAt else null, activities),
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, if (completed) endAt else null, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, if (completed) endAt else null, emptyList()),
        usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, if (completed) endAt else null, emptyList()),
    )

    private fun activity(id: String, optionId: String) = ActivityObservationV2(id, optionId, optionId, 0L)

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24L * HOUR
    }
}
