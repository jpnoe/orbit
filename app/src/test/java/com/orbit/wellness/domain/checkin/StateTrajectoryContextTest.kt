package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StateTrajectoryContextTest {
    @Test
    fun aDeclineDoesNotAbsorbTheLaterRecovery() {
        val before = context(recoveryDataset(finalMood = null))
        val recovered = context(recoveryDataset(finalMood = 5))
        val changed = context(recoveryDataset(finalMood = 9))
        assertEquals(before.context("low"), recovered.context("low"))
        assertEquals(recovered.context("low"), changed.context("low"))
        assertEquals(before.context("high"), changed.context("high"))
        val low = recovered.context("low").axis(DefaultCheckInAxes.MOOD)
        assertEquals(0, low.currentRaw)
        assertEquals(5, low.previousRaw)
        assertEquals(-5, low.incomingDelta)
        assertEquals(5, low.absoluteIncomingDelta)
        assertEquals(0, low.floorDistance)
        assertEquals(10, low.ceilingDistance)
        assertEquals(true, low.atFloor)
        assertEquals(false, low.atCeiling)
        assertEquals(-5.0, low.deviationFromDay!!, 0.0)
        assertEquals(StandardizationGap.INSUFFICIENT_SUPPORT, low.dayStandardizationGap)
        assertNull(low.standardizedDayDeviation)
        assertEquals(listOf("high"), recovered.context("low").resolvedPredecessorKeys.map { it.raw })
        assertEquals(1, recovered.context("low").trailingDay.stateCount)
        assertNull(low.acceleration)
        assertEquals(DeltaDirection.FALLING, low.direction)
        val ending = recovered.context("recovered").axis(DefaultCheckInAxes.MOOD)
        assertEquals(5, ending.currentRaw)
        assertEquals(5, ending.incomingDelta)
        assertEquals(10, ending.acceleration)
        assertEquals(listOf("low", "high"), recovered.context("recovered").resolvedPredecessorKeys.map { it.raw })
        val anxiety = recovered.context("low").axis(DefaultCheckInAxes.ANXIETY)
        assertEquals(6, anxiety.incomingDelta)
    }

    @Test
    fun directionRunsUseExplicitDeltasOnly() {
        val rising = context(sequenceDataset(listOf(1, 2, 3, 4))).context("s3").axis(DefaultCheckInAxes.MOOD)
        assertEquals(DeltaDirection.RISING, rising.direction)
        assertEquals(3, rising.sameDirectionRun)
        assertEquals(3, rising.sameDirectionNetChange)
        assertEquals(0, rising.acceleration)
        val falling = context(sequenceDataset(listOf(8, 6, 3))).context("s2").axis(DefaultCheckInAxes.MOOD)
        assertEquals(DeltaDirection.FALLING, falling.direction)
        assertEquals(-3, falling.incomingDelta)
        assertEquals(-2, falling.precedingDelta)
        assertEquals(-1, falling.acceleration)
        assertEquals(2, falling.sameDirectionRun)
        assertEquals(-5, falling.sameDirectionNetChange)
        val flat = context(sequenceDataset(listOf(5, 5, 5))).context("s2").axis(DefaultCheckInAxes.MOOD)
        assertEquals(DeltaDirection.FLAT, flat.direction)
        assertEquals(0, flat.incomingDelta)
        assertEquals(2, flat.sameDirectionRun)
        assertEquals(0, flat.sameDirectionNetChange)
        val accelerated = context(sequenceDataset(listOf(0, 1, 4))).context("s2").axis(DefaultCheckInAxes.MOOD)
        assertEquals(3, accelerated.incomingDelta)
        assertEquals(1, accelerated.precedingDelta)
        assertEquals(2, accelerated.acceleration)
        val zero = context(sequenceDataset(listOf(2, 0))).context("s1").axis(DefaultCheckInAxes.MOOD)
        assertEquals(0, zero.currentRaw)
        assertEquals(true, zero.atFloor)
        assertEquals(0, zero.floorDistance)
        assertEquals(2, zero.previousRaw)
        assertEquals(-2, zero.incomingDelta)
    }

    @Test
    fun aMissingAxisStopsThatAxisWithoutChangingTheOthers() {
        val result = context(partialDataset())
        val mood = result.context("s3").axis(DefaultCheckInAxes.MOOD)
        val energy = result.context("s3").axis(DefaultCheckInAxes.ENERGY)
        assertNull(mood.incomingDelta)
        assertNull(mood.precedingDelta)
        assertEquals(2, mood.thirdIncomingDelta)
        assertNull(mood.direction)
        assertNull(mood.sameDirectionRun)
        assertEquals(2, mood.usablePredecessorCount)
        assertEquals(1, energy.incomingDelta)
        assertEquals(1, energy.precedingDelta)
        assertEquals(1, energy.thirdIncomingDelta)
        assertEquals(3, energy.sameDirectionRun)
        assertNull(result.context("s2").axis(DefaultCheckInAxes.MOOD).currentRaw)
        assertNull(result.context("s2").axis(DefaultCheckInAxes.MOOD).atFloor)
    }

    @Test
    fun boundariesAndBrokenLinksStayOutOfOrderedHistory() {
        val wake = context(boundaryDataset()).context("wake")
        assertEquals(PathBreakReason.BOUNDARY, wake.pathBreak)
        assertTrue(wake.resolvedPredecessorKeys.isEmpty())
        assertNull(wake.axis(DefaultCheckInAxes.MOOD).previousRaw)
        assertEquals(9.0, wake.axis(DefaultCheckInAxes.MOOD).trailingDay.summary!!.mean, 0.0)
        val day = context(boundaryDataset()).context("day")
        assertEquals(PathBreakReason.BOUNDARY, day.pathBreak)
        assertNull(day.axis(DefaultCheckInAxes.MOOD).previousRaw)
        val unresolved = context(unresolvedDataset()).context("end")
        assertEquals(PathBreakReason.UNRESOLVED, unresolved.pathBreak)
        assertNull(unresolved.axis(DefaultCheckInAxes.MOOD).previousRaw)
        assertEquals(-3.0, unresolved.axis(DefaultCheckInAxes.MOOD).deviationFromDay!!, 0.0)
        val invalid = context(invalidDataset()).context("early")
        assertEquals(PathBreakReason.INVALID_CHRONOLOGY, invalid.pathBreak)
        assertNull(invalid.axis(DefaultCheckInAxes.MOOD).previousRaw)
        assertEquals(0, invalid.trailingDay.stateCount)
        val ambiguous = context(ambiguousDataset()).context("b")
        assertEquals(PathBreakReason.AMBIGUOUS_PREDECESSOR, ambiguous.pathBreak)
        assertNull(ambiguous.axis(DefaultCheckInAxes.MOOD).previousRaw)
    }

    @Test
    fun aBranchDoesNotEnterItsSiblingMomentum() {
        val result = context(branchDataset(siblingMood = 9))
        val changed = context(branchDataset(siblingMood = 1))
        assertEquals(result.context("left"), changed.context("left"))
        val left = result.context("left").axis(DefaultCheckInAxes.MOOD)
        val right = result.context("right").axis(DefaultCheckInAxes.MOOD)
        assertEquals(listOf("origin"), result.context("left").resolvedPredecessorKeys.map { it.raw })
        assertEquals(3, left.incomingDelta)
        assertEquals(8, right.incomingDelta)
        assertEquals(1, result.context("left").trailingDay.stateCount)
    }

    @Test
    fun equalTimestampsAreNotGivenAnOrder() {
        val result = context(equalTimeDataset())
        val first = result.context("aa")
        val second = result.context("bb")
        assertEquals(0, first.trailingDay.stateCount)
        assertEquals(0, second.trailingDay.stateCount)
        assertNull(first.axis(DefaultCheckInAxes.MOOD).previousRaw)
        assertNull(second.axis(DefaultCheckInAxes.MOOD).previousRaw)
        assertEquals(4, first.axis(DefaultCheckInAxes.MOOD).currentRaw)
        assertEquals(10, second.axis(DefaultCheckInAxes.MOOD).currentRaw)
        val linked = context(equalLinkedDataset()).context("later-key")
        assertEquals(PathBreakReason.INVALID_CHRONOLOGY, linked.pathBreak)
        assertNull(linked.axis(DefaultCheckInAxes.MOOD).previousRaw)
        assertEquals(0, linked.trailingDay.stateCount)
    }

    @Test
    fun trailingWindowsAndScalePositionStayRaw() {
        val result = context(baselineDataset())
        val focal = result.context("focal").axis(DefaultCheckInAxes.MOOD)
        assertEquals(1, focal.trailingDay.observedCount)
        assertEquals(4.0, focal.trailingDay.summary!!.mean, 0.0)
        assertEquals(2, focal.trailingWeek.observedCount)
        val week = checkNotNull(focal.trailingWeek.summary)
        assertEquals(7.0, week.mean, 0.0)
        assertEquals(3.0, week.deviation, 1e-9)
        assertEquals(0.0, focal.deviationFromDay!!, 0.0)
        assertEquals(-3.0, focal.deviationFromWeek!!, 1e-9)
        assertEquals(TrailingScale.DAY, result.context("focal").trailingDay.scale)
        assertEquals(result.context("focal").recordedAt - TrailingScale.DAY.duration.toMillis(), result.context("focal").trailingDay.windowStartAt)
        val edges = context(windowEdgeDataset())
        assertEquals(1, edges.context("focal").trailingDay.stateCount)
        assertEquals(2, edges.context("focal").trailingWeek.stateCount)
        val ceiling = context(sequenceDataset(listOf(10))).context("s0").axis(DefaultCheckInAxes.MOOD)
        assertEquals(0, ceiling.ceilingDistance)
        assertEquals(10, ceiling.floorDistance)
        assertEquals(true, ceiling.atCeiling)
        assertEquals(false, ceiling.atFloor)
        val flatBaseline = context(sequenceDataset(listOf(5, 5, 5))).context("s2").axis(DefaultCheckInAxes.MOOD)
        assertEquals(2, flatBaseline.trailingDay.observedCount)
        assertEquals(0.0, flatBaseline.deviationFromDay!!, 0.0)
        assertEquals(StandardizationGap.NEGLIGIBLE_VARIATION, flatBaseline.dayStandardizationGap)
        assertNull(flatBaseline.standardizedDayDeviation)
        val spread = context(sequenceDataset(listOf(0, 10, 0))).context("s2").axis(DefaultCheckInAxes.MOOD)
        assertEquals(-5.0, spread.deviationFromDay!!, 1e-9)
        assertEquals(-1.0, spread.standardizedDayDeviation!!, 1e-9)
        assertNull(spread.dayStandardizationGap)
    }

    @Test
    fun reversedInputAndFlagsStayStable() {
        val forward = context(sequenceDataset(listOf(1, 2, 3, 4)))
        val sourceDataset = sequenceDataset(listOf(1, 2, 3, 4))
        val reversed = context(
            sourceDataset.copy(
                states = sourceDataset.states.reversed(),
                intervals = sourceDataset.intervals.reversed(),
            ),
        )
        assertEquals(forward, reversed)
        assertEquals(STATE_TRAJECTORY_CONTEXT_PROTOCOL, forward.protocol)
        assertFalse(forward.causal || forward.predictive || forward.suitableForRecommendation)
        val text = source(
            "src/main/java/com/orbit/wellness/domain/checkin/StateTrajectoryContext.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/StateTrajectoryContext.kt",
        ).readText()
        assertFalse(text.contains("Dao"))
        assertFalse(text.contains("RoomDatabase"))
        assertFalse(text.contains("projectStateMap"))
        assertFalse(text.contains("projectMultiscale"))
        assertFalse(text.contains("isShock"))
        assertFalse(text.contains("rebound"))
        val fields = StateTrajectoryContext::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("behavior") || it.contains("predict") || it.contains("recommend") })
    }

    private fun context(dataset: CheckInAnalysisDatasetV2) = projectStateTrajectoryContext(dataset)

    private fun StateTrajectoryContextDataset.context(id: String) = contexts.single { it.stateKey.raw == id }

    private fun StateTrajectoryContext.axis(axisId: String) = axes.single { it.axisId == axisId }

    private fun recoveryDataset(finalMood: Int?): CheckInAnalysisDatasetV2 {
        val start = 20L * DAY
        val states = mutableListOf(
            state("high", start, mood = 5, anxiety = 2),
            state("low", start + HOUR, mood = 0, anxiety = 8),
        )
        val intervals = mutableListOf(linked("low", "high", start, start + HOUR))
        if (finalMood != null) {
            states += state("recovered", start + 2 * HOUR, mood = finalMood, anxiety = 8)
            intervals += linked("recovered", "low", start + HOUR, start + 2 * HOUR)
        }
        return dataset(states, intervals)
    }

    private fun sequenceDataset(moods: List<Int>): CheckInAnalysisDatasetV2 {
        val start = 30L * DAY
        val states = moods.mapIndexed { index, mood -> state("s$index", start + index * HOUR, mood = mood) }
        val intervals = moods.indices.drop(1).map { index ->
            linked("s$index", "s${index - 1}", start + (index - 1) * HOUR, start + index * HOUR)
        }
        return dataset(states, intervals)
    }

    private fun partialDataset(): CheckInAnalysisDatasetV2 {
        val start = 40L * DAY
        return dataset(
            states = listOf(
                state("s0", start, mood = 1, energy = 1),
                state("s1", start + HOUR, mood = 3, energy = 2),
                state("s2", start + 2 * HOUR, energy = 3),
                state("s3", start + 3 * HOUR, mood = 7, energy = 4),
            ),
            intervals = listOf(
                linked("s1", "s0", start, start + HOUR),
                linked("s2", "s1", start + HOUR, start + 2 * HOUR),
                linked("s3", "s2", start + 2 * HOUR, start + 3 * HOUR),
            ),
        )
    }

    private fun boundaryDataset(): CheckInAnalysisDatasetV2 {
        val start = 50L * DAY
        return dataset(
            states = listOf(
                state("before", start, mood = 9),
                state("wake", start + HOUR, mood = 3),
                state("day", start + 2 * HOUR, mood = 4),
            ),
            intervals = listOf(
                interval("wake", "before", start, start + HOUR, CheckInContextStartBasis.WAKE_TIME),
                interval("day", "before", start, start + 2 * HOUR, CheckInContextStartBasis.DAY_START),
            ),
        )
    }

    private fun unresolvedDataset(): CheckInAnalysisDatasetV2 {
        val start = 60L * DAY
        return dataset(
            states = listOf(
                state("earlier", start, mood = 4),
                state("end", start + HOUR, mood = 1),
            ),
            intervals = listOf(linked("end", "ghost", start, start + HOUR)),
        )
    }

    private fun invalidDataset(): CheckInAnalysisDatasetV2 {
        val start = 70L * DAY
        return dataset(
            states = listOf(
                state("early", start + HOUR, mood = 1),
                state("late", start + 5 * HOUR, mood = 8),
            ),
            intervals = listOf(linked("early", "late", start, start + HOUR)),
        )
    }

    private fun ambiguousDataset(): CheckInAnalysisDatasetV2 {
        val start = 80L * DAY
        return dataset(
            states = listOf(
                state("left-start", start, mood = 1),
                state("right-start", start + HOUR, mood = 9),
                state("b", start + 2 * HOUR, mood = 4),
            ),
            intervals = listOf(
                linked("b", "left-start", start, start + 2 * HOUR),
                linked("b", "right-start", start + HOUR, start + 2 * HOUR),
            ),
        )
    }

    private fun branchDataset(siblingMood: Int): CheckInAnalysisDatasetV2 {
        val start = 90L * DAY
        return dataset(
            states = listOf(
                state("origin", start, mood = 1),
                state("left", start + HOUR, mood = 4),
                state("right", start + 2 * HOUR, mood = siblingMood),
            ),
            intervals = listOf(
                linked("left", "origin", start, start + HOUR),
                linked("right", "origin", start, start + 2 * HOUR),
            ),
        )
    }

    private fun equalTimeDataset(): CheckInAnalysisDatasetV2 {
        val start = 100L * DAY
        return dataset(
            states = listOf(
                state("aa", start, mood = 4),
                state("bb", start, mood = 10),
            ),
            intervals = emptyList(),
        )
    }

    private fun equalLinkedDataset(): CheckInAnalysisDatasetV2 {
        val start = 110L * DAY
        return dataset(
            states = listOf(
                state("earlier-key", start, mood = 2),
                state("later-key", start, mood = 8),
            ),
            intervals = listOf(linked("later-key", "earlier-key", start - HOUR, start)),
        )
    }

    private fun baselineDataset(): CheckInAnalysisDatasetV2 {
        val start = 120L * DAY
        return dataset(
            states = listOf(
                state("week-only", start, mood = 10),
                state("recent", start + 30 * HOUR, mood = 4),
                state("focal", start + 31 * HOUR, mood = 4),
            ),
            intervals = listOf(
                linked("recent", "week-only", start, start + 30 * HOUR),
                linked("focal", "recent", start + 30 * HOUR, start + 31 * HOUR),
            ),
        )
    }

    private fun windowEdgeDataset(): CheckInAnalysisDatasetV2 {
        val focalAt = 130L * DAY
        val day = TrailingScale.DAY.duration.toMillis()
        return dataset(
            states = listOf(
                state("inside-week", focalAt - day - 1, mood = 2),
                state("on-day-edge", focalAt - day, mood = 6),
                state("focal", focalAt, mood = 1),
            ),
            intervals = emptyList(),
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
        confidence: Int? = null,
        anxiety: Int? = null,
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
            DefaultCheckInAxes.CONFIDENCE to confidence,
            DefaultCheckInAxes.ANXIETY to anxiety,
        ).mapNotNull { (axis, raw) -> reading(axis, raw) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.CONFIDENCE },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ANXIETY },
            ),
        )
    }

    private fun linked(end: String, previous: String, start: Long, endAt: Long) = interval(
        end,
        previous,
        start,
        endAt,
        CheckInContextStartBasis.PREVIOUS_CHECK_IN,
    )

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

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
