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

class RetrospectiveStateShapeDiagnosticsTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun aFlatPlateauHasNoSlopeAndATighterFocalDistanceThanAPeak() {
        val plateau = ramp(listOf(5, 5, 5, 5, 5), listOf("calm", "calm", "", "glad", ""))
        val shock = ramp(listOf(5, 5, 10, 5, 5))
        val flat = mood(plateau)
        val peak = mood(shock)
        assertEquals(5.0, checkNotNull(flat.focalValue), 0.0)
        assertEquals(5.0, checkNotNull(flat.leaveOneOut.stateMedian), 0.0)
        assertEquals(0.0, checkNotNull(flat.leaveOneOut.stateMad), 0.0)
        assertEquals(0.0, checkNotNull(flat.leaveOneOut.distance), 0.0)
        assertEquals(0.0, checkNotNull(flat.inclusive.slope), 0.0)
        assertEquals(0.0, checkNotNull(flat.leaveOneOut.residual), 1e-9)
        assertEquals(0.0, checkNotNull(flat.inclusive.incomingSlope), 0.0)
        assertEquals(0.0, checkNotNull(flat.inclusive.outgoingSlope), 0.0)
        assertTrue(checkNotNull(peak.leaveOneOut.distance) > checkNotNull(flat.leaveOneOut.distance))
        assertTrue(checkNotNull(peak.inclusive.incomingSlope) > 0.0)
        assertTrue(checkNotNull(peak.inclusive.outgoingSlope) < 0.0)
        val energy = axis(plateau.first, plateau.second, DefaultCheckInAxes.ENERGY)
        assertNull(energy.focalValue)
        assertNull(energy.leaveOneOut.stateMedian)
        assertNull(energy.inclusive.slope)
        assertEquals(0, energy.recordedStateCount)
        val view = project(plateau.first, plateau.second)
        assertEquals(2, view.candidates.single().emotions.single { it.emotion == "calm" }.count)
        assertEquals(1, view.candidates.single().emotions.single { it.emotion == "glad" }.count)
        assertEquals(2, view.candidates.single().emotionAbsentCount)
        val label = stateShapeAuditLabel(view)
        assertTrue(label.contains("leave-one-out median"))
        assertTrue(label.contains("inclusive segment medians"))
        assertTrue(label.contains("inclusive slope"))
        assertTrue(label.contains("leave-one-out residual"))
        assertTrue(label.contains("inclusive incoming"))
        assertTrue(label.contains("outgoing"))
        assertTrue(label.contains("calm 2"))
        assertTrue(label.contains("No preferred scale."))
        assertFalse(label.contains("score"))
        assertFalse(label.contains("winner"))
        assertFalse(label.contains("p0"))
    }

    @Test
    fun upwardAndDownwardSlopesHaveOppositeSigns() {
        val rising = mood(ramp(listOf(1, 3, 5, 7, 9)))
        val falling = mood(ramp(listOf(9, 7, 5, 3, 1)))
        val flat = mood(ramp(listOf(5, 5, 5, 5, 5)))
        assertTrue(checkNotNull(rising.inclusive.slope) > 0.0)
        assertTrue(checkNotNull(rising.inclusive.incomingSlope) > 0.0)
        assertTrue(checkNotNull(rising.inclusive.outgoingSlope) > 0.0)
        assertTrue(checkNotNull(falling.inclusive.slope) < 0.0)
        assertTrue(checkNotNull(falling.inclusive.incomingSlope) < 0.0)
        assertTrue(checkNotNull(falling.inclusive.outgoingSlope) < 0.0)
        assertEquals(0.0, checkNotNull(flat.inclusive.slope), 0.0)
        assertTrue(kotlin.math.abs(checkNotNull(flat.inclusive.slope)) < checkNotNull(rising.inclusive.slope))
        assertTrue(checkNotNull(falling.inclusive.slope) < checkNotNull(flat.inclusive.slope))
        assertEquals(0.0, checkNotNull(rising.leaveOneOut.residual), 1e-9)
        assertEquals(0.0, checkNotNull(falling.leaveOneOut.residual), 1e-9)
    }

    @Test
    fun identicalFocalValuesKeepOppositeIncomingMomentum() {
        val rising = mood(endingSeries(listOf(1, 3, 5)))
        val falling = mood(endingSeries(listOf(9, 7, 5)))
        assertEquals(checkNotNull(rising.focalValue), checkNotNull(falling.focalValue), 0.0)
        assertTrue(checkNotNull(rising.inclusive.incomingSlope) > 0.0)
        assertTrue(checkNotNull(falling.inclusive.incomingSlope) < 0.0)
        assertNull(rising.inclusive.outgoingSlope)
        assertNull(falling.inclusive.outgoingSlope)
    }

    @Test
    fun theFocalResidualUsesTheLineFitWithoutTheFocalPoint() {
        val offLine = mood(ramp(listOf(0, 0, 10, 4)))
        val pair = mood(endingSeries(listOf(1, 5)))
        assertEquals(22.0 / 3.0, checkNotNull(offLine.leaveOneOut.residual), 1e-9)
        assertTrue(checkNotNull(offLine.inclusive.slope) > 0.0)
        assertTrue(checkNotNull(pair.inclusive.slope) > 0.0)
        assertNull(pair.leaveOneOut.residual)
        val peak = mood(ramp(listOf(5, 5, 9, 5, 5)))
        assertEquals(
            checkNotNull(peak.focalValue) - checkNotNull(peak.leaveOneOut.stateMedian),
            checkNotNull(peak.leaveOneOut.residual),
            1e-9,
        )
    }

    @Test
    fun aValleyFallsIntoTheFocalStateAndRisesAfterIt() {
        val valley = mood(ramp(listOf(8, 6, 4, 6, 8)))
        assertTrue(checkNotNull(valley.inclusive.incomingSlope) < 0.0)
        assertTrue(checkNotNull(valley.inclusive.outgoingSlope) > 0.0)
        assertTrue(checkNotNull(valley.inclusive.incomingSlope) < checkNotNull(valley.inclusive.outgoingSlope))
    }

    @Test
    fun segmentBalanceStaysPutWhenOneSegmentIsSampledMoreDensely() {
        val even = mood(days(listOf(4, 6)), BidirectionalSpan(7L * DAY, 3L * DAY))
        val uneven = mood(days(listOf(1, 1, 1, 9, 9, 9)), BidirectionalSpan(7L * DAY, 3L * DAY))
        assertEquals(3, even.wakingSegmentCount)
        assertEquals(3, uneven.wakingSegmentCount)
        assertEquals(2, even.inclusive.levels[0].recordedStateCount)
        assertEquals(2, even.inclusive.levels[1].recordedStateCount)
        assertEquals(2, even.inclusive.levels[2].recordedStateCount)
        assertEquals(2, uneven.inclusive.levels[1].recordedStateCount)
        assertEquals(6, uneven.inclusive.levels[2].recordedStateCount)
        assertEquals(SegmentKind.FOCAL_ORDERED, uneven.inclusive.levels[1].kind)
        assertEquals(0.0, checkNotNull(uneven.inclusive.slope), 0.0)
        assertEquals(checkNotNull(even.inclusive.balancedMedian), checkNotNull(uneven.inclusive.balancedMedian), 0.0)
        assertEquals(checkNotNull(even.inclusive.medianSpread), checkNotNull(uneven.inclusive.medianSpread), 0.0)
        assertEquals(0.0, checkNotNull(uneven.inclusive.medianSpread), 0.0)
        assertTrue(checkNotNull(uneven.leaveOneOut.stateMad) > checkNotNull(even.leaveOneOut.stateMad))
    }

    @Test
    fun anEndingCheckInOutsideTheSpanDoesNotEnterTheMedian() {
        val start = 50L * DAY
        val data = chain(
            listOf(
                Sample("back", start - 2 * HOUR, 5),
                Sample("focal", start, 5),
                Sample("late", start + 20 * HOUR, 10),
            ),
        )
        val narrowSpan = BidirectionalSpan(6L * HOUR, 6L * HOUR)
        val wideSpan = BidirectionalSpan(6L * HOUR, 24L * HOUR)
        val view = project(data, "focal", listOf(narrowSpan, wideSpan))
        val narrow = view.candidates.single { it.span == narrowSpan }.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        val wide = view.candidates.single { it.span == wideSpan }.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(2, narrow.recordedStateCount)
        assertEquals(3, wide.recordedStateCount)
        assertEquals(5.0, checkNotNull(narrow.leaveOneOut.stateMedian), 0.0)
        assertEquals(7.5, checkNotNull(wide.leaveOneOut.stateMedian), 0.0)
        assertEquals(0.0, checkNotNull(narrow.inclusive.slope), 0.0)
        assertTrue(checkNotNull(wide.inclusive.slope) > 0.0)
        assertTrue(view.candidates.all { candidate ->
            candidate.residences.none { it.kind == StateResidenceKind.AMBIGUOUS }
        })
        assertFalse(view.candidates.single { it.span == narrowSpan }.residences.any { it.stateId == "late" })
    }

    @Test
    fun branchContextDoesNotCopyTheForkIntoTheBranchMedian() {
        val start = 70L * DAY
        val data = dataset(
            states = listOf(
                state("origin", start, 1),
                state("a", start + HOUR, 2),
                state("next", start + 5 * HOUR, 4),
                state("wake", start + 2 * HOUR, 3),
            ),
            intervals = listOf(
                linked("a", "origin", start, start + HOUR),
                linked("next", "a", start + HOUR, start + 5 * HOUR),
                boundary("wake", "a", start + HOUR, start + 2 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            ),
        )
        val shape = project(data, "a")
        val axis = shape.candidates.single().axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertTrue(shape.candidates.single().residences.none { it.kind == StateResidenceKind.AMBIGUOUS })
        assertEquals(4, axis.recordedStateCount)
        val focal = axis.inclusive.levels.single { it.kind == SegmentKind.FOCAL_ORDERED }
        val branches = axis.inclusive.levels.filter { it.kind == SegmentKind.BRANCH_LOCAL_PREDECESSOR }
        assertEquals(2, focal.recordedStateCount)
        assertEquals(1.5, checkNotNull(focal.median), 0.0)
        assertEquals(2, branches.size)
        assertTrue(branches.all { it.recordedStateCount == 1 })
        assertEquals(setOf(3.0, 4.0), branches.map { checkNotNull(it.median) }.toSet())
        assertTrue(checkNotNull(axis.inclusive.incomingSlope) > 0.0)
        assertNull(axis.inclusive.outgoingSlope)
    }

    @Test
    fun residentMembershipAssignsEveryAdmittedStateOnce() {
        val start = 70L * DAY
        val data = dataset(
            states = listOf(
                state("origin", start, 1),
                state("a", start + HOUR, 2),
                state("next", start + 5 * HOUR, 4),
                state("wake", start + 2 * HOUR, 3),
            ),
            intervals = listOf(
                linked("a", "origin", start, start + HOUR),
                linked("next", "a", start + HOUR, start + 5 * HOUR),
                boundary("wake", "a", start + HOUR, start + 2 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            ),
        )
        val spans = listOf(BidirectionalSpan(DAY, DAY))
        val span = projectRetrospectiveMultiscaleBidirectionalEvidence(data, "a", zone, spans).candidates.single()
        val shape = project(data, "a", spans).candidates.single()
        reconcile(span, shape)
        val branch = span.segments.indexOfFirst { it.rootStateId == "next" }
        val focal = span.segments.indexOfFirst { it.kind == SegmentKind.FOCAL_ORDERED }
        assertTrue(branch >= 0 && focal >= 0)
        assertTrue("a" in span.segments[branch].statesInSpan)
        assertTrue(shape.residences.none { it.stateId == "a" && it.segmentIndex == branch })
        assertEquals(focal, shape.residences.single { it.stateId == "origin" }.segmentIndex)
        assertEquals(focal, shape.residences.single { it.stateId == "a" }.segmentIndex)
        assertEquals(StateResidenceKind.RESIDENT, shape.residences.single { it.stateId == "origin" }.kind)
        val mood = shape.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertTrue(checkNotNull(mood.inclusive.incomingSlope) > 0.0)
        assertEquals(shape.residences.count { it.kind == StateResidenceKind.RESIDENT }, mood.recordedStateCount)
        assertEquals(mood.recordedStateCount, mood.inclusive.levels.sumOf { it.recordedStateCount })
        val chain = chain(
            listOf(
                Sample("back", start - 2 * HOUR, 5),
                Sample("focal", start, 5),
                Sample("late", start + 2 * HOUR, 8),
            ),
        )
        val ordered = projectRetrospectiveMultiscaleBidirectionalEvidence(chain, "focal", zone, spans).candidates.single()
        reconcile(ordered, project(chain, "focal", spans).candidates.single())
    }

    @Test
    fun aLoneFocalLeavesTheReferenceMedianUnavailable() {
        val start = 20L * DAY
        val view = project(
            dataset(listOf(state("focal", start, 5)), emptyList()),
            "focal",
            DEFAULT_BIDIRECTIONAL_AUDIT_SPANS,
        )
        assertEquals(DEFAULT_BIDIRECTIONAL_AUDIT_SPANS, view.candidates.map { it.span })
        val mood = view.candidates.first().axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(5.0, checkNotNull(mood.focalValue), 0.0)
        assertNull(mood.leaveOneOut.stateMedian)
        assertNull(mood.leaveOneOut.stateMad)
        assertNull(mood.leaveOneOut.distance)
        assertNull(mood.leaveOneOut.residual)
        assertNull(mood.inclusive.slope)
        assertEquals(5.0, checkNotNull(mood.inclusive.balancedMedian), 0.0)
        assertNull(mood.inclusive.medianSpread)
        val label = stateShapeAuditLabel(view)
        assertEquals(6, label.lines().count { it.startsWith("left ") })
        assertTrue(label.contains("focal $FOCAL_STATE_ALIAS"))
        assertTrue(label.contains("unavailable"))
        assertFalse(label.contains(start.toString()))
    }

    @Test
    fun aMissingFocalStaysAbsent() {
        val view = project(dataset(listOf(state("focal", DAY, 1)), emptyList()), "missing")
        assertTrue(view.candidates.isEmpty())
        assertNull(view.anchorAt)
        assertEquals(stateShapeMissingLabel(), stateShapeAuditLabel(view))
    }

    @Test
    fun theDiagnosticsStayOutsideTheLockedPredictors() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveStateShapeDiagnostics.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveStateShapeDiagnostics.kt",
        ).readText()
        listOf(
            "transitionKey",
            "canonicalId",
            "projectNullTrajectoryLearner",
            "projectPredecessorContextLearner",
            "projectCausalFeatureEncoder",
            "projectCausalWalkForwardPreparation",
            "Dao",
            ".insert",
        ).forEach { token -> assertFalse(token, source.contains(token)) }
        assertTrue(source.contains("comparableState"))
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
            assertFalse(source(primary, fallback).readText().contains("retrospective-state-shape-diagnostics"))
        }
        val architecture = source("docs/architecture.md", "../docs/architecture.md").readText()
        assertTrue(architecture.contains("retrospective-state-shape-diagnostics-v1"))
        assertTrue(architecture.contains("bidirectional-smoothed-state"))
    }

    private fun reconcile(span: BidirectionalCandidate, shape: StateShapeCandidate) {
        val claims = span.segments.map { segment ->
            val forward = segment.futureEvidence.flatMap { it.inSpanStateIds }.toSet()
            segment.statesInSpan.mapNotNull { id ->
                if (id != segment.rootStateId && id !in forward) return@mapNotNull null
                span.comparableState(id)
            }.toSet()
        }
        val admitted = span.segments.flatMap { it.statesInSpan }
            .mapNotNull { span.comparableState(it) }
            .distinct()
        assertEquals(admitted.toSet(), shape.residences.map { it.stateId }.toSet())
        admitted.forEach { id ->
            val owners = claims.mapIndexedNotNull { index, ids -> index.takeIf { id in ids } }
            val record = shape.residences.single { it.stateId == id }
            when (owners.size) {
                0 -> {
                    assertEquals(StateResidenceKind.PREDECESSOR_CONTEXT, record.kind)
                    assertNull(record.segmentIndex)
                }
                1 -> {
                    assertEquals(StateResidenceKind.RESIDENT, record.kind)
                    assertEquals(owners.single(), record.segmentIndex)
                }
                else -> {
                    assertEquals(StateResidenceKind.AMBIGUOUS, record.kind)
                    assertNull(record.segmentIndex)
                }
            }
        }
        val residents = shape.residences.filter { it.kind == StateResidenceKind.RESIDENT }
        assertEquals(residents.size, residents.map { it.stateId }.distinct().size)
        assertTrue(shape.residences.filter { it.kind != StateResidenceKind.RESIDENT }.all { it.segmentIndex == null })
    }

    private fun mood(series: Pair<CheckInAnalysisDatasetV2, String>, span: BidirectionalSpan = BidirectionalSpan(DAY, DAY)) =
        axis(series.first, series.second, DefaultCheckInAxes.MOOD, listOf(span))

    private fun axis(
        data: CheckInAnalysisDatasetV2,
        focal: String,
        axisId: String,
        spans: List<BidirectionalSpan> = listOf(BidirectionalSpan(DAY, DAY)),
    ) = project(data, focal, spans).candidates.single().axes.single { it.axisId == axisId }

    private fun ramp(moods: List<Int>, emotions: List<String> = emptyList()): Pair<CheckInAnalysisDatasetV2, String> {
        val start = 30L * DAY
        val samples = moods.mapIndexed { index, mood ->
            Sample("p$index", start + index * 2L * HOUR, mood, emotions.getOrElse(index) { "" })
        }
        return chain(samples) to samples[moods.size / 2].id
    }

    private fun endingSeries(moods: List<Int>): Pair<CheckInAnalysisDatasetV2, String> {
        val start = 40L * DAY
        val samples = moods.mapIndexed { index, mood ->
            Sample("m$index", start + index * 2L * HOUR, mood)
        }
        return chain(samples) to samples.last().id
    }

    private fun days(day3: List<Int>): Pair<CheckInAnalysisDatasetV2, String> {
        val start = 80L * DAY
        val focalAt = start + DAY
        val day3At = start + 2 * DAY
        val samples = mutableListOf(
            Sample("d1a", start, 4),
            Sample("d1b", start + 2 * HOUR, 6),
            Sample("d2a", focalAt, 5),
            Sample("d2b", focalAt + 2 * HOUR, 5),
        )
        val intervals = mutableListOf(
            linked("d1b", "d1a", start, start + 2 * HOUR),
            boundary("d2a", "d1b", start + 2 * HOUR, focalAt, CheckInContextStartBasis.WAKE_TIME),
            linked("d2b", "d2a", focalAt, focalAt + 2 * HOUR),
            boundary("d30", "d2b", focalAt + 2 * HOUR, day3At, CheckInContextStartBasis.WAKE_TIME),
        )
        day3.forEachIndexed { index, mood ->
            val id = "d3$index"
            val at = day3At + index * HOUR
            samples += Sample(id, at, mood)
            if (index > 0) {
                intervals += linked(id, "d3${index - 1}", day3At + (index - 1) * HOUR, at)
            }
        }
        return dataset(samples.map { state(it.id, it.at, it.mood, it.emotion) }, intervals) to "d2a"
    }

    private fun chain(samples: List<Sample>): CheckInAnalysisDatasetV2 {
        val intervals = samples.zipWithNext { left, right ->
            linked(right.id, left.id, left.at, right.at)
        }
        return dataset(samples.map { state(it.id, it.at, it.mood, it.emotion) }, intervals)
    }

    private fun project(
        data: CheckInAnalysisDatasetV2,
        focal: String,
        spans: List<BidirectionalSpan> = listOf(BidirectionalSpan(DAY, DAY)),
    ) = projectRetrospectiveStateShapeDiagnostics(data, focal, zone, spans)

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun state(id: String, at: Long, mood: Int, emotion: String = ""): StateObservationV2 {
        val reading = AxisReadingV2(DefaultCheckInAxes.MOOD, mood, mood / 10.0)
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = mapOf(reading.axisId to reading),
            core = CoreAxisViewV2(reading, null, null, null),
            conflictingAxisIds = emptyList(),
            emotion = emotion,
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

    private data class Sample(val id: String, val at: Long, val mood: Int, val emotion: String = "")

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
