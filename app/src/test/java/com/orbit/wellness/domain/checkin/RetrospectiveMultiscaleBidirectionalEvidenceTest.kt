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

class RetrospectiveMultiscaleBidirectionalEvidenceTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun theDefaultAuditSpansStayInOrderAndEverySpanRemainsVisible() {
        val start = 20L * DAY
        val view = project(
            dataset(
                states = listOf(state("focal", start, mood = 4)),
                intervals = emptyList(),
            ),
            "focal",
        )
        assertEquals(RETROSPECTIVE_MULTISCALE_BIDIRECTIONAL_EVIDENCE_PROTOCOL, view.protocol)
        assertEquals(CAUSAL_PREDECESSOR_NEIGHBORHOOD_PROTOCOL, view.predecessorProtocol)
        assertEquals(RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_PROTOCOL, view.successorProtocol)
        assertEquals(DEFAULT_BIDIRECTIONAL_AUDIT_SPANS, view.candidates.map { it.span })
        assertEquals(6, view.candidates.size)
        assertEquals(start, view.anchorAt)
        assertTrue(view.retrospective && view.containsFuture && view.containsOutcome)
        assertFalse(view.predictive || view.updatesModel || view.suitableForRecommendation || view.inputPastOnly)
        val label = bidirectionalAuditLabel(view)
        assertTrue(label.contains("Every span stays visible. No preferred scale."))
        assertFalse(label.contains("winner"))
        assertFalse(label.contains("coherence"))
        assertEquals(6, Regex("span back ").findAll(label).count())
    }

    @Test
    fun asymmetricBoundsKeepThePastStateAndLeaveTheLaterStateOutside() {
        val start = 30L * DAY
        val data = daysAround(start)
        val narrow = project(data, "focal", listOf(BidirectionalSpan(24L * HOUR, 6L * HOUR)))
        val wide = project(data, "focal", listOf(BidirectionalSpan(24L * HOUR, 24L * HOUR)))
        val shortPast = project(data, "focal", listOf(BidirectionalSpan(6L * HOUR, 6L * HOUR)))
        val narrowStates = narrow.candidates.single().segments.flatMap { it.statesInSpan }
        val wideStates = wide.candidates.single().segments.flatMap { it.statesInSpan }
        val shortStates = shortPast.candidates.single().segments.flatMap { it.statesInSpan }
        assertTrue(narrowStates.contains("back"))
        assertTrue(narrowStates.contains("soon"))
        assertFalse(narrowStates.contains("old"))
        assertFalse(narrowStates.contains("late"))
        assertTrue(wideStates.contains("late"))
        assertFalse(shortStates.contains("back"))
        assertEquals(24L * HOUR, narrow.candidates.single().span.backwardMillis)
        assertEquals(6L * HOUR, narrow.candidates.single().span.forwardMillis)
        assertEquals(start - 24L * HOUR, narrow.candidates.single().requestedStartAt)
        assertEquals(start + 6L * HOUR, narrow.candidates.single().requestedEndAt)
    }

    @Test
    fun leftAndRightCoverageAreIndependent() {
        val start = 40L * DAY
        val data = dataset(
            states = listOf(
                state("early", start - 2 * HOUR, mood = 1),
                state("focal", start, mood = 2),
                state("later", start + 48 * HOUR, mood = 3),
            ),
            intervals = listOf(
                linked("focal", "early", start - 2 * HOUR, start),
                linked("later", "focal", start, start + 48 * HOUR),
            ),
        )
        val span = project(data, "focal", listOf(BidirectionalSpan(24L * HOUR, 6L * HOUR))).candidates.single()
        assertTrue(span.leftTruncated)
        assertFalse(span.rightCensored)
        assertEquals(2.0 / 24.0, span.leftCoverage, 0.0)
        assertEquals(1.0, span.rightCoverage, 0.0)
        assertEquals(start - 2 * HOUR, span.effectiveStartAt)
        assertEquals(start + 6 * HOUR, span.effectiveEndAt)
    }

    @Test
    fun theForwardSideIsCensoredAtTheLatestCheckIn() {
        val start = 50L * DAY
        val data = dataset(
            states = listOf(
                state("focal", start, mood = 2),
                state("soon", start + 3 * HOUR, mood = 4),
            ),
            intervals = listOf(linked("soon", "focal", start, start + 3 * HOUR)),
        )
        val span = project(data, "focal", listOf(BidirectionalSpan(6L * HOUR, 24L * HOUR))).candidates.single()
        assertTrue(span.rightCensored)
        assertEquals(3.0 / 24.0, span.rightCoverage, 0.0)
        assertEquals(start + 3 * HOUR, span.effectiveEndAt)
        assertEquals(start + 24 * HOUR, span.requestedEndAt)
        assertTrue(span.segments.flatMap { it.statesInSpan }.contains("soon"))
        val successor = projectRetrospectiveSuccessorNeighborhood(data)
            .neighborhoods.single { it.transitionId == "soon" }
        val focalSegment = span.segments.single { it.rootStateId == "focal" }
        assertEquals(successor.stop.reason, focalSegment.futureReason)
        assertEquals(SuccessorStopReason.END_OF_RECORDED_HISTORY, focalSegment.futureReason)
    }

    @Test
    fun severalWakingSegmentsStaySeparateAcrossBoundaries() {
        val start = 60L * DAY
        val data = threeDays(start)
        val span = project(data, "d2a", DEFAULT_BIDIRECTIONAL_AUDIT_SPANS)
            .candidates.single { it.span == BidirectionalSpan(7L * DAY, 3L * DAY) }
        assertEquals(listOf("d1a", "d2a", "d3a"), span.segments.map { it.rootStateId })
        assertTrue(span.boundaryCount >= 2)
        val focal = span.segments.single { it.rootStateId == "d2a" }
        assertEquals(listOf("d2a"), focal.pastHops.map { it.transition.key.raw })
        assertEquals(PredecessorStopReason.BOUNDARY, focal.pastStop.reason)
        assertNull(focal.pastHops.single().predecessor)
        assertEquals(listOf("d2b", "d3a"), focal.futureHops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.BOUNDARY_REACHED, focal.futureReason)
        assertTrue(focal.futureHops.none { it.successor?.stateKey?.raw == "d3a" || it.successor?.stateKey?.raw == "d3b" })
        val nextDay = span.segments.single { it.rootStateId == "d3a" }
        assertTrue(nextDay.statesInSpan.contains("d3b"))
        assertFalse(focal.statesInSpan.contains("d3b"))
        val label = bidirectionalAuditLabel(project(data, "d2a", listOf(span.span)))
        assertTrue(label.contains("segment d1a"))
        assertTrue(label.contains("segment d2a"))
        assertTrue(label.contains("segment d3a"))
        assertTrue(label.contains("segments 3"))
    }

    @Test
    fun anAmbiguousForkIsNotAssignedToEitherBranch() {
        val start = 70L * DAY
        val data = dataset(
            states = listOf(
                state("origin", start, mood = 1),
                state("a", start + HOUR, mood = 2),
                state("next", start + 5 * HOUR, mood = 4),
                state("wake", start + 2 * HOUR, mood = 3),
            ),
            intervals = listOf(
                linked("a", "origin", start, start + HOUR),
                linked("next", "a", start + HOUR, start + 5 * HOUR),
                boundary("wake", "a", start + HOUR, start + 2 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            ),
        )
        val span = project(data, "a", listOf(BidirectionalSpan(DAY, DAY))).candidates.single()
        val fork = span.segments.single { it.futureReason == SuccessorStopReason.AMBIGUOUS_SUCCESSOR }
        assertEquals(listOf("a"), fork.futureHops.map { it.transition.key.raw })
        assertEquals(listOf("next", "wake"), fork.futureCandidates.map { it.transitionKey.raw })
        assertTrue(fork.futureHops.none { it.successor?.stateKey?.raw == "next" || it.successor?.stateKey?.raw == "wake" })
        val roots = span.segments.map { it.rootStateId }
        assertTrue(roots.contains("next"))
        assertTrue(roots.contains("wake"))
    }

    @Test
    fun aMissingFocalStaysAbsent() {
        val view = project(
            dataset(states = listOf(state("focal", DAY, mood = 1)), intervals = emptyList()),
            "missing",
        )
        assertNull(view.focal)
        assertTrue(view.candidates.isEmpty())
        assertEquals(bidirectionalMissingLabel(), bidirectionalAuditLabel(view))
    }

    @Test
    fun theViewStaysOutsideTheLockedPredictors() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveMultiscaleBidirectionalEvidence.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveMultiscaleBidirectionalEvidence.kt",
        ).readText()
        listOf(
            "projectNullTrajectoryLearner",
            "projectPredecessorContextLearner",
            "projectCausalFeatureEncoder",
            "projectCausalWalkForwardPreparation",
            "Dao",
            ".insert",
        ).forEach { token -> assertFalse(token, source.contains(token)) }
        listOf(
            "src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/PredecessorContextLearner.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/PredecessorContextLearner.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/CausalFeatureEncoder.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/CausalFeatureEncoder.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/CausalWalkForwardPreparation.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/CausalWalkForwardPreparation.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/CausalPredecessorNeighborhood.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/CausalPredecessorNeighborhood.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveSuccessorNeighborhood.kt" to
                "app/src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveSuccessorNeighborhood.kt",
        ).forEach { (primary, fallback) ->
            val text = source(primary, fallback).readText()
            assertFalse(text.contains("retrospective-multiscale-bidirectional-evidence"))
        }
        val architecture = source("docs/architecture.md", "../docs/architecture.md").readText()
        assertTrue(architecture.contains("retrospective-multiscale-bidirectional-evidence-v1"))
    }

    private fun daysAround(start: Long) = dataset(
        states = listOf(
            state("old", start - 30 * HOUR, mood = 1),
            state("back", start - 12 * HOUR, mood = 2),
            state("focal", start, mood = 3),
            state("soon", start + 3 * HOUR, mood = 4),
            state("late", start + 20 * HOUR, mood = 5),
        ),
        intervals = listOf(
            linked("back", "old", start - 30 * HOUR, start - 12 * HOUR),
            linked("focal", "back", start - 12 * HOUR, start),
            linked("soon", "focal", start, start + 3 * HOUR),
            linked("late", "soon", start + 3 * HOUR, start + 20 * HOUR),
        ),
    )

    private fun threeDays(start: Long) = dataset(
        states = listOf(
            state("d1a", start, mood = 1),
            state("d1b", start + 2 * HOUR, mood = 2),
            state("d2a", start + DAY, mood = 3),
            state("d2b", start + DAY + 2 * HOUR, mood = 4),
            state("d3a", start + 2 * DAY, mood = 5),
            state("d3b", start + 2 * DAY + 2 * HOUR, mood = 6),
        ),
        intervals = listOf(
            linked("d1b", "d1a", start, start + 2 * HOUR),
            boundary("d2a", "d1b", start + 2 * HOUR, start + DAY, CheckInContextStartBasis.WAKE_TIME),
            linked("d2b", "d2a", start + DAY, start + DAY + 2 * HOUR),
            boundary("d3a", "d2b", start + DAY + 2 * HOUR, start + 2 * DAY, CheckInContextStartBasis.DAY_START),
            linked("d3b", "d3a", start + 2 * DAY, start + 2 * DAY + 2 * HOUR),
        ),
    )

    private fun project(
        dataset: CheckInAnalysisDatasetV2,
        focalStateId: String,
        spans: List<BidirectionalSpan> = DEFAULT_BIDIRECTIONAL_AUDIT_SPANS,
    ) = projectRetrospectiveMultiscaleBidirectionalEvidence(dataset, focalStateId, zone, spans)

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun state(
        id: String,
        at: Long,
        mood: Int?,
    ): StateObservationV2 {
        val reading = mood?.let { AxisReadingV2(DefaultCheckInAxes.MOOD, it, it / 10.0) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = listOfNotNull(reading).associateBy { it.axisId },
            core = CoreAxisViewV2(reading, null, null, null),
            conflictingAxisIds = emptyList(),
            emotion = "",
        )
    }

    private fun linked(end: String, previous: String, start: Long, endAt: Long) =
        interval(end, previous, start, endAt, CheckInContextStartBasis.PREVIOUS_CHECK_IN)

    private fun boundary(end: String, previous: String?, start: Long, endAt: Long, basis: String) =
        interval(end, previous, start, endAt, basis)

    private fun interval(end: String, previous: String?, start: Long, endAt: Long, basis: String) =
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
