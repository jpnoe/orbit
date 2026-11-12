package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class RetrospectiveStateShapeEvidenceTest {
    private val zone = ZoneId.of("UTC")
    private val wide = BidirectionalSpan(7L * DAY, 7L * DAY)

    @Test
    fun localSignsStayFlatWhileAcrossSegmentDirectionFollowsTheDays() {
        val flat = scale(wakingDays(List(5) { listOf(5, 5, 5) }, 2))
        val rise = scale(wakingDays(listOf(listOf(1, 1, 1), listOf(3, 3, 3), listOf(5, 5, 5), listOf(7, 7, 7), listOf(9, 9, 9)), 2))
        val fall = scale(wakingDays(listOf(listOf(9, 9, 9), listOf(7, 7, 7), listOf(5, 5, 5), listOf(3, 3, 3), listOf(1, 1, 1)), 2))
        assertEquals(5, flat.across.representativeCount)
        assertEquals(ExactSlopeSign.ZERO, flat.local.incomingSign)
        assertEquals(ExactSlopeSign.ZERO, flat.local.outgoingSign)
        assertEquals(0.0, checkNotNull(flat.across.betweenSegmentSlope), 0.0)
        assertEquals(0.0, checkNotNull(flat.across.fittedFirstToLastChange), 0.0)
        assertEquals(0.0, checkNotNull(flat.across.fitError), 0.0)
        assertEquals(0.0, checkNotNull(flat.local.focalDistance), 0.0)
        assertEquals(0.0, checkNotNull(flat.local.trendResidual), 0.0)
        assertTrue(checkNotNull(rise.across.betweenSegmentSlope) > 0.0)
        assertTrue(checkNotNull(rise.across.fittedFirstToLastChange) > 0.0)
        assertTrue(checkNotNull(fall.across.betweenSegmentSlope) < 0.0)
        assertTrue(checkNotNull(fall.across.fittedFirstToLastChange) < 0.0)
        assertTrue(checkNotNull(fall.across.betweenSegmentSlope) < checkNotNull(flat.across.betweenSegmentSlope))
        assertTrue(checkNotNull(flat.across.betweenSegmentSlope) < checkNotNull(rise.across.betweenSegmentSlope))
        assertEquals(ExactSlopeSign.ZERO, rise.local.incomingSign)
        assertEquals(ExactSlopeSign.ZERO, fall.local.outgoingSign)
        val riseSteps = checkNotNull(rise.across.adjacentDirection)
        val fallSteps = checkNotNull(fall.across.adjacentDirection)
        assertEquals(riseSteps.comparableSteps, riseSteps.agreeingSteps)
        assertEquals(fallSteps.comparableSteps, fallSteps.agreeingSteps)
        assertTrue(rise.across.turnsInspected && rise.across.turns.isEmpty())
        assertTrue(fall.across.turnsInspected && fall.across.turns.isEmpty())
    }

    @Test
    fun valleysAndPeaksListEveryOppositeArmWithoutChoosingOne() {
        val valley = scale(wakingDays(listOf(listOf(8, 8, 8), listOf(6, 6, 6), listOf(4, 4, 4), listOf(6, 6, 6), listOf(8, 8, 8)), 2))
        val peak = scale(wakingDays(listOf(listOf(2, 2, 2), listOf(4, 4, 4), listOf(8, 8, 8), listOf(4, 4, 4), listOf(2, 2, 2)), 2))
        assertEquals(0.0, checkNotNull(valley.across.betweenSegmentSlope), 0.0)
        assertEquals(0.0, checkNotNull(peak.across.betweenSegmentSlope), 0.0)
        assertTrue(checkNotNull(valley.across.fitError) > 0.0)
        assertTrue(checkNotNull(peak.across.fitError) > 0.0)
        assertTrue(valley.across.turns.size >= 2)
        assertTrue(peak.across.turns.size >= 2)
        assertTrue(valley.across.turns.all { it.leftSlope < 0.0 && it.rightSlope > 0.0 })
        assertTrue(peak.across.turns.all { it.leftSlope > 0.0 && it.rightSlope < 0.0 })
        assertTrue(valley.across.turns.all { it.leftArmCount >= 2 && it.rightArmCount >= 2 })
        assertEquals(ExactSlopeSign.ZERO, valley.local.incomingSign)
        assertEquals(ExactSlopeSign.ZERO, valley.local.outgoingSign)
    }

    @Test
    fun anIsolatedShockStaysLocalWhileSegmentLevelsStayLevel() {
        val calm = scale(wakingDays(List(5) { listOf(5, 5, 5) }, 2))
        val shock = scale(
            wakingDays(
                listOf(listOf(5, 5, 5), listOf(5, 5, 5), listOf(5, 5, 10, 5, 5), listOf(5, 5, 5), listOf(5, 5, 5)),
                2,
            ),
        )
        assertTrue(checkNotNull(shock.local.focalDistance) > checkNotNull(calm.local.focalDistance))
        assertTrue(checkNotNull(shock.local.trendResidual) > 0.0)
        assertEquals(ExactSlopeSign.POSITIVE, shock.local.incomingSign)
        assertEquals(ExactSlopeSign.NEGATIVE, shock.local.outgoingSign)
        assertEquals(checkNotNull(calm.across.betweenSegmentSlope), checkNotNull(shock.across.betweenSegmentSlope), 0.0)
        assertEquals(checkNotNull(calm.across.balancedCenter), checkNotNull(shock.across.balancedCenter), 0.0)
        assertEquals(0.0, checkNotNull(shock.across.balancedSpread), 0.0)
        assertTrue(shock.across.turns.isEmpty())
    }

    @Test
    fun unevenLoggingDoesNotMoveTheSegmentBalancedEvidence() {
        val span = BidirectionalSpan(7L * DAY, 3L * DAY)
        val even = scale(wakingDays(listOf(listOf(4, 6), listOf(5, 5), listOf(4, 6)), 1), span)
        val uneven = scale(wakingDays(listOf(listOf(4, 6), listOf(5, 5), listOf(1, 1, 1, 9, 9, 9)), 1), span)
        assertEquals(3, even.across.representativeCount)
        assertEquals(even.across.representativeCount, uneven.across.representativeCount)
        assertTrue(uneven.local.recordedStateCount > even.local.recordedStateCount)
        assertEquals(checkNotNull(even.across.balancedCenter), checkNotNull(uneven.across.balancedCenter), 0.0)
        assertEquals(checkNotNull(even.across.balancedSpread), checkNotNull(uneven.across.balancedSpread), 0.0)
        assertEquals(checkNotNull(even.across.betweenSegmentSlope), checkNotNull(uneven.across.betweenSegmentSlope), 0.0)
        assertEquals(checkNotNull(even.across.fittedFirstToLastChange), checkNotNull(uneven.across.fittedFirstToLastChange), 0.0)
        assertFalse(even.across.turnsInspected)
        assertFalse(uneven.across.turnsInspected)
    }

    @Test
    fun theEvidenceCopiesDiagnosticReferencesWithoutRefittingThem() {
        val series = wakingDays(List(5) { listOf(5, 5, 5) }, 2)
        val diagnostics = projectRetrospectiveStateShapeDiagnostics(series.first, series.second, zone, listOf(wide))
        val axis = diagnostics.candidates.single().axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        val copied = scale(series)
        same(axis.inclusive.incomingSlope, copied.local.incomingSlope)
        same(axis.inclusive.outgoingSlope, copied.local.outgoingSlope)
        same(axis.leaveOneOut.stateMedian, copied.local.leaveOneOutMedian)
        same(axis.leaveOneOut.stateMad, copied.local.leaveOneOutMad)
        same(axis.leaveOneOut.distance, copied.local.focalDistance)
        same(axis.leaveOneOut.trendResidual, copied.local.trendResidual)
        same(axis.inclusive.balancedMedian, copied.across.balancedCenter)
        same(axis.inclusive.medianSpread, copied.across.balancedSpread)
    }

    @Test
    fun displayValuesAreFormattedOnce() {
        assertEquals("2", formatShapeEvidenceValue(2.0))
        assertEquals("2.5", formatShapeEvidenceValue(2.5))
        assertEquals("0.39", formatShapeEvidenceValue(0.39))
        assertEquals("3.75", formatShapeEvidenceValue(3.75))
        assertEquals("-3.8", formatShapeEvidenceValue(-3.8))
        assertEquals("0", formatShapeEvidenceValue(0.0))
        assertEquals("—", formatShapeEvidenceValue(null))
    }

    @Test
    fun theAuditTablesKeepScalesAsRowsAndLeaveMissingAxesBlank() {
        val series = wakingDays(listOf(listOf(8, 8, 8), listOf(6, 6, 6), listOf(4, 4, 4), listOf(6, 6, 6), listOf(8, 8, 8)), 2)
        val view = projectRetrospectiveStateShapeEvidence(
            projectRetrospectiveStateShapeDiagnostics(series.first, series.second, zone, listOf(wide)),
        )
        val energy = view.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.scales.single()
        assertNull(energy.local.focalValue)
        assertNull(energy.across.betweenSegmentSlope)
        assertEquals(ExactSlopeSign.UNAVAILABLE, energy.local.incomingSign)
        val table = stateShapeEvidenceTable(view)
        assertTrue(table.contains("local"))
        assertTrue(table.contains("across"))
        assertTrue(table.contains("trend residual"))
        assertTrue(table.contains("Legend"))
        assertTrue(table.contains("—"))
        assertTrue(table.contains("7d/7d"))
        assertFalse(table.contains("winner"))
        assertFalse(table.contains("support"))
        assertFalse(table.contains("arc"))
        assertFalse(table.contains(series.first.states.first().recordedAt.toString()))
    }

    @Test
    fun aMissingFocalStaysAbsent() {
        val series = wakingDays(listOf(listOf(5, 5, 5)), 0)
        val view = projectRetrospectiveStateShapeEvidence(
            projectRetrospectiveStateShapeDiagnostics(series.first, "missing", zone, listOf(wide)),
        )
        assertTrue(view.axes.isEmpty())
        assertNull(view.anchorAt)
        assertEquals(stateShapeEvidenceMissingLabel(), stateShapeEvidenceTable(view))
    }

    @Test
    fun theEvidenceStaysDownstreamOfTheDiagnostics() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveStateShapeEvidence.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveStateShapeEvidence.kt",
        ).readText()
        listOf(
            "projectRetrospectiveStateShapeDiagnostics",
            "projectRetrospectiveMultiscaleBidirectionalEvidence",
            "projectNullTrajectoryLearner",
            "projectPredecessorContextLearner",
            "projectCausalFeatureEncoder",
            "projectCausalWalkForwardPreparation",
            "transitionKey",
            "canonicalId",
            "Dao",
            ".insert",
        ).forEach { token -> assertFalse(token, source.contains(token)) }
        assertTrue(source.contains("trendResidual"))
        assertTrue(source.contains("medianRecordedAtMillis"))
        assertTrue(source.contains("betweenSegmentSlope"))
        listOf(
            "src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/PredecessorContextLearner.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/PredecessorContextLearner.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/CausalFeatureEncoder.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/CausalFeatureEncoder.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/CausalWalkForwardPreparation.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/CausalWalkForwardPreparation.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveStateShapeDiagnostics.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveStateShapeDiagnostics.kt",
        ).forEach { (primary, fallback) ->
            assertFalse(source(primary, fallback).readText().contains("retrospective-state-shape-evidence"))
        }
        val architecture = source("docs/architecture.md", "../docs/architecture.md").readText()
        assertTrue(architecture.contains("retrospective-state-shape-evidence-v1"))
        assertTrue(architecture.contains("retrospective-state-shape-diagnostics-v1"))
        assertTrue(architecture.contains("bidirectional-smoothed-state"))
    }

    private fun scale(
        series: Pair<CheckInAnalysisDatasetV2, String>,
        span: BidirectionalSpan = wide,
    ): ScaleShapeEvidence {
        val diagnostics = projectRetrospectiveStateShapeDiagnostics(series.first, series.second, zone, listOf(span))
        return projectRetrospectiveStateShapeEvidence(diagnostics)
            .axes
            .single { it.axisId == DefaultCheckInAxes.MOOD }
            .scales
            .single()
    }

    private fun same(expected: Double?, actual: Double?) {
        if (expected == null) {
            assertNull(actual)
        } else {
            assertEquals(expected, checkNotNull(actual), 0.0)
        }
    }

    private fun wakingDays(dayValues: List<List<Int>>, focalDay: Int): Pair<CheckInAnalysisDatasetV2, String> {
        val start = 90L * DAY
        val states = mutableListOf<StateObservationV2>()
        val intervals = mutableListOf<ContextIntervalObservationV2>()
        var previousId: String? = null
        var previousAt = 0L
        dayValues.forEachIndexed { day, values ->
            val dayStart = start + day * DAY
            values.forEachIndexed { index, mood ->
                val id = "d${day}p$index"
                val at = dayStart + index * HOUR
                states += state(id, at, mood)
                val earlierId = previousId
                if (earlierId != null) {
                    intervals += if (index == 0) {
                        boundary(id, earlierId, previousAt, at, CheckInContextStartBasis.WAKE_TIME)
                    } else {
                        linked(id, earlierId, previousAt, at)
                    }
                }
                previousId = id
                previousAt = at
            }
        }
        val focalIndex = dayValues[focalDay].size / 2
        return dataset(states, intervals) to "d${focalDay}p$focalIndex"
    }

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun state(id: String, at: Long, mood: Int): StateObservationV2 {
        val reading = AxisReadingV2(DefaultCheckInAxes.MOOD, mood, mood / 10.0)
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = mapOf(reading.axisId to reading),
            core = CoreAxisViewV2(reading, null, null, null),
            conflictingAxisIds = emptyList(),
            emotion = "",
        )
    }

    private fun linked(end: String, previous: String, start: Long, endAt: Long) =
        interval(end, previous, start, endAt, CheckInContextStartBasis.PREVIOUS_CHECK_IN)

    private fun boundary(end: String, previous: String, start: Long, endAt: Long, basis: String) =
        interval(end, previous, start, endAt, basis)

    private fun interval(end: String, previous: String, start: Long, endAt: Long, basis: String) =
        ContextIntervalObservationV2(
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
