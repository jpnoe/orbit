package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrajectoryExpertTest {
    @Test
    fun immediateVelocityUsesElapsedHoursAndIrregularSpacingKeepsTheSlope() {
        val hour = 3_600_000L
        val experts = projectTrajectoryExperts(
            chain(
                point("a", 0, mood = 0),
                point("b", 2 * hour, mood = 2),
                point("c", 6 * hour, mood = 6),
            ),
        )
        val immediate = experts.mood("c", TrajectoryScaleId.IMMEDIATE)
        assertEquals(4, immediate.stepDelta)
        assertEquals(1.0, immediate.slopePerHour!!, 1e-9)
        assertEquals(listOf("b", "c"), immediate.occurrenceKeys.map { it.raw })
        val day = experts.mood("c", TrajectoryScaleId.HOURS_24)
        assertEquals(1.0, day.slopePerHour!!, 1e-9)
        assertEquals(0.0, day.fitResidualDeviation!!, 1e-9)
        assertEquals(listOf("a", "b", "c"), day.occurrenceKeys.map { it.raw })

        val irregular = fitTrajectoryTrend(
            listOf(
                TrendPoint(StateOccurrenceKey("a"), 0, 0),
                TrendPoint(StateOccurrenceKey("b"), hour, 0),
                TrendPoint(StateOccurrenceKey("c"), 3 * hour, 6),
            ),
        )
        assertEquals(15.0 / 7.0, irregular.slopePerHour!!, 1e-9)
        val sameTime = fitTrajectoryTrend(
            listOf(
                TrendPoint(StateOccurrenceKey("aa"), 5_000, 1),
                TrendPoint(StateOccurrenceKey("bb"), 5_000, 4),
            ),
        )
        assertEquals(TrajectoryExpertGap.ZERO_TIME_SPAN, sameTime.unavailable)
        assertNull(sameTime.slopePerHour)
    }

    @Test
    fun windowsUseTheResolvedPathAndHalfOpenBoundaries() {
        val hour = 3_600_000L
        val day = 24 * hour
        val focal = 10 * day
        val experts = projectTrajectoryExperts(
            chain(
                point("outside-week", focal - 7 * day - 1, mood = 1),
                point("week-edge", focal - 7 * day, mood = 1),
                point("day-edge", focal - day, mood = 1),
                point("outside-six", focal - 6 * hour - 1, mood = 1),
                point("six-edge", focal - 6 * hour, mood = 1),
                point("focal", focal, mood = 1),
                point("future", focal + hour, mood = 9),
            ),
        )
        assertEquals(listOf("six-edge", "focal"), experts.mood("focal", TrajectoryScaleId.HOURS_6).occurrenceKeys.map { it.raw })
        assertEquals(
            listOf("day-edge", "outside-six", "six-edge", "focal"),
            experts.mood("focal", TrajectoryScaleId.HOURS_24).occurrenceKeys.map { it.raw },
        )
        assertEquals(
            listOf("week-edge", "day-edge", "outside-six", "six-edge", "focal"),
            experts.mood("focal", TrajectoryScaleId.DAYS_7).occurrenceKeys.map { it.raw },
        )
        assertFalse(experts.mood("focal", TrajectoryScaleId.DAYS_7).occurrenceKeys.map { it.raw }.contains("future"))
        assertFalse(experts.mood("focal", TrajectoryScaleId.DAYS_7).occurrenceKeys.map { it.raw }.contains("outside-week"))
        val withLater = projectTrajectoryExperts(
            chain(
                point("outside-week", focal - 7 * day - 1, mood = 8),
                point("week-edge", focal - 7 * day, mood = 1),
                point("day-edge", focal - day, mood = 1),
                point("outside-six", focal - 6 * hour - 1, mood = 1),
                point("six-edge", focal - 6 * hour, mood = 1),
                point("focal", focal, mood = 1),
                point("future", focal + hour, mood = 3),
                point("beyond", focal + 2 * hour, mood = 7),
            ),
        )
        assertEquals(experts.mood("focal", TrajectoryScaleId.DAYS_7), withLater.mood("focal", TrajectoryScaleId.DAYS_7))
    }

    @Test
    fun missingAxesAndRecordedZeroStayDistinct() {
        val hour = 3_600_000L
        val experts = projectTrajectoryExperts(
            chain(
                point("a", 0, energy = 3),
                point("b", hour, mood = 0),
                point("c", 2 * hour, mood = 0),
            ),
        )
        val missing = experts.mood("b", TrajectoryScaleId.IMMEDIATE)
        assertEquals(TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS, missing.unavailable)
        assertEquals(listOf("b"), missing.occurrenceKeys.map { it.raw })
        assertNull(missing.stepDelta)
        val zero = experts.mood("c", TrajectoryScaleId.IMMEDIATE)
        assertNull(zero.unavailable)
        assertEquals(0, zero.stepDelta)
        assertEquals(0.0, zero.slopePerHour!!, 0.0)
        assertEquals(listOf("b", "c"), zero.occurrenceKeys.map { it.raw })
        val alone = projectTrajectoryExperts(chain(point("only", 0, mood = 4))).mood("only", TrajectoryScaleId.HOURS_6)
        assertEquals(TrajectoryExpertGap.NO_RESOLVED_PREDECESSOR, alone.unavailable)
        assertEquals(1, alone.observedCount)
    }

    @Test
    fun breaksAndSiblingBranchesStayOutOfThePath() {
        val hour = 3_600_000L
        val start = 40 * hour
        val boundary = projectTrajectoryExperts(
            dataset(
                states = listOf(
                    point("before", start, mood = 9),
                    point("wake", start + hour, mood = 3),
                    point("after", start + 2 * hour, mood = 5),
                ),
                intervals = listOf(
                    interval("wake", "before", start, start + hour, CheckInContextStartBasis.WAKE_TIME),
                    interval("after", "wake", start + hour, start + 2 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                ),
            ),
        )
        val after = boundary.mood("after", TrajectoryScaleId.DAYS_7)
        assertEquals(listOf("wake", "after"), after.occurrenceKeys.map { it.raw })
        assertEquals(PathBreakReason.BOUNDARY, boundary.mood("wake", TrajectoryScaleId.IMMEDIATE).pathBreak)
        assertEquals(TrajectoryExpertGap.BOUNDARY, boundary.mood("wake", TrajectoryScaleId.IMMEDIATE).unavailable)

        val unresolved = projectTrajectoryExperts(
            dataset(
                states = listOf(point("earlier", start, mood = 4), point("end", start + hour, mood = 1)),
                intervals = listOf(interval("end", "ghost", start, start + hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN)),
            ),
        ).mood("end", TrajectoryScaleId.DAYS_7)
        assertEquals(TrajectoryExpertGap.UNRESOLVED, unresolved.unavailable)
        assertFalse(unresolved.occurrenceKeys.map { it.raw }.contains("earlier"))

        val invalid = projectTrajectoryExperts(
            dataset(
                states = listOf(point("early", start + hour, mood = 1), point("late", start + 5 * hour, mood = 8)),
                intervals = listOf(interval("early", "late", start, start + hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN)),
            ),
        ).mood("early", TrajectoryScaleId.IMMEDIATE)
        assertEquals(TrajectoryExpertGap.INVALID_CHRONOLOGY, invalid.unavailable)
        assertFalse(invalid.occurrenceKeys.map { it.raw }.contains("late"))

        val ambiguous = projectTrajectoryExperts(
            dataset(
                states = listOf(
                    point("left-start", start, mood = 1),
                    point("right-start", start + hour, mood = 9),
                    point("b", start + 2 * hour, mood = 4),
                ),
                intervals = listOf(
                    interval("b", "left-start", start, start + 2 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                    interval("b", "right-start", start + hour, start + 2 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                ),
            ),
        ).mood("b", TrajectoryScaleId.IMMEDIATE)
        assertEquals(TrajectoryExpertGap.AMBIGUOUS_PREDECESSOR, ambiguous.unavailable)
        assertTrue(ambiguous.occurrenceKeys.none { it.raw == "left-start" || it.raw == "right-start" })

        val left = projectTrajectoryExperts(branch(9)).mood("left", TrajectoryScaleId.DAYS_7)
        val changed = projectTrajectoryExperts(branch(1)).mood("left", TrajectoryScaleId.DAYS_7)
        assertEquals(left, changed)
        assertEquals(listOf("origin", "left"), left.occurrenceKeys.map { it.raw })

        val equal = projectTrajectoryExperts(
            dataset(
                states = listOf(point("aa", start, mood = 2), point("bb", start, mood = 8)),
                intervals = listOf(interval("bb", "aa", start - hour, start, CheckInContextStartBasis.PREVIOUS_CHECK_IN)),
            ),
        )
        assertFalse(equal.mood("bb", TrajectoryScaleId.DAYS_7).occurrenceKeys.map { it.raw }.contains("aa"))
        assertFalse(equal.mood("aa", TrajectoryScaleId.DAYS_7).occurrenceKeys.map { it.raw }.contains("bb"))
    }

    @Test
    fun expertsAreNotBlendedOrWritten() {
        val names = TrajectoryExpert::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "serialVersionUID" }
        assertFalse(names.any { it.contains("weight", ignoreCase = true) })
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/TrajectoryExpert.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/TrajectoryExpert.kt",
        ).readText()
        assertFalse(source.contains("fun combine"))
        assertFalse(source.contains("neural", ignoreCase = true))
        assertFalse(source.contains("fitRetrospective"))
        assertFalse(source.contains("projectStateMap"))
        assertFalse(source.contains("projectMultiscale"))
        assertFalse(source.contains("Dao"))
        assertFalse(source.contains("RoomDatabase"))
        assertFalse(source.contains(".insert"))
        val replay = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText().substringAfter("fun RecordedReplayPane").substringBefore("fun PredictionSandboxPane")
        assertFalse(replay.contains("projectExpertHorizon"))
        assertFalse(replay.contains("horizonHours"))
        assertFalse(replay.contains("Slider"))
    }

    private fun TrajectoryExpertDataset.mood(id: String, scale: TrajectoryScaleId): TrajectoryExpert {
        return experts.single {
            it.stateKey.raw == id && it.axisId == DefaultCheckInAxes.MOOD && it.scale == scale
        }
    }

    private fun chain(vararg states: StateObservationV2): CheckInAnalysisDatasetV2 {
        val intervals = states.toList().zipWithNext().map { (previous, next) ->
            interval(
                next.checkInId,
                previous.checkInId,
                previous.recordedAt,
                next.recordedAt,
                CheckInContextStartBasis.PREVIOUS_CHECK_IN,
            )
        }
        return dataset(states.toList(), intervals)
    }

    private fun branch(siblingMood: Int): CheckInAnalysisDatasetV2 {
        val start = 90 * 3_600_000L
        return dataset(
            states = listOf(
                point("origin", start, mood = 1),
                point("left", start + 3_600_000L, mood = 4),
                point("right", start + 2 * 3_600_000L, mood = siblingMood),
            ),
            intervals = listOf(
                interval("left", "origin", start, start + 3_600_000L, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                interval("right", "origin", start, start + 2 * 3_600_000L, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
            ),
        )
    }

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun point(id: String, at: Long, mood: Int? = null, energy: Int? = null): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
        ).mapNotNull { (axis, raw) -> reading(axis, raw) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-11-08",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                null,
                null,
            ),
        )
    }

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = basis,
        completedAt = endAt,
        activity = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    )

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }
}
