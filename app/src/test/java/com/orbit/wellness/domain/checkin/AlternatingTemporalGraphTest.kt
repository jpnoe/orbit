package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternatingTemporalGraphTest {
    @Test
    fun identicalCheckInValuesStayDistinctStateNodes() {
        val dataset = dataset(
            checkIns = listOf(
                checkIn("second", 2_000, emotion = "calm"),
                checkIn("first", 1_000, emotion = "calm"),
            ),
            axisValues = listOf(
                axis("first", DefaultCheckInAxes.MOOD, 4),
                axis("second", DefaultCheckInAxes.MOOD, 4),
            ),
            windows = listOf(window("first", 1_000), window("second", 2_000)),
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        assertEquals(listOf("first", "second"), graph.states.map { it.id })
        assertEquals(graph.states[0].state.emotion, graph.states[1].state.emotion)
        assertEquals(graph.states[0].state.core.mood!!.raw, graph.states[1].state.core.mood!!.raw)
        assertNotSame(graph.states[0], graph.states[1])
        assertNotSame(graph.states[0].state, graph.states[1].state)
        assertSame(dataset.states[0], graph.states[0].state)
        assertSame(dataset.states[1], graph.states[1].state)
    }

    @Test
    fun aLinkedStepKeepsTheStoredDirectionAndAdjacency() {
        val dataset = linked("a", "b")
        val graph = projectAlternatingTemporalGraph(dataset)
        val transition = graph.transition("b")!!
        assertEquals("a", transition.startStateId)
        assertEquals("b", transition.endStateId)
        assertSame(dataset.transitions.single(), transition.linkedTransition)
        assertSame(dataset.intervals.last(), transition.interval)
        assertEquals(listOf("b"), graph.state("a")!!.outgoingTransitionIds)
        assertEquals(listOf("b"), graph.state("b")!!.incomingTransitionIds)
        val step = graph.causalSteps.single { it.input.transitionId == "b" }
        assertSame(graph.state("a")!!.state, step.input.startingState)
        assertSame(graph.state("b")!!.state, step.target.endingState)
        assertTrue(step.eligibility.eligible)
    }

    @Test
    fun aThreeStateChainExposesEachStatesImmediateNeighborhood() {
        val dataset = dataset(
            checkIns = listOf(checkIn("c", 3_000), checkIn("a", 1_000), checkIn("b", 2_000)),
            windows = listOf(
                window("a", 1_000),
                previousWindow("b", end = 2_000, start = 1_000, previous = "a"),
                previousWindow("c", end = 3_000, start = 2_000, previous = "b"),
            ),
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        assertEquals(
            RetrospectiveNeighborhood(
                focalStateId = "b",
                incomingTransitionIds = listOf("b"),
                outgoingTransitionIds = listOf("c"),
                incomingStateIds = listOf("a"),
                outgoingStateIds = listOf("c"),
            ),
            graph.retrospectiveNeighborhood("b"),
        )
        assertEquals(listOf("b"), graph.retrospectiveNeighborhood("a")!!.outgoingStateIds)
        assertTrue(graph.retrospectiveNeighborhood("a")!!.incomingStateIds.isEmpty())
        assertEquals(listOf("b"), graph.retrospectiveNeighborhood("c")!!.incomingStateIds)
        assertTrue(graph.retrospectiveNeighborhood("c")!!.outgoingTransitionIds.isEmpty())
        assertNull(graph.retrospectiveNeighborhood("missing"))
    }

    @Test
    fun branchingOutgoingTransitionsAreBothKept() {
        val dataset = dataset(
            checkIns = listOf(checkIn("c", 3_000), checkIn("b", 2_000), checkIn("a", 1_000)),
            windows = listOf(
                window("a", 1_000),
                previousWindow("b", end = 2_000, start = 1_000, previous = "a"),
                previousWindow("c", end = 3_000, start = 1_000, previous = "a"),
            ),
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        assertEquals(listOf("b", "c"), graph.state("a")!!.outgoingTransitionIds)
        assertEquals(listOf("b", "c"), graph.retrospectiveNeighborhood("a")!!.outgoingStateIds)
        assertEquals("a", graph.transition("b")!!.startStateId)
        assertEquals("a", graph.transition("c")!!.startStateId)
    }

    @Test
    fun wakeAndDayStartIntervalsHaveNoStartStateAndNoEligibleStep() {
        val dataset = dataset(
            checkIns = listOf(checkIn("day", 2_000), checkIn("wake", 1_000)),
            windows = listOf(
                window("wake", end = 1_000, basis = CheckInContextStartBasis.WAKE_TIME),
                window("day", end = 2_000, basis = CheckInContextStartBasis.DAY_START),
            ),
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        assertEquals(listOf("wake", "day"), graph.states.map { it.id })
        assertTrue(graph.transitions.all { it.startStateId == null && it.linkedTransition == null })
        assertEquals("wake", graph.transition("wake")!!.endStateId)
        assertEquals("day", graph.transition("day")!!.endStateId)
        assertTrue(graph.retrospectiveNeighborhood("wake")!!.incomingStateIds.isEmpty())
        val steps = graph.causalSteps
        assertEquals(2, steps.size)
        assertTrue(steps.none { it.eligibility.eligible })
        assertTrue(steps.all { it.input.startingState == null })
        assertEquals(
            listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL),
            steps.first().eligibility.reasons,
        )
        assertTrue(graph.issues.none { it.code == AnalysisIssueCode.MISSING_PREVIOUS_CHECK_IN })
    }

    @Test
    fun anUnresolvedPreviousIdDoesNotCreateAState() {
        val dataset = dataset(
            checkIns = listOf(checkIn("end", 2_000)),
            windows = listOf(previousWindow("end", end = 2_000, start = 1_000, previous = "ghost")),
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        assertEquals(listOf("end"), graph.states.map { it.id })
        val transition = graph.transition("end")!!
        assertNull(transition.startStateId)
        assertNull(transition.linkedTransition)
        assertEquals("ghost", transition.interval.previousCheckInId)
        assertSame(dataset.issues, graph.issues)
        assertEquals(AnalysisIssueCode.MISSING_PREVIOUS_CHECK_IN, graph.issues.single().code)
        val step = graph.causalSteps.single()
        assertNull(step.input.startingState)
        assertEquals(
            listOf(CausalIneligibilityReason.START_UNRESOLVED),
            step.eligibility.reasons,
        )
    }

    @Test
    fun backwardChronologyStaysInTheStoredDirectionAndIsIneligible() {
        val dataset = dataset(
            checkIns = listOf(checkIn("earlier", 1_000), checkIn("later", 5_000)),
            windows = listOf(
                window("later", 5_000),
                previousWindow("earlier", end = 1_000, start = 0, previous = "later"),
            ),
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        val transition = graph.transition("earlier")!!
        assertEquals("later", transition.startStateId)
        assertEquals("earlier", transition.endStateId)
        val linked = checkNotNull(transition.linkedTransition)
        assertSame(dataset.transitions.single(), linked)
        assertEquals("later", linked.previous.checkInId)
        assertEquals("earlier", linked.ending.checkInId)
        assertTrue(graph.transitions.none { it.startStateId == "earlier" && it.endStateId == "later" })
        assertEquals(AnalysisIssueCode.PREVIOUS_CHECK_IN_IS_NOT_EARLIER, graph.issues.single().code)
        val step = graph.causalSteps.single { it.input.transitionId == "earlier" }
        assertSame(graph.state("later")!!.state, step.input.startingState)
        assertSame(graph.state("earlier")!!.state, step.target.endingState)
        assertEquals(
            listOf(CausalIneligibilityReason.INVALID_CHRONOLOGY),
            step.eligibility.reasons,
        )
    }

    @Test
    fun aLinkWithNoMeasuredDeltaStaysLinked() {
        val dataset = linked("a", "b")
        val graph = projectAlternatingTemporalGraph(dataset)
        val transition = graph.transition("b")!!
        val linked = checkNotNull(transition.linkedTransition)
        assertTrue(linked.deltas.isEmpty())
        assertFalse(linked.hasMeasuredDelta)
        val step = graph.causalSteps.single { it.input.transitionId == "b" }
        assertTrue(step.eligibility.eligible)
        assertSame(transition.interval, step.input.interval)
    }

    @Test
    fun anIncompleteContextStaysRepresentedAndIsNotEligible() {
        val dataset = dataset(
            checkIns = listOf(checkIn("b", 2_000), checkIn("a", 1_000)),
            windows = listOf(
                window("a", 1_000),
                previousWindow("b", end = 2_000, start = 1_000, previous = "a", completedAt = null),
            ),
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        val transition = graph.transition("b")!!
        assertFalse(transition.interval.isComplete)
        assertEquals("a", transition.startStateId)
        assertSame(dataset.transitions.single(), transition.linkedTransition)
        val step = graph.causalSteps.single { it.input.transitionId == "b" }
        assertEquals(
            listOf(CausalIneligibilityReason.INCOMPLETE_CONTEXT),
            step.eligibility.reasons,
        )
        assertFalse(step.eligibility.eligible)
    }

    @Test
    fun aMissingCategoryRowStaysAbsentAndCanStillBeEligible() {
        val dataset = dataset(
            checkIns = listOf(checkIn("b", 2_000), checkIn("a", 1_000)),
            windows = listOf(
                window("a", 1_000),
                previousWindow("b", end = 2_000, start = 1_000, previous = "a"),
            ),
            categories = noneCategories("a") +
                noneCategories("b").filter { it.category != CheckInContextCategories.DIET },
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        assertEquals(
            CategoryObservationKindV2.ABSENT,
            graph.transition("b")!!.interval.diet.kind,
        )
        assertEquals(AnalysisIssueCode.MISSING_CATEGORY_ROW, graph.issues.single().code)
        assertTrue(graph.causalSteps.single { it.input.transitionId == "b" }.eligibility.eligible)
    }

    @Test
    fun orderingFollowsRecordedTimeAndThenId() {
        val first = dataset(
            checkIns = listOf(checkIn("m", 1_000), checkIn("a", 1_000)),
            windows = listOf(window("m", 1_000, basis = CheckInContextStartBasis.DAY_START), window("a", 1_000)),
        )
        val reversed = dataset(
            checkIns = listOf(checkIn("a", 1_000), checkIn("m", 1_000)),
            windows = listOf(window("a", 1_000), window("m", 1_000, basis = CheckInContextStartBasis.DAY_START)),
        )
        val forward = projectAlternatingTemporalGraph(first)
        val backward = projectAlternatingTemporalGraph(reversed)
        assertEquals(listOf("a", "m"), forward.states.map { it.id })
        assertEquals(listOf("a", "m"), forward.transitions.map { it.id })
        assertEquals(forward.states.map { it.id }, backward.states.map { it.id })
        assertEquals(forward.transitions.map { it.id }, backward.transitions.map { it.id })
        assertEquals(
            forward.transitions.map { it.startStateId to it.endStateId },
            backward.transitions.map { it.startStateId to it.endStateId },
        )
    }

    @Test
    fun aDisagreedWindowStaysLinkedAndIsNotEligible() {
        val dataset = dataset(
            checkIns = listOf(checkIn("b", 2_000), checkIn("a", 1_000)),
            windows = listOf(
                window("a", 1_000),
                previousWindow("b", end = 1_500, start = 1_000, previous = "a"),
            ),
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        assertEquals("a", graph.transition("b")!!.startStateId)
        assertEquals(AnalysisIssueCode.WINDOW_END_DISAGREES_WITH_CHECK_IN, graph.issues.single().code)
        assertEquals(
            listOf(CausalIneligibilityReason.INCONSISTENT_CONTEXT),
            graph.causalSteps.single { it.input.transitionId == "b" }.eligibility.reasons,
        )
    }

    @Test
    fun causalInputExcludesTheEndingObservationAndNeighborhood() {
        val dataset = dataset(
            checkIns = listOf(
                checkIn("b", 2_000, emotion = "ending-only-emotion", note = "ending-only-note"),
                checkIn("a", 1_000, emotion = "starting-only-emotion"),
            ),
            windows = listOf(
                window("a", 1_000),
                previousWindow("b", end = 2_000, start = 1_000, previous = "a"),
            ),
        )
        val graph = projectAlternatingTemporalGraph(dataset)
        val step = graph.causalSteps.single { it.input.transitionId == "b" }
        assertEquals("starting-only-emotion", step.input.startingState!!.emotion)
        assertEquals("ending-only-emotion", step.target.endingState.emotion)
        assertFalse(step.input.toString().contains("ending-only-emotion"))
        assertFalse(step.input.toString().contains("ending-only-note"))
        assertFalse(step.input.toString().contains("Retrospective"))
        assertEquals(
            setOf("transitionId", "startingState", "interval"),
            fieldNames(CausalTransitionInput::class.java),
        )
        assertEquals(
            setOf("input", "target", "eligibility"),
            fieldNames(CausalStep::class.java),
        )
        val inputTypes = CausalTransitionInput::class.java.declaredFields
            .filterNot { it.name.startsWith("$") || it.name == "serialVersionUID" }
            .map { it.type }
        assertEquals(1, inputTypes.count { it == StateObservationV2::class.java })
        assertFalse(inputTypes.contains(RetrospectiveNeighborhood::class.java))
        assertFalse(inputTypes.contains(StateTransitionObservationV2::class.java))
        assertFalse(inputTypes.contains(DirectedCheckInStep::class.java))
        assertFalse(inputTypes.contains(AxisDeltaV2::class.java))
        assertFalse(inputTypes.contains(CausalPredictionTarget::class.java))
    }

    private fun linked(startId: String, endId: String) = dataset(
        checkIns = listOf(checkIn(endId, 2_000), checkIn(startId, 1_000)),
        windows = listOf(
            window(startId, 1_000),
            previousWindow(endId, end = 2_000, start = 1_000, previous = startId),
        ),
    )

    private fun dataset(
        checkIns: List<CheckInRecord>,
        windows: List<CheckInContextWindow>,
        axisValues: List<CheckInAxisValue> = emptyList(),
        categories: List<CheckInContextCategory>? = null,
    ): CheckInAnalysisDatasetV2 {
        val categoryRows = categories ?: checkIns.flatMap { noneCategories(it.id) }
        return CheckInAnalysisDatasetBuilder.build(
            checkIns = checkIns,
            axisValues = axisValues,
            windows = windows,
            categories = categoryRows,
        )
    }

    private fun checkIn(
        id: String,
        at: Long,
        emotion: String = "",
        note: String = "",
    ) = CheckInRecord(
        id = id,
        localDate = DAY,
        recordedAt = at,
        trigger = CheckInRecord.TRIGGER_MANUAL,
        note = note,
        emotion = emotion,
    )

    private fun window(
        id: String,
        end: Long,
        start: Long = 0L,
        basis: String = CheckInContextStartBasis.WAKE_TIME,
        completedAt: Long? = 10L,
    ) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = null,
        windowStartAt = start,
        windowEndAt = end,
        startBasis = basis,
        completedAt = completedAt,
    )

    private fun previousWindow(
        id: String,
        end: Long,
        start: Long,
        previous: String,
        completedAt: Long? = 10L,
    ) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = end,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = completedAt,
    )

    private fun noneCategories(id: String) = CheckInContextCategories.ALL.map { category ->
        CheckInContextCategory(
            checkInId = id,
            category = category,
            status = CheckInContextStatus.NONE,
        )
    }

    private fun axis(checkInId: String, axisId: String, value: Int) =
        CheckInAxisValue(checkInId, axisId, value)

    private companion object {
        const val DAY = "2026-11-08"

        fun fieldNames(type: Class<*>): Set<String> = type.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "serialVersionUID" }
            .toSet()
    }
}
