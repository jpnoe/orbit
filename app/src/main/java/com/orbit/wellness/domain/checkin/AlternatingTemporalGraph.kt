package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis

/**
 * Occurrence graph over one [CheckInAnalysisDatasetV2].
 *
 * A [StateOccurrenceNode] is one saved check-in. A [TransitionOccurrenceNode]
 * is one stored context interval, keyed by the same ending check-in id the
 * analysis already uses. Identical axis values do not merge state nodes.
 * Nothing here is clustered, averaged, or given a coordinate, and nothing is
 * written back to Room.
 *
 * The lists are ordered by recorded time and then by id. That order does not
 * change an id or which endpoint was stored as the start. One state can have
 * several outgoing intervals, because a later check-in does not rewrite an
 * earlier window. [CheckInAnalysisDatasetV2.outgoingInterval] still returns
 * only the first of those.
 *
 * A later message-passing pass can read a state together with its incoming and
 * outgoing transition ids, or a transition together with the states its
 * endpoints resolve to. [CausalTransitionInput] is the past-only view: it has
 * the starting state and the raw interval, and it does not carry the ending
 * state. This pass does not update either representation.
 */
data class AlternatingTemporalGraph(
    val states: List<StateOccurrenceNode>,
    val transitions: List<TransitionOccurrenceNode>,
    /** One step per transition, in the same order. Ineligible steps are retained. */
    val causalSteps: List<CausalStep>,
    /** The dataset's issue list, in its existing order. */
    val issues: List<AnalysisIssue>,
) {
    fun state(id: String): StateOccurrenceNode? = states.firstOrNull { it.id == id }

    fun transition(id: String): TransitionOccurrenceNode? = transitions.firstOrNull { it.id == id }
}

data class StateOccurrenceNode(
    /** [StateObservationV2.checkInId]. Distinct from a transition that shares this string. */
    val id: String,
    val state: StateObservationV2,
    val incomingTransitionIds: List<String>,
    val outgoingTransitionIds: List<String>,
)

/**
 * One context interval. [id] is [ContextIntervalObservationV2.endingCheckInId].
 *
 * [startStateId] is set only when [ContextIntervalObservationV2.previousCheckInId]
 * names a state in this graph. A wake-time or day-start interval, and a previous
 * id that does not resolve, keep [startStateId] null. The unresolved id stays
 * on [interval] and is not turned into a state. [linkedTransition] is the
 * dataset's resolved link, including a link whose previous check-in is not
 * earlier and a link whose delta map is empty. Those stored endpoints are not
 * swapped.
 */
data class TransitionOccurrenceNode(
    val id: String,
    val interval: ContextIntervalObservationV2,
    val linkedTransition: StateTransitionObservationV2?,
    val startStateId: String?,
    val endStateId: String,
) {
    init {
        require(id == interval.endingCheckInId)
        require(endStateId == interval.endingCheckInId)
        if (linkedTransition != null) {
            require(linkedTransition.previous.checkInId == startStateId)
            require(linkedTransition.ending.checkInId == endStateId)
            require(linkedTransition.interval.endingCheckInId == id)
        }
    }
}

/**
 * Both temporal directions around one state occurrence.
 * Neighbor ids are one hop away. This is not a feature vector.
 */
data class RetrospectiveNeighborhood(
    val focalStateId: String,
    val incomingTransitionIds: List<String>,
    val outgoingTransitionIds: List<String>,
    val incomingStateIds: List<String>,
    val outgoingStateIds: List<String>,
)

/**
 * What a predictive encoder is allowed to read for one interval.
 *
 * [startingState] is absent when the interval has no resolved start. [interval]
 * is the raw context. The ending check-in's psychological observation is not a
 * field of this object. Deltas are not included, because a delta stores the
 * ending raw value. A retrospective neighborhood is not included either.
 */
data class CausalTransitionInput(
    val transitionId: String,
    val startingState: StateObservationV2?,
    val interval: ContextIntervalObservationV2,
)

/** The ending observation for one interval. Requested separately from [CausalTransitionInput]. */
data class CausalPredictionTarget(
    val transitionId: String,
    val endingState: StateObservationV2,
)

data class CausalEligibility(
    val eligible: Boolean,
    val reasons: List<CausalIneligibilityReason>,
) {
    init {
        require(eligible == reasons.isEmpty()) {
            "Eligibility and its reasons disagree"
        }
    }
}

/**
 * Why a step is kept in the graph but is not a state-to-state training example.
 *
 * [INCONSISTENT_CONTEXT] follows the analysis issues that say the interval
 * disagrees with itself: the window end differs from the check-in time, a
 * category status disagrees with its items, a completed interval still has an
 * unreviewed category, or a category kind is [CategoryObservationKindV2.CONFLICTING].
 * A missing category row stays [CategoryObservationKindV2.ABSENT] and is not one
 * of these reasons. An empty delta map is not a reason either.
 */
enum class CausalIneligibilityReason {
    BOUNDARY_INTERVAL,
    START_UNRESOLVED,
    INVALID_CHRONOLOGY,
    INCOMPLETE_CONTEXT,
    INCONSISTENT_CONTEXT,
}

data class CausalStep(
    val input: CausalTransitionInput,
    val target: CausalPredictionTarget,
    val eligibility: CausalEligibility,
)

fun projectAlternatingTemporalGraph(dataset: CheckInAnalysisDatasetV2): AlternatingTemporalGraph {
    val stateById = dataset.states.associateBy { it.checkInId }
    val linkedById = dataset.transitions.associateBy { it.interval.endingCheckInId }
    val transitions = dataset.intervals.map { interval ->
        val ending = stateById.getValue(interval.endingCheckInId)
        val previousId = interval.previousCheckInId
        val start = previousId?.let { stateById[it] }
        TransitionOccurrenceNode(
            id = interval.endingCheckInId,
            interval = interval,
            linkedTransition = linkedById[interval.endingCheckInId],
            startStateId = start?.checkInId,
            endStateId = ending.checkInId,
        )
    }.sortedWith(compareBy({ stateById.getValue(it.endStateId).recordedAt }, { it.id }))

    val incoming = transitions.groupBy { it.endStateId }
    val outgoing = transitions
        .filter { it.startStateId != null }
        .groupBy { checkNotNull(it.startStateId) }
    val states = dataset.states
        .sortedWith(compareBy({ it.recordedAt }, { it.checkInId }))
        .map { state ->
            StateOccurrenceNode(
                id = state.checkInId,
                state = state,
                incomingTransitionIds = incoming[state.checkInId].orEmpty().map { it.id },
                outgoingTransitionIds = outgoing[state.checkInId].orEmpty().map { it.id },
            )
        }
    val steps = transitions.map { transition ->
        val ending = stateById.getValue(transition.endStateId)
        val starting = transition.startStateId?.let { stateById.getValue(it) }
        CausalStep(
            input = CausalTransitionInput(
                transitionId = transition.id,
                startingState = starting,
                interval = transition.interval,
            ),
            target = CausalPredictionTarget(
                transitionId = transition.id,
                endingState = ending,
            ),
            eligibility = causalEligibility(transition, starting, ending, dataset.issues),
        )
    }
    return AlternatingTemporalGraph(
        states = states,
        transitions = transitions,
        causalSteps = steps,
        issues = dataset.issues,
    )
}

fun AlternatingTemporalGraph.retrospectiveNeighborhood(stateId: String): RetrospectiveNeighborhood? {
    val focal = state(stateId) ?: return null
    val byId = transitions.associateBy { it.id }
    return RetrospectiveNeighborhood(
        focalStateId = focal.id,
        incomingTransitionIds = focal.incomingTransitionIds,
        outgoingTransitionIds = focal.outgoingTransitionIds,
        incomingStateIds = focal.incomingTransitionIds.mapNotNull { byId[it]?.startStateId },
        outgoingStateIds = focal.outgoingTransitionIds.map { id ->
            checkNotNull(byId[id]).endStateId
        },
    )
}

private val INCONSISTENT_CONTEXT_ISSUES = setOf(
    AnalysisIssueCode.WINDOW_END_DISAGREES_WITH_CHECK_IN,
    AnalysisIssueCode.CATEGORY_STATUS_DISAGREES_WITH_ITEMS,
    AnalysisIssueCode.COMPLETED_INTERVAL_HAS_UNREVIEWED,
)

private fun causalEligibility(
    transition: TransitionOccurrenceNode,
    starting: StateObservationV2?,
    ending: StateObservationV2,
    issues: List<AnalysisIssue>,
): CausalEligibility {
    val reasons = mutableListOf<CausalIneligibilityReason>()
    val basis = transition.interval.startBasis
    val boundary = basis == CheckInContextStartBasis.WAKE_TIME ||
        basis == CheckInContextStartBasis.DAY_START
    if (boundary) reasons += CausalIneligibilityReason.BOUNDARY_INTERVAL
    if (!boundary && starting == null) reasons += CausalIneligibilityReason.START_UNRESOLVED
    if (starting != null && starting.recordedAt >= ending.recordedAt) {
        reasons += CausalIneligibilityReason.INVALID_CHRONOLOGY
    }
    if (!transition.interval.isComplete) reasons += CausalIneligibilityReason.INCOMPLETE_CONTEXT
    val conflicting = transition.interval.categoriesInOrder().any { category ->
        category.kind == CategoryObservationKindV2.CONFLICTING
    }
    val flagged = issues.any { issue ->
        issue.checkInId == transition.id && issue.code in INCONSISTENT_CONTEXT_ISSUES
    }
    if (conflicting || flagged) reasons += CausalIneligibilityReason.INCONSISTENT_CONTEXT
    return CausalEligibility(eligible = reasons.isEmpty(), reasons = reasons)
}
