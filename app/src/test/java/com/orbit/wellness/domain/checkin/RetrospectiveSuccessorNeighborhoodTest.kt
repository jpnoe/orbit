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

class RetrospectiveSuccessorNeighborhoodTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun theSelectedTransitionIsDepthOneAndADeeperChainIsFollowed() {
        val projected = project(chain())
        val first = projected.neighborhood("b")
        assertEquals("b", first.hops.first().transition.key.raw)
        assertEquals("a", first.focal?.stateKey?.raw)
        assertEquals(listOf("b", "c", "d"), first.hops.map { it.transition.key.raw })
        assertEquals(listOf("a", "b", "c"), first.hops.map { it.source?.stateKey?.raw })
        assertEquals(listOf("b", "c", "d"), first.hops.map { it.successor?.stateKey?.raw })
        assertEquals(SuccessorStopReason.END_OF_RECORDED_HISTORY, first.stop.reason)
        val later = projected.neighborhood("c")
        assertEquals(listOf("c", "d"), later.hops.map { it.transition.key.raw })
        assertFalse(later.hops.any { it.transition.key.raw == "b" })
    }

    @Test
    fun anUnrelatedEqualTimestampTransitionIsNotSubstituted() {
        val start = 30L * DAY
        val data = dataset(
            states = listOf(
                state("origin", start, mood = 2),
                state("left", start + HOUR, mood = 4),
                state("right", start + HOUR, mood = 9),
            ),
            intervals = listOf(
                linked("left", "origin", start, start + HOUR),
                linked("right", "origin", start, start + HOUR),
            ),
        )
        val left = project(data).neighborhood("left")
        assertEquals(listOf("left"), left.hops.map { it.transition.key.raw })
        assertEquals("left", left.hops.single().successor?.stateKey?.raw)
        assertFalse(left.hops.any { it.transition.key.raw == "right" || it.successor?.stateKey?.raw == "right" })
        val sixHours = left.windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertEquals(listOf("left", "right"), sixHours.outcomes.intervals.map { it.key.raw })
    }

    @Test
    fun ambiguousSuccessorsStayCandidatesInIdentityOrder() {
        val start = 31L * DAY
        val data = dataset(
            states = listOf(
                state("origin", start, mood = 1),
                state("a", start + HOUR, mood = 2),
                state("wake", start + 2 * HOUR, mood = 3),
                state("next", start + 5 * HOUR, mood = 4),
            ),
            intervals = listOf(
                linked("a", "origin", start, start + HOUR),
                linked("next", "a", start + HOUR, start + 5 * HOUR),
                boundary("wake", "a", start + HOUR, start + 2 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            ),
        )
        val forward = project(data).neighborhood("a")
        val reversed = project(data.copy(intervals = data.intervals.reversed())).neighborhood("a")
        assertEquals(forward, reversed)
        assertEquals(listOf("a"), forward.hops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.AMBIGUOUS_SUCCESSOR, forward.stop.reason)
        assertEquals(listOf("next", "wake"), forward.stop.candidates.map { it.transitionKey.raw })
        assertTrue(successorTechnicalLabel(forward).contains("occurrence-identity order"))
    }

    @Test
    fun unresolvedInvalidCycleAndIncompleteStayDistinct() {
        val unresolved = project(unresolvedSource()).neighborhood("end")
        assertNull(unresolved.focal)
        assertEquals("ghost", unresolved.unresolvedSourceId)
        assertEquals("end", unresolved.hops.single().successor?.stateKey?.raw)
        assertTrue(unresolved.hops.none { it.source?.stateKey?.raw == "ghost" })

        val start = 32L * DAY
        val invalid = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("late", start + 5 * HOUR, mood = 8),
                    state("early", start + HOUR, mood = 2),
                ),
                intervals = listOf(
                    linked("late", "origin", start, start + 5 * HOUR),
                    linked("early", "late", start, start + HOUR),
                ),
            ),
        ).neighborhood("late")
        assertEquals(listOf("late"), invalid.hops.map { it.transition.key.raw })
        assertEquals("early", invalid.stop.stoppingTransitionKey?.raw)
        assertEquals(SuccessorStopReason.INVALID_CHRONOLOGY, invalid.stop.reason)
        assertEquals("late", invalid.hops.single().successor?.stateKey?.raw)

        val cycled = project(cycle()).neighborhood("b")
        assertEquals(listOf("b", "c"), cycled.hops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.CYCLE, cycled.stop.reason)
        assertEquals("a", cycled.stop.stoppingTransitionKey?.raw)
        assertTrue(
            checkNotNull(cycled.stop.eligibility).reasons.contains(CausalIneligibilityReason.INVALID_CHRONOLOGY),
        )

        val open = project(incomplete()).neighborhood("b")
        assertEquals(listOf("b", "c"), open.hops.map { it.transition.key.raw })
        assertFalse(open.hops.first().transition.completed)
        assertTrue(open.hops.first().transition.eligibility.reasons.contains(CausalIneligibilityReason.INCOMPLETE_CONTEXT))
        assertEquals(CategoryKnowledge.UNKNOWN, open.hops.first().transition.activityReview)
        assertEquals(CategoryKnowledge.UNKNOWN, open.hops.last().transition.activityReview)
        assertEquals(SuccessorStopReason.END_OF_RECORDED_HISTORY, open.stop.reason)
    }

    @Test
    fun noLaterEdgeIsEndOfRecordedHistoryRatherThanReviewedAbsence() {
        val neighborhood = project(chain()).neighborhood("d")
        assertEquals(SuccessorStopReason.END_OF_RECORDED_HISTORY, neighborhood.stop.reason)
        assertEquals(CategoryKnowledge.REVIEWED_NONE, neighborhood.hops.single().transition.activityReview)
        val compact = successorCompactLabel(neighborhood)
        assertTrue(compact.contains("Nothing recorded later is the end of recorded history, not reviewed absence."))
        assertTrue(compact.contains("Contains recorded future behavior and outcomes. Retrospective only; excluded from forecast inputs."))
    }

    @Test
    fun anUnlinkedWakeOrDayStartDoesNotBecomeTheNextEdge() {
        val start = 33L * DAY
        val data = dataset(
            states = listOf(
                state("before", start, mood = 1),
                state("mid", start + HOUR, mood = 2),
                state("woken", start + 3 * HOUR, mood = 3),
                state("day", start + 4 * HOUR, mood = 4),
            ),
            intervals = listOf(
                linked("mid", "before", start, start + HOUR),
                boundary("woken", null, start + 2 * HOUR, start + 3 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                boundary("day", null, start + 3 * HOUR, start + 4 * HOUR, CheckInContextStartBasis.DAY_START),
            ),
        )
        val neighborhood = project(data).neighborhood("mid")
        assertEquals(listOf("mid"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.DISCONNECTED_AT_BOUNDARY, neighborhood.stop.reason)
        assertTrue(neighborhood.observableCutoffAt!! > checkNotNull(neighborhood.hops.last().successor).recordedAt)
        val compact = successorCompactLabel(neighborhood)
        assertTrue(compact.contains("No graph-linked successor. Every check-in at the earliest later time is a wake or day start."))
        assertFalse(compact.contains("Nothing recorded later"))
        assertFalse(compact.contains("Later sampled records exist"))
        val sixHours = neighborhood.windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertEquals(listOf("day", "mid", "woken"), sixHours.choices.intervals.map { it.key.raw }.sorted())
        assertTrue(sixHours.outcomes.intervals.map { it.key.raw }.containsAll(listOf("woken", "day")))
    }

    @Test
    fun aLaterUnlinkedRecordIsNotTheEndOfRecordedHistory() {
        val start = 35L * DAY
        val neighborhood = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("end", start + HOUR, mood = 2),
                    state("side", start + 2 * HOUR, mood = 3),
                    state("later", start + 3 * HOUR, mood = 4),
                ),
                intervals = listOf(
                    linked("end", "origin", start, start + HOUR),
                    linked("later", "side", start + 2 * HOUR, start + 3 * HOUR),
                ),
            ),
        ).neighborhood("end")
        assertEquals(listOf("end"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR, neighborhood.stop.reason)
        assertTrue(successorCompactLabel(neighborhood).contains("No graph-linked successor. Later sampled records exist."))
        assertFalse(successorCompactLabel(neighborhood).contains("Nothing recorded later"))
    }

    @Test
    fun aLinkedBoundaryIsTheTerminalHopWhenItIsTheFinalRecord() {
        val start = 34L * DAY
        val neighborhood = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("mid", start + HOUR, mood = 2),
                    state("woken", start + 3 * HOUR, mood = 3, emotion = "steady"),
                ),
                intervals = listOf(
                    linked("mid", "origin", start, start + HOUR),
                    boundary("woken", "mid", start + HOUR, start + 3 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                ),
            ),
        ).neighborhood("mid")
        assertEquals(listOf("mid", "woken"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals("mid", neighborhood.hops.last().source?.stateKey?.raw)
        assertEquals(CheckInContextStartBasis.WAKE_TIME, neighborhood.hops.last().transition.startBasis)
        assertNull(neighborhood.hops.last().successor)
        assertTrue(neighborhood.hops.none { it.successor?.stateKey?.raw == "woken" })
        assertEquals(SuccessorStopReason.BOUNDARY_REACHED, neighborhood.stop.reason)
        assertEquals("woken", neighborhood.stop.stoppingTransitionKey?.raw)
        val compact = successorCompactLabel(neighborhood)
        assertTrue(compact.contains("Graph-linked wake or day start stops the ordered path."))
        assertFalse(compact.contains("Nothing recorded later"))
        val sixHours = neighborhood.windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertTrue(sixHours.outcomes.intervals.map { it.key.raw }.contains("woken"))
    }

    @Test
    fun aLinkedBoundaryStopsThePathWhileSampledWindowsKeepTheNextSegment() {
        val start = 36L * DAY
        val neighborhood = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("mid", start + HOUR, mood = 2),
                    state("woken", start + 3 * HOUR, mood = 3, emotion = "steady"),
                    state("next", start + 4 * HOUR, mood = 8),
                ),
                intervals = listOf(
                    linked("mid", "origin", start, start + HOUR),
                    boundary("woken", "mid", start + HOUR, start + 3 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                    linked("next", "woken", start + 3 * HOUR, start + 4 * HOUR),
                ),
            ),
        ).neighborhood("mid")
        assertEquals(listOf("mid", "woken"), neighborhood.hops.map { it.transition.key.raw })
        assertNull(neighborhood.hops.last().successor)
        assertTrue(neighborhood.hops.none { it.transition.key.raw == "next" || it.successor?.stateKey?.raw == "next" })
        assertTrue(neighborhood.hops.none { it.successor?.stateKey?.raw == "woken" })
        assertEquals(SuccessorStopReason.BOUNDARY_REACHED, neighborhood.stop.reason)
        assertEquals("woken", neighborhood.stop.stoppingTransitionKey?.raw)
        val sixHours = neighborhood.windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertTrue(sixHours.choices.intervals.map { it.key.raw }.containsAll(listOf("woken", "next")))
        assertTrue(sixHours.outcomes.intervals.map { it.key.raw }.containsAll(listOf("woken", "next")))
    }

    @Test
    fun aSelectedBoundaryStopsAtDepthOne() {
        val start = 37L * DAY
        val neighborhood = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("woken", start + 2 * HOUR, mood = 4, emotion = "awake"),
                    state("next", start + 4 * HOUR, mood = 6),
                ),
                intervals = listOf(
                    boundary("woken", "origin", start, start + 2 * HOUR, CheckInContextStartBasis.DAY_START),
                    linked("next", "woken", start + 2 * HOUR, start + 4 * HOUR),
                ),
            ),
        ).neighborhood("woken")
        assertEquals(listOf("woken"), neighborhood.hops.map { it.transition.key.raw })
        assertNull(neighborhood.focal)
        assertNull(neighborhood.hops.single().successor)
        assertEquals(1, neighborhood.stop.depth)
        assertEquals(SuccessorStopReason.BOUNDARY_REACHED, neighborhood.stop.reason)
        assertEquals("woken", neighborhood.stop.stoppingTransitionKey?.raw)
        assertTrue(successorCompactLabel(neighborhood).contains("ending state not adopted"))
        val sixHours = neighborhood.windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertTrue(sixHours.outcomes.intervals.map { it.key.raw }.contains("next"))
    }

    @Test
    fun anOrdinaryLaterRecordIsNotRelabeledByABoundaryAfterIt() {
        val start = 38L * DAY
        val neighborhood = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("end", start + HOUR, mood = 2),
                    state("other", start, mood = 3),
                    state("later", start + 3 * HOUR, mood = 4),
                    state("morning", start + 5 * HOUR, mood = 5),
                ),
                intervals = listOf(
                    linked("end", "origin", start, start + HOUR),
                    linked("later", "other", start, start + 3 * HOUR),
                    boundary("morning", "later", start + 3 * HOUR, start + 5 * HOUR, CheckInContextStartBasis.DAY_START),
                ),
            ),
        ).neighborhood("end")
        assertEquals(listOf("end"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR, neighborhood.stop.reason)
        assertTrue(successorCompactLabel(neighborhood).contains("No graph-linked successor. Later sampled records exist."))
        assertFalse(successorCompactLabel(neighborhood).contains("wake or day start"))
    }

    @Test
    fun anUnlinkedBoundarySeparatingTheNextSegmentStaysDisconnected() {
        val start = 39L * DAY
        val neighborhood = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("end", start + HOUR, mood = 2),
                    state("woken", start + 3 * HOUR, mood = 3),
                    state("after", start + 5 * HOUR, mood = 4),
                ),
                intervals = listOf(
                    linked("end", "origin", start, start + HOUR),
                    boundary("woken", null, start + HOUR, start + 3 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                    linked("after", "woken", start + 3 * HOUR, start + 5 * HOUR),
                ),
            ),
        ).neighborhood("end")
        assertEquals(listOf("end"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.DISCONNECTED_AT_BOUNDARY, neighborhood.stop.reason)
        assertTrue(
            successorCompactLabel(neighborhood).contains(
                "Every check-in at the earliest later time is a wake or day start.",
            ),
        )
        val sixHours = neighborhood.windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertTrue(sixHours.choices.intervals.map { it.key.raw }.containsAll(listOf("woken", "after")))
        assertTrue(sixHours.outcomes.intervals.map { it.key.raw }.containsAll(listOf("woken", "after")))
    }

    @Test
    fun aTiedBoundaryCohortIsDisconnected() {
        val start = 42L * DAY
        val tied = start + 3 * HOUR
        val neighborhood = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("end", start + HOUR, mood = 2),
                    state("z-day", tied, mood = 3),
                    state("a-wake", tied, mood = 4),
                    state("later", start + 5 * HOUR, mood = 5),
                ),
                intervals = listOf(
                    linked("end", "origin", start, start + HOUR),
                    boundary("z-day", null, start + HOUR, tied, CheckInContextStartBasis.DAY_START),
                    boundary("a-wake", null, start + HOUR, tied, CheckInContextStartBasis.WAKE_TIME),
                    linked("later", "a-wake", tied, start + 5 * HOUR),
                ),
            ),
        ).neighborhood("end")
        assertEquals(listOf("end"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.DISCONNECTED_AT_BOUNDARY, neighborhood.stop.reason)
    }

    @Test
    fun aMixedTiedCohortStaysAnOrdinaryMissingEdge() {
        val start = 43L * DAY
        val tied = start + 3 * HOUR
        listOf("a-wake" to "z-later", "z-wake" to "a-later").forEach { (boundaryId, ordinaryId) ->
            val neighborhood = project(
                dataset(
                    states = listOf(
                        state("origin", start, mood = 1),
                        state("end", start + HOUR, mood = 2),
                        state("side", start, mood = 3),
                        state(boundaryId, tied, mood = 4),
                        state(ordinaryId, tied, mood = 5),
                    ),
                    intervals = listOf(
                        linked("end", "origin", start, start + HOUR),
                        boundary(boundaryId, null, start + HOUR, tied, CheckInContextStartBasis.WAKE_TIME),
                        linked(ordinaryId, "side", start, tied),
                    ),
                ),
            ).neighborhood("end")
            assertEquals(boundaryId, listOf("end"), neighborhood.hops.map { it.transition.key.raw })
            assertEquals(boundaryId, SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR, neighborhood.stop.reason)
            assertFalse(successorCompactLabel(neighborhood).contains("wake or day start"))
        }
    }

    @Test
    fun tiedOrdinaryCheckInsStayOrdinaryWhenABoundaryFollows() {
        val start = 48L * DAY
        val tied = start + 3 * HOUR
        val neighborhood = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("end", start + HOUR, mood = 2),
                    state("left-side", start, mood = 3),
                    state("right-side", start, mood = 4),
                    state("a-later", tied, mood = 5),
                    state("z-later", tied, mood = 6),
                    state("morning", start + 5 * HOUR, mood = 7),
                ),
                intervals = listOf(
                    linked("end", "origin", start, start + HOUR),
                    linked("a-later", "left-side", start, tied),
                    linked("z-later", "right-side", start, tied),
                    boundary("morning", "a-later", tied, start + 5 * HOUR, CheckInContextStartBasis.DAY_START),
                ),
            ),
        ).neighborhood("end")
        assertEquals(listOf("end"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR, neighborhood.stop.reason)
    }

    @Test
    fun anInvalidBoundaryIsNotAdoptedAsBoundaryReached() {
        val start = 41L * DAY
        val neighborhood = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("late", start + 5 * HOUR, mood = 8),
                    state("early", start + HOUR, mood = 2),
                ),
                intervals = listOf(
                    linked("late", "origin", start, start + 5 * HOUR),
                    boundary("early", "late", start, start + HOUR, CheckInContextStartBasis.WAKE_TIME),
                ),
            ),
        ).neighborhood("late")
        assertEquals(listOf("late"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals(SuccessorStopReason.INVALID_CHRONOLOGY, neighborhood.stop.reason)
        assertEquals("early", neighborhood.stop.stoppingTransitionKey?.raw)
    }

    @Test
    fun choiceAndOutcomeWindowsKeepTheirBoundaries() {
        val start = 40L * DAY
        val data = dataset(
            states = listOf(
                state("past", start - HOUR, mood = 1),
                state("origin", start, mood = 2),
                state("near", start + 2 * HOUR, mood = 3),
                state("exact", start + 6 * HOUR, mood = 4),
                state("far", start + 10 * HOUR, mood = 5),
                state("after", start + 10 * HOUR, mood = 7),
                state("horizon", start + 8 * DAY, mood = 6),
            ),
            intervals = listOf(
                linked("origin", "past", start - HOUR, start),
                linked("near", "origin", start, start + 2 * HOUR),
                linked("exact", "origin", start, start + 6 * HOUR),
                linked("far", "origin", start + 3 * HOUR, start + 10 * HOUR),
                linked("after", "exact", start + 6 * HOUR, start + 10 * HOUR),
            ),
        )
        val sixHours = project(data).neighborhood("near").windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertEquals(1.0, sixHours.coverage, 0.0)
        assertFalse(sixHours.rightCensored)
        assertEquals(listOf("exact", "far", "near"), sixHours.choices.intervals.map { it.key.raw }.sorted())
        assertFalse(sixHours.choices.intervals.any { it.key.raw == "after" || it.key.raw == "origin" })
        assertEquals(listOf("exact", "near"), sixHours.outcomes.intervals.map { it.key.raw }.sorted())
        assertTrue(sixHours.choices.intervals.any { it.key.raw == "near" })
        assertTrue(sixHours.outcomes.intervals.any { it.key.raw == "near" })
        assertTrue(sixHours.choices.intervals.any { it.key.raw == "far" })
        assertFalse(sixHours.outcomes.intervals.any { it.key.raw == "far" })
        val day = project(data).neighborhood("near").windows.single { it.scale == SuccessorWindowScale.FOLLOWING_24_HOURS }
        assertTrue(day.choices.intervals.any { it.key.raw == "after" })
        assertTrue(day.outcomes.intervals.any { it.key.raw == "far" })
    }

    @Test
    fun aFullyObservedEmptyWindowDiffersFromACensoredOne() {
        val start = 41L * DAY
        val covered = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("end", start + 8 * DAY, mood = 2),
                ),
                intervals = listOf(linked("end", "origin", start - HOUR, start + 8 * DAY)),
            ),
        ).neighborhood("end")
        val sixHours = covered.windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertEquals(1.0, sixHours.coverage, 0.0)
        assertFalse(sixHours.rightCensored)
        assertTrue(sixHours.choices.intervals.isEmpty())
        assertTrue(sixHours.choicesAreObservedZero)

        val censored = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("end", start + HOUR, mood = 2),
                ),
                intervals = listOf(linked("end", "origin", start - HOUR, start + HOUR)),
            ),
        ).neighborhood("end")
        val partial = censored.windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertTrue(partial.rightCensored)
        assertTrue(partial.coverage > 0.0 && partial.coverage < 1.0)
        assertTrue(partial.choices.intervals.isEmpty())
        assertFalse(partial.choicesAreObservedZero)
        assertEquals(listOf("end"), partial.outcomes.intervals.map { it.key.raw })

        val closed = project(
            dataset(
                states = listOf(
                    state("origin", start, mood = 1),
                    state("end", start, mood = 2),
                ),
                intervals = listOf(linked("end", "origin", start, start)),
            ),
        ).neighborhood("end")
        val none = closed.windows.single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
        assertEquals(0.0, none.coverage, 0.0)
        assertTrue(none.rightCensored)
        assertTrue(none.choices.intervals.isEmpty())
        assertFalse(none.choicesAreObservedZero)
        assertEquals(SuccessorStopReason.INVALID_CHRONOLOGY, closed.stop.reason)
    }

    @Test
    fun laterDataExtendsTheNeighborhoodAndEarlierHistoryDoesNotRewriteThePath() {
        val start = 42L * DAY
        val original = dataset(
            states = listOf(
                state("origin", start, mood = 1),
                state("end", start + HOUR, mood = 2),
            ),
            intervals = listOf(linked("end", "origin", start, start + HOUR)),
        )
        val before = project(original).neighborhood("end")
        val extended = original.copy(
            states = original.states + state("later", start + 3 * HOUR, mood = 3),
            intervals = original.intervals + linked("later", "end", start + HOUR, start + 3 * HOUR),
        )
        val after = project(extended).neighborhood("end")
        assertEquals(listOf("end", "later"), after.hops.map { it.transition.key.raw })
        assertTrue(after.windows.first().coverage > before.windows.first().coverage)

        val withPredecessor = extended.copy(
            states = listOf(state("past", start - 2 * HOUR, mood = 4)) + extended.states,
            intervals = listOf(linked("origin", "past", start - 2 * HOUR, start)) + extended.intervals,
        )
        assertEquals(after, project(withPredecessor).neighborhood("end"))
        val relinked = withPredecessor.copy(
            intervals = withPredecessor.intervals.map { interval ->
                if (interval.endingCheckInId == "end") interval.copy(previousCheckInId = "past") else interval
            },
        )
        assertEquals("past", project(relinked).neighborhood("end").focal?.stateKey?.raw)
    }

    @Test
    fun missingConflictAndZeroStayDistinctForAxesAndQuantities() {
        val start = 43L * DAY
        val data = dataset(
            states = listOf(
                state("origin", start, mood = 2),
                state(
                    "end",
                    start + HOUR,
                    mood = 0,
                    confidence = 4,
                    anxiety = 4,
                    conflicting = listOf(DefaultCheckInAxes.CONFIDENCE),
                    emotion = "tense",
                ),
            ),
            intervals = listOf(
                linked(
                    "end",
                    "origin",
                    start,
                    start + HOUR,
                    usage = UsageCategoryObservationV2(
                        CategoryObservationKindV2.ENTRIES,
                        start + HOUR,
                        listOf(
                            UsageObservationV2("blank", "tea", "Tea", "supportive", "", start),
                            UsageObservationV2("zero", "water", "Water", "supportive", "0", start),
                            UsageObservationV2("bad", "dust", "Dust", "supportive", "nope", start),
                            UsageObservationV2("mg", "caffeine", "Caffeine", "coping", "1 mg", start),
                            UsageObservationV2("ml", "caffeine", "Caffeine", "coping", "1 ml", start),
                        ),
                    ),
                ),
            ),
        )
        val hop = project(data).neighborhood("end").hops.single()
        val successor = checkNotNull(hop.successor)
        val axes = successor.axes
        assertEquals(FocalAxisStatus.RECORDED, axes.single { it.axisId == DefaultCheckInAxes.MOOD }.status)
        assertEquals(0, axes.single { it.axisId == DefaultCheckInAxes.MOOD }.raw)
        assertEquals(FocalAxisStatus.MISSING, axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.status)
        assertNull(axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.raw)
        assertEquals(FocalAxisStatus.CONFLICTING, axes.single { it.axisId == DefaultCheckInAxes.CONFIDENCE }.status)
        assertNull(axes.single { it.axisId == DefaultCheckInAxes.CONFIDENCE }.raw)
        assertEquals("tense", successor.emotion)
        val amounts = hop.transition.exposures.filterIsInstance<UsageBehavior>()
        assertEquals(UsageQuantityStatus.MISSING, amounts.single { it.identity.key == "option:tea" }.amounts.single().status)
        val zero = amounts.single { it.identity.key == "option:water" }
        assertEquals(UsageQuantityStatus.PARSED, zero.amounts.single().status)
        assertEquals(0.0, checkNotNull(zero.amounts.single().numericAmount), 0.0)
        assertEquals(UsageQuantityStatus.UNPARSED, amounts.single { it.identity.key == "option:dust" }.amounts.single().status)
        assertTrue(amounts.single { it.identity.key == "option:dust" }.comparable.isEmpty())
        val caffeine = amounts.single { it.identity.key == "option:caffeine" }
        assertEquals(setOf("mg", "ml"), caffeine.comparable.map { it.unit }.toSet())
        assertTrue(caffeine.comparable.all { it.total == 1.0 })
        val counted = project(data).neighborhood("end").windows
            .single { it.scale == SuccessorWindowScale.FOLLOWING_6_HOURS }
            .choices.endingAxes
        assertEquals(1, counted.single { it.axisId == DefaultCheckInAxes.MOOD }.recordedZeroCount)
        assertEquals(1, counted.single { it.axisId == DefaultCheckInAxes.ENERGY }.missingCount)
        assertEquals(1, counted.single { it.axisId == DefaultCheckInAxes.CONFIDENCE }.conflictingCount)
    }

    @Test
    fun theContractStaysRetrospectiveAndLeavesTheLockedForecastsUnchanged() {
        val data = chain()
        val bundle = projectForecastExamples(data)
        val neighborhoods = projectCausalPredecessorNeighborhood(data, zone)
        val nullBefore = projectNullTrajectoryLearner(
            bundle.stateForecasts.examples,
            bundle.conditionalTransitions.examples,
            zone,
        )
        val predecessorBefore = projectPredecessorContextLearner(
            bundle.stateForecasts.examples,
            bundle.conditionalTransitions.examples,
            neighborhoods,
            zone,
        )
        val successor = project(data)
        val nullAfter = projectNullTrajectoryLearner(
            bundle.stateForecasts.examples,
            bundle.conditionalTransitions.examples,
            zone,
        )
        val predecessorAfter = projectPredecessorContextLearner(
            bundle.stateForecasts.examples,
            bundle.conditionalTransitions.examples,
            neighborhoods,
            zone,
        )
        assertEquals(nullBefore, nullAfter)
        assertEquals(predecessorBefore, predecessorAfter)
        assertEquals(NULL_TRAJECTORY_PROTOCOL, nullBefore.protocol)
        assertEquals(PREDECESSOR_CONTEXT_PROTOCOL, predecessorBefore.protocol)
        assertEquals(RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_PROTOCOL, successor.protocol)
        assertTrue(successor.retrospective && successor.containsFuture && successor.containsOutcome)
        assertFalse(successor.inputPastOnly)
        assertFalse(successor.predictive || successor.updatesModel || successor.suitableForRecommendation)
        assertEquals(ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION, successor.availabilityMode)
        assertFalse(successor.strictAsKnownAvailability)
        assertTrue(successor.cutoffRule.contains("latest stored check-in recordedAt"))
        assertTrue(successor.cutoffRule.contains("not wall-clock now"))
        val fields = RetrospectiveSuccessorNeighborhood::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { name ->
            listOf("forecast", "residual", "coefficient", "coordinate", "cluster").any { name.contains(it) }
        })
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveSuccessorNeighborhood.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/RetrospectiveSuccessorNeighborhood.kt",
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
        ).forEach { (primary, fallback) ->
            val text = source(primary, fallback).readText()
            assertFalse(text.contains("retrospective-successor-neighborhood"))
            assertFalse(text.contains("projectRetrospectiveSuccessorNeighborhood"))
        }
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("Retrospective successor neighborhood"))
        val replay = lab.substringAfter("fun RecordedReplayPane").substringBefore("fun PredictionSandboxPane")
        assertTrue(replay.contains("RetrospectiveSuccessorSection"))
        assertFalse(replay.contains("horizonHours"))
        assertFalse(replay.contains("projectExpertHorizon"))
        val architecture = source("docs/architecture.md", "../docs/architecture.md").readText()
        assertTrue(architecture.contains("retrospective-successor-neighborhood-v2"))
        assertTrue(architecture.contains("bidirectional-smoothed-state"))
    }

    private fun project(dataset: CheckInAnalysisDatasetV2) = projectRetrospectiveSuccessorNeighborhood(dataset)

    private fun RetrospectiveSuccessorNeighborhoodDataset.neighborhood(id: String) =
        neighborhoods.single { it.transitionId == id }

    private fun chain(): CheckInAnalysisDatasetV2 {
        val start = 44L * DAY
        return dataset(
            states = listOf(
                state("a", start, mood = 1),
                state("b", start + HOUR, mood = 2),
                state("c", start + 2 * HOUR, mood = 3),
                state("d", start + 3 * HOUR, mood = 4),
            ),
            intervals = listOf(
                linked("b", "a", start, start + HOUR),
                linked("c", "b", start + HOUR, start + 2 * HOUR),
                linked("d", "c", start + 2 * HOUR, start + 3 * HOUR),
            ),
        )
    }

    private fun unresolvedSource(): CheckInAnalysisDatasetV2 {
        val start = 45L * DAY
        return dataset(
            states = listOf(state("end", start + HOUR, mood = 1)),
            intervals = listOf(linked("end", "ghost", start, start + HOUR)),
        )
    }

    private fun cycle(): CheckInAnalysisDatasetV2 {
        val start = 46L * DAY
        return dataset(
            states = listOf(
                state("a", start, mood = 1),
                state("b", start + HOUR, mood = 2),
                state("c", start + 2 * HOUR, mood = 3),
            ),
            intervals = listOf(
                linked("b", "a", start, start + HOUR),
                linked("c", "b", start + HOUR, start + 2 * HOUR),
                linked("a", "c", start + 2 * HOUR, start),
            ),
        )
    }

    private fun incomplete(): CheckInAnalysisDatasetV2 {
        val start = 47L * DAY
        return dataset(
            states = listOf(
                state("a", start, mood = 1),
                state("b", start + HOUR, mood = 2),
                state("c", start + 2 * HOUR, mood = 3),
            ),
            intervals = listOf(
                linked("b", "a", start, start + HOUR, complete = false, activity = unreviewedActivity()),
                linked("c", "b", start + HOUR, start + 2 * HOUR, activity = unreviewedActivity()),
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
        confidence: Int? = null,
        anxiety: Int? = null,
        emotion: String = "",
        conflicting: List<String> = emptyList(),
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
            DefaultCheckInAxes.CONFIDENCE to confidence,
            DefaultCheckInAxes.ANXIETY to anxiety,
        ).mapNotNull { (axis, raw) ->
            if (axis in conflicting) null else reading(axis, raw)
        }
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
            conflictingAxisIds = conflicting,
            emotion = emotion,
        )
    }

    private fun linked(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        complete: Boolean = true,
        activity: ActivityCategoryObservationV2 = reviewedNoneActivity(endAt),
        usage: UsageCategoryObservationV2 = reviewedNoneUsage(endAt),
    ) = interval(end, previous, start, endAt, CheckInContextStartBasis.PREVIOUS_CHECK_IN, complete, activity, usage)

    private fun boundary(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
    ) = interval(end, previous, start, endAt, basis, true, reviewedNoneActivity(endAt), reviewedNoneUsage(endAt))

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
        complete: Boolean,
        activity: ActivityCategoryObservationV2,
        usage: UsageCategoryObservationV2,
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = basis,
        completedAt = if (complete) endAt else null,
        activity = activity,
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = usage,
    )

    private fun reviewedNoneActivity(at: Long) =
        ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, at, emptyList())

    private fun reviewedNoneUsage(at: Long) =
        UsageCategoryObservationV2(CategoryObservationKindV2.NONE, at, emptyList())

    private fun unreviewedActivity() =
        ActivityCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, null, emptyList())

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
