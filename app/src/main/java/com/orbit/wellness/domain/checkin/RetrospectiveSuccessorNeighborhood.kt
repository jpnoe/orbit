package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextCategories

/**
 * One retrospective view of a recorded conditional transition.
 *
 * `retrospective-successor-neighborhood-v2` starts at that transition. Depth 0
 * is its focal starting state when the interval evidence has one. Depth 1 is
 * the selected transition itself, not the nearest later timestamp. Later
 * depths follow the unique graph outgoing edge until a wake or day-start.
 * That boundary stays as the terminal hop, without the check-in on its far
 * side, and the path does not enter the next waking segment. A selected
 * boundary stays at depth 1. A wake or day-start that is not graph-linked
 * stays out of that path and can still appear in a sampled window. The result
 * contains future behavior and outcomes. It is not a forecast input. End of
 * recorded history means the last adopted successor reaches the observable
 * cutoff. No outgoing edge while a later check-in exists is no graph-linked
 * successor. It is disconnected at a boundary only when every check-in at the
 * earliest later time ends a wake or day-start. Check-ins that share that
 * time are one cohort. Id order does not choose among them. A mixed cohort
 * keeps the ordinary missing-edge reason. An ordinary later check-in keeps
 * that reason even if a boundary is stored after it.
 *
 * The observable cutoff is the latest stored check-in `recordedAt` in the
 * reconstruction. It is not wall-clock now. Each future window ends at the
 * earlier of its requested end and that cutoff. Coverage is the effective
 * duration divided by the requested duration. The right edge is censored when
 * the cutoff is before the requested end. A dataset whose latest check-in is
 * the focal time has zero future coverage. Zero choices are an observed zero
 * only when the window is fully covered.
 */
const val RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_PROTOCOL = "retrospective-successor-neighborhood-v2"

const val RETROSPECTIVE_SUCCESSOR_CUTOFF_RULE =
    "The observable cutoff is the latest stored check-in recordedAt in this reconstruction. " +
        "It is not wall-clock now. A window's effective end is the earlier of the requested end and that cutoff. " +
        "Coverage is the effective duration divided by the requested duration. " +
        "The right edge is censored when the cutoff is before the requested end. " +
        "A latest check-in at the focal time gives zero future coverage. " +
        "Zero choices are an observed zero only when coverage is complete. " +
        "A censored window with no choices is not evidence that no choice occurs."

const val RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_ASSUMPTIONS =
    "Retrospective event-time reconstruction of one selected transition, its graph successor path, " +
        "and sampled future windows. Strict as-known availability is false. A later or backdated record can change it. " +
        "This contains recorded future behavior and outcomes. It is not a forecast input, does not update a model, " +
        "and is not for recommendation. " +
        RETROSPECTIVE_SUCCESSOR_CUTOFF_RULE + " " +
        "Depth 1 is the selected recorded transition. Later depths follow one graph outgoing edge. " +
        "A unique valid graph-linked wake or day-start is the terminal boundary hop. " +
        "The check-in on the far side of that boundary is not adopted, and the next waking segment is not entered. " +
        "A selected boundary stays at depth 1. Several outgoing edges stay ambiguous before a boundary is chosen. " +
        "Invalid chronology is not adopted as a boundary. " +
        "A wake or day-start that is not graph-linked is not adopted as that edge. " +
        "Equal-timestamp candidates stay in occurrence-identity order, which is not a temporal order. " +
        "End of recorded history means the last adopted successor reaches the observable cutoff. " +
        "No outgoing edge while a later check-in exists is no graph-linked successor. " +
        "That stop is disconnected at a boundary only when every check-in at the earliest later time ends a wake or day-start. " +
        "Check-ins that share that time are one cohort, and id order does not choose among them. " +
        "A mixed cohort stays no graph-linked successor. " +
        "An ordinary later check-in stays no graph-linked successor even when a boundary is stored after it. " +
        "Sampled windows may still cross a boundary. " +
        "Reviewed absence stays a property of a recorded interval. " +
        "Deferred and not implemented: bidirectional-smoothed-state and a past-only successor-cluster model."

enum class SuccessorStopReason {
    END_OF_RECORDED_HISTORY,
    NO_GRAPH_LINKED_SUCCESSOR,
    DISCONNECTED_AT_BOUNDARY,
    BOUNDARY_REACHED,
    AMBIGUOUS_SUCCESSOR,
    UNRESOLVED,
    INVALID_CHRONOLOGY,
    CYCLE,
}

enum class SuccessorWindowScale(val requestedDurationMillis: Long) {
    FOLLOWING_6_HOURS(6L * 3_600_000L),
    FOLLOWING_24_HOURS(24L * 3_600_000L),
    FOLLOWING_7_DAYS(7L * 24L * 3_600_000L),
}

fun projectRetrospectiveSuccessorNeighborhood(
    dataset: CheckInAnalysisDatasetV2,
): RetrospectiveSuccessorNeighborhoodDataset {
    val graph = projectAlternatingTemporalGraph(dataset)
    val evidence = projectIntervalBehaviorEvidence(graph, dataset)
    val statesById = graph.states.associateBy { it.id }
    val cutoff = graph.states.maxOfOrNull { it.state.recordedAt }
    val neighborhoods = graph.causalSteps.map { step ->
        projectOne(step, graph, statesById, evidence, cutoff)
    }
    return RetrospectiveSuccessorNeighborhoodDataset(
        observableCutoffAt = cutoff,
        neighborhoods = neighborhoods,
    )
}

data class RetrospectiveSuccessorNeighborhoodDataset(
    val protocol: String = RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_PROTOCOL,
    val retrospective: Boolean = true,
    val containsFuture: Boolean = true,
    val containsOutcome: Boolean = true,
    val inputPastOnly: Boolean = false,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val availabilityMode: ObservationAvailabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
    val strictAsKnownAvailability: Boolean = false,
    val cutoffRule: String = RETROSPECTIVE_SUCCESSOR_CUTOFF_RULE,
    val observableCutoffAt: Long?,
    val neighborhoods: List<RetrospectiveSuccessorNeighborhood>,
) {
    init {
        require(protocol == RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_PROTOCOL)
        require(retrospective && containsFuture && containsOutcome)
        require(!inputPastOnly && !predictive && !updatesModel && !suitableForRecommendation)
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION)
        require(!strictAsKnownAvailability)
        require(cutoffRule == RETROSPECTIVE_SUCCESSOR_CUTOFF_RULE)
        require(neighborhoods.all { it.observableCutoffAt == observableCutoffAt })
    }
}

data class RetrospectiveSuccessorNeighborhood(
    val transitionId: String,
    val focal: SuccessorStateView?,
    val anchorAt: Long,
    val anchorIsFocalState: Boolean,
    val unresolvedSourceId: String?,
    val observableCutoffAt: Long?,
    val endingAt: Long,
    val hops: List<SuccessorHop>,
    val stop: SuccessorStop,
    val windows: List<SuccessorWindow>,
) {
    init {
        require(hops.isNotEmpty()) { "The selected transition is depth 1" }
        require(hops.map { it.depth } == (1..hops.size).toList())
        require(hops.first().transition.key.raw == transitionId)
        require(stop.depth == hops.size)
        require(anchorIsFocalState == (focal != null))
        require(focal == null || anchorAt == focal.recordedAt)
        require(focal != null || anchorAt == hops.first().transition.startAt)
        require(unresolvedSourceId == null || focal == null)
        require(windows.map { it.scale } == SuccessorWindowScale.entries)
        require(endingAt == hops.first().transition.endAt)
        hops.zipWithNext { current, next ->
            require(next.source?.stateKey == current.successor?.stateKey) {
                "The next hop leaves the successor just adopted"
            }
        }
    }
}

data class SuccessorStateView(
    val stateKey: StateOccurrenceKey,
    val recordedAt: Long,
    val axes: List<FocalAxisCoverage>,
    val emotionPresent: Boolean,
    val emotion: String?,
) {
    init {
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(emotionPresent == (emotion != null))
    }
}

data class SuccessorHop(
    val depth: Int,
    val transition: IntervalBehaviorEvidence,
    val source: SuccessorStateView?,
    val successor: SuccessorStateView?,
) {
    init {
        require(depth >= 1)
    }
}

data class SuccessorCandidate(
    val transitionKey: TransitionOccurrenceKey,
    val endStateId: String,
    val startAt: Long,
    val endAt: Long,
)

data class SuccessorStop(
    val reason: SuccessorStopReason,
    val depth: Int,
    val stoppingTransitionKey: TransitionOccurrenceKey?,
    val unresolvedSuccessorId: String?,
    val candidates: List<SuccessorCandidate>,
    val eligibility: CausalEligibility?,
) {
    init {
        require(depth >= 1)
        require(reason != SuccessorStopReason.AMBIGUOUS_SUCCESSOR || candidates.size > 1)
        require(candidates == candidates.sortedBy { it.transitionKey.raw }) {
            "Candidates stay in occurrence-identity order"
        }
        require(reason != SuccessorStopReason.UNRESOLVED || unresolvedSuccessorId != null)
    }
}

data class SuccessorWindow(
    val scale: SuccessorWindowScale,
    val anchorAt: Long,
    val requestedEndAt: Long,
    val observableCutoffAt: Long?,
    val effectiveEndAt: Long,
    val requestedDurationMillis: Long,
    val effectiveDurationMillis: Long,
    val coverage: Double,
    val rightCensored: Boolean,
    val choicesAreObservedZero: Boolean,
    val choices: SuccessorPopulation,
    val outcomes: SuccessorPopulation,
) {
    init {
        require(requestedDurationMillis == scale.requestedDurationMillis)
        require(requestedEndAt == anchorAt + requestedDurationMillis)
        require(effectiveDurationMillis >= 0L)
        require(effectiveDurationMillis == (effectiveEndAt - anchorAt).coerceAtLeast(0L))
        require(coverage.isFinite() && coverage in 0.0..1.0)
        require(coverage == effectiveDurationMillis.toDouble() / requestedDurationMillis.toDouble())
        require(rightCensored == (observableCutoffAt == null || observableCutoffAt < requestedEndAt))
        require(choicesAreObservedZero == (!rightCensored && choices.intervals.isEmpty()))
        require(choices.intervals.all { it.startAt >= anchorAt && it.startAt < effectiveEndAt })
        require(outcomes.intervals.all { it.endAt > anchorAt && it.endAt <= effectiveEndAt })
    }
}

data class SuccessorPopulation(
    val intervals: List<IntervalBehaviorEvidence>,
    val distinctTimestampCount: Int,
    val completedCount: Int,
    val incompleteCount: Int,
    val categories: List<PredecessorCategoryCount>,
    val exposures: List<PredecessorExposureCount>,
    val endingAxes: List<SuccessorAxisCount>,
) {
    init {
        require(distinctTimestampCount >= 0)
        require(completedCount + incompleteCount == intervals.size)
        require(completedCount == intervals.count { it.completed })
        require(categories.map { it.category } == CheckInContextCategories.ALL)
        require(endingAxes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
    }
}

data class SuccessorAxisCount(
    val axisId: String,
    val recordedCount: Int,
    val missingCount: Int,
    val conflictingCount: Int,
    val recordedZeroCount: Int,
) {
    init {
        require(recordedCount >= 0 && missingCount >= 0 && conflictingCount >= 0)
        require(recordedZeroCount in 0..recordedCount)
    }
}

private fun projectOne(
    step: CausalStep,
    graph: AlternatingTemporalGraph,
    statesById: Map<String, StateOccurrenceNode>,
    evidence: List<IntervalBehaviorEvidence>,
    cutoff: Long?,
): RetrospectiveSuccessorNeighborhood {
    val node = graph.transitions.single { it.id == step.input.transitionId }
    val selected = evidence.single { row ->
        row.key.raw == node.id &&
            row.startAt == node.interval.windowStartAt &&
            row.endAt == node.interval.windowEndAt &&
            row.durationMillis == node.interval.durationMillis
    }
    val (hops, stop) = orderedPath(node, selected, graph, statesById, evidence, cutoff)
    val focal = hops.first().source
    val anchorAt = focal?.recordedAt ?: selected.startAt
    val windows = SuccessorWindowScale.entries.map { scale ->
        window(scale, anchorAt, cutoff, evidence, statesById)
    }
    val unresolvedSourceId = if (
        selected.startStateKey == null &&
        node.startStateId == null &&
        node.interval.previousCheckInId != null
    ) {
        node.interval.previousCheckInId
    } else {
        null
    }
    return RetrospectiveSuccessorNeighborhood(
        transitionId = selected.key.raw,
        focal = focal,
        anchorAt = anchorAt,
        anchorIsFocalState = focal != null,
        unresolvedSourceId = unresolvedSourceId,
        observableCutoffAt = cutoff,
        endingAt = selected.endAt,
        hops = hops,
        stop = stop,
        windows = windows,
    )
}

private fun orderedPath(
    selectedNode: TransitionOccurrenceNode,
    selected: IntervalBehaviorEvidence,
    graph: AlternatingTemporalGraph,
    statesById: Map<String, StateOccurrenceNode>,
    evidence: List<IntervalBehaviorEvidence>,
    cutoff: Long?,
): Pair<List<SuccessorHop>, SuccessorStop> {
    val focalNode = selected.startStateKey?.raw?.let { statesById[it] }
    val focal = focalNode?.let { stateView(it.state) }
    val endNode = statesById[selected.endStateKey.raw]
    val hops = mutableListOf<SuccessorHop>()
    if (endNode == null) {
        hops += SuccessorHop(1, selected, focal, successor = null)
        return hops to stop(
            reason = SuccessorStopReason.UNRESOLVED,
            depth = 1,
            stoppingTransitionKey = selected.key,
            unresolvedSuccessorId = selected.endStateKey.raw,
            eligibility = selected.eligibility,
        )
    }
    val ending = endNode
    if (rejectedChronology(focalNode, ending, selected)) {
        hops += SuccessorHop(1, selected, focal, successor = null)
        return hops to stop(
            reason = SuccessorStopReason.INVALID_CHRONOLOGY,
            depth = 1,
            stoppingTransitionKey = selected.key,
            eligibility = selected.eligibility,
        )
    }
    if (isBoundary(selected)) {
        hops += SuccessorHop(1, selected, focal, successor = null)
        return hops to boundaryStop(selected, depth = 1)
    }
    hops += SuccessorHop(1, selected, focal, stateView(ending.state))
    val visitedStates = mutableSetOf<String>()
    val visitedTransitions = mutableSetOf(selected.key.raw)
    focalNode?.let { visitedStates += it.id }
    visitedStates += ending.id
    var cursor: StateOccurrenceNode = ending
    while (true) {
        val leaving = cursor
        val outgoing = graph.transitions
            .filter { it.startStateId == leaving.id }
            .sortedBy { it.id }
        if (outgoing.isEmpty()) {
            return hops to stop(noOutgoingReason(leaving, statesById, evidence, cutoff), hops.size)
        }
        if (outgoing.size > 1) {
            return hops to stop(
                reason = SuccessorStopReason.AMBIGUOUS_SUCCESSOR,
                depth = hops.size,
                candidates = outgoing.map { it.toCandidate() },
            )
        }
        val node = outgoing.single()
        val transition = evidence.single { row ->
            row.key.raw == node.id &&
                row.startAt == node.interval.windowStartAt &&
                row.endAt == node.interval.windowEndAt &&
                row.durationMillis == node.interval.durationMillis
        }
        if (node.id in visitedTransitions || node.endStateId in visitedStates) {
            return hops to stop(
                reason = SuccessorStopReason.CYCLE,
                depth = hops.size,
                stoppingTransitionKey = transition.key,
                eligibility = transition.eligibility,
            )
        }
        val next = statesById[node.endStateId]
        if (next == null) {
            return hops to stop(
                reason = SuccessorStopReason.UNRESOLVED,
                depth = hops.size,
                stoppingTransitionKey = transition.key,
                unresolvedSuccessorId = node.endStateId,
                eligibility = transition.eligibility,
            )
        }
        if (rejectedChronology(leaving, next, transition)) {
            return hops to stop(
                reason = SuccessorStopReason.INVALID_CHRONOLOGY,
                depth = hops.size,
                stoppingTransitionKey = transition.key,
                eligibility = transition.eligibility,
            )
        }
        if (isBoundary(transition)) {
            hops += SuccessorHop(hops.size + 1, transition, stateView(leaving.state), successor = null)
            return hops to boundaryStop(transition, hops.size)
        }
        hops += SuccessorHop(hops.size + 1, transition, stateView(leaving.state), stateView(next.state))
        visitedTransitions += node.id
        visitedStates += next.id
        cursor = next
    }
}

private fun noOutgoingReason(
    terminal: StateOccurrenceNode,
    statesById: Map<String, StateOccurrenceNode>,
    evidence: List<IntervalBehaviorEvidence>,
    cutoff: Long?,
): SuccessorStopReason {
    val terminalAt = terminal.state.recordedAt
    if (cutoff == null || terminalAt >= cutoff) return SuccessorStopReason.END_OF_RECORDED_HISTORY
    val later = statesById.values.filter { it.state.recordedAt > terminalAt }
    if (later.isEmpty()) return SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR
    val earliestAt = later.minOf { it.state.recordedAt }
    val cohort = later.filter { it.state.recordedAt == earliestAt }
    val boundaryCohort = cohort.all { state ->
        val rows = evidence.filter { it.endStateKey.raw == state.id }
        rows.isNotEmpty() && rows.all(::isBoundary)
    }
    return if (boundaryCohort) {
        SuccessorStopReason.DISCONNECTED_AT_BOUNDARY
    } else {
        SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR
    }
}

private fun isBoundary(transition: IntervalBehaviorEvidence): Boolean {
    return CausalIneligibilityReason.BOUNDARY_INTERVAL in transition.eligibility.reasons
}

private fun boundaryStop(transition: IntervalBehaviorEvidence, depth: Int) = stop(
    reason = SuccessorStopReason.BOUNDARY_REACHED,
    depth = depth,
    stoppingTransitionKey = transition.key,
    eligibility = transition.eligibility,
)

private fun rejectedChronology(
    source: StateOccurrenceNode?,
    successor: StateOccurrenceNode,
    transition: IntervalBehaviorEvidence,
): Boolean {
    val tooEarly = source != null && successor.state.recordedAt <= source.state.recordedAt
    return tooEarly || CausalIneligibilityReason.INVALID_CHRONOLOGY in transition.eligibility.reasons
}

private fun window(
    scale: SuccessorWindowScale,
    anchorAt: Long,
    cutoff: Long?,
    evidence: List<IntervalBehaviorEvidence>,
    statesById: Map<String, StateOccurrenceNode>,
): SuccessorWindow {
    val requested = scale.requestedDurationMillis
    val requestedEnd = anchorAt + requested
    val effectiveEnd = when {
        cutoff == null || cutoff <= anchorAt -> anchorAt
        cutoff >= requestedEnd -> requestedEnd
        else -> cutoff
    }
    val effective = (effectiveEnd - anchorAt).coerceAtLeast(0L)
    val censored = cutoff == null || cutoff < requestedEnd
    val choices = evidence
        .filter { it.startAt >= anchorAt && it.startAt < effectiveEnd }
        .sortedWith(compareBy({ it.startAt }, { it.endAt }, { it.key.raw }))
    val outcomes = evidence
        .filter { it.endAt > anchorAt && it.endAt <= effectiveEnd }
        .sortedWith(compareBy({ it.endAt }, { it.startAt }, { it.key.raw }))
    return SuccessorWindow(
        scale = scale,
        anchorAt = anchorAt,
        requestedEndAt = requestedEnd,
        observableCutoffAt = cutoff,
        effectiveEndAt = effectiveEnd,
        requestedDurationMillis = requested,
        effectiveDurationMillis = effective,
        coverage = effective.toDouble() / requested.toDouble(),
        rightCensored = censored,
        choicesAreObservedZero = !censored && choices.isEmpty(),
        choices = population(choices, statesById) { it.startAt },
        outcomes = population(outcomes, statesById) { it.endAt },
    )
}

private fun population(
    intervals: List<IntervalBehaviorEvidence>,
    statesById: Map<String, StateOccurrenceNode>,
    timestamp: (IntervalBehaviorEvidence) -> Long,
): SuccessorPopulation {
    return SuccessorPopulation(
        intervals = intervals,
        distinctTimestampCount = intervals.map(timestamp).toSet().size,
        completedCount = intervals.count { it.completed },
        incompleteCount = intervals.count { !it.completed },
        categories = categoryCounts(intervals),
        exposures = exposureCounts(intervals),
        endingAxes = axisCounts(intervals, statesById),
    )
}

private fun categoryCounts(members: List<IntervalBehaviorEvidence>): List<PredecessorCategoryCount> {
    val reviews = listOf(
        CheckInContextCategories.ACTIVITY to { interval: IntervalBehaviorEvidence -> interval.activityReview },
        CheckInContextCategories.WORKOUT to { interval: IntervalBehaviorEvidence -> interval.workoutReview },
        CheckInContextCategories.DIET to { interval: IntervalBehaviorEvidence -> interval.dietReview },
        CheckInContextCategories.USAGE to { interval: IntervalBehaviorEvidence -> interval.usageReview },
    )
    return reviews.map { (category, review) ->
        PredecessorCategoryCount(
            category = category,
            reviewedNone = members.count { review(it) == CategoryKnowledge.REVIEWED_NONE },
            reviewedEntries = members.count { review(it) == CategoryKnowledge.REVIEWED_ENTRIES },
            unknown = members.count { review(it) == CategoryKnowledge.UNKNOWN },
        )
    }
}

private fun exposureCounts(members: List<IntervalBehaviorEvidence>): List<PredecessorExposureCount> {
    data class Bucket(
        var intervals: Int = 0,
        val totals: MutableList<ComparableUsageTotal> = mutableListOf(),
        val minutes: MutableList<Int> = mutableListOf(),
    )
    val buckets = linkedMapOf<BehaviorIdentity, Bucket>()
    members.forEach { interval ->
        interval.exposures.groupBy { it.identity }.toSortedMap(
            compareBy({ it.kind.ordinal }, { it.key }, { it.intent ?: "" }),
        ).forEach { (identity, rows) ->
            val bucket = buckets.getOrPut(identity) { Bucket() }
            bucket.intervals += 1
            rows.forEach { exposure ->
                when (exposure) {
                    is UsageBehavior -> bucket.totals += exposure.comparable
                    is WorkoutBehavior -> bucket.minutes += exposure.minutes
                    is ReportedBehavior -> Unit
                }
            }
        }
    }
    return buckets.map { (identity, bucket) ->
        PredecessorExposureCount(
            identity = identity,
            intervalCount = bucket.intervals,
            comparableTotals = bucket.totals,
            storedWorkoutMinutes = bucket.minutes,
        )
    }.sortedWith(compareBy({ it.identity.kind.ordinal }, { it.identity.key }, { it.identity.intent ?: "" }))
}

private fun axisCounts(
    intervals: List<IntervalBehaviorEvidence>,
    statesById: Map<String, StateOccurrenceNode>,
): List<SuccessorAxisCount> {
    val axes = intervals.map { interval ->
        statesById[interval.endStateKey.raw]?.let { stateView(it.state) }?.axes
    }
    return CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        val column = axes.map { row -> row?.single { it.axisId == axisId } }
        SuccessorAxisCount(
            axisId = axisId,
            recordedCount = column.count { it?.status == FocalAxisStatus.RECORDED },
            missingCount = column.count { it == null || it.status == FocalAxisStatus.MISSING },
            conflictingCount = column.count { it?.status == FocalAxisStatus.CONFLICTING },
            recordedZeroCount = column.count { it?.status == FocalAxisStatus.RECORDED && it.raw == 0 },
        )
    }
}

private fun stateView(state: StateObservationV2): SuccessorStateView {
    val emotion = state.emotion.takeIf { it.isNotEmpty() }
    return SuccessorStateView(
        stateKey = StateOccurrenceKey(state.checkInId),
        recordedAt = state.recordedAt,
        axes = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            val reading = state.core.inOrder().first { it.first == axisId }.second
            val status = when {
                axisId in state.conflictingAxisIds -> FocalAxisStatus.CONFLICTING
                reading == null -> FocalAxisStatus.MISSING
                else -> FocalAxisStatus.RECORDED
            }
            FocalAxisCoverage(
                axisId = axisId,
                status = status,
                raw = if (status == FocalAxisStatus.RECORDED) reading?.raw else null,
            )
        },
        emotionPresent = emotion != null,
        emotion = emotion,
    )
}

private fun TransitionOccurrenceNode.toCandidate() = SuccessorCandidate(
    transitionKey = TransitionOccurrenceKey(id),
    endStateId = endStateId,
    startAt = interval.windowStartAt,
    endAt = interval.windowEndAt,
)

private fun stop(
    reason: SuccessorStopReason,
    depth: Int,
    stoppingTransitionKey: TransitionOccurrenceKey? = null,
    unresolvedSuccessorId: String? = null,
    candidates: List<SuccessorCandidate> = emptyList(),
    eligibility: CausalEligibility? = null,
) = SuccessorStop(
    reason = reason,
    depth = depth,
    stoppingTransitionKey = stoppingTransitionKey,
    unresolvedSuccessorId = unresolvedSuccessorId,
    candidates = candidates.sortedBy { it.transitionKey.raw },
    eligibility = eligibility,
)

fun successorCompactLabel(neighborhood: RetrospectiveSuccessorNeighborhood): String {
    val immediate = neighborhood.hops.first()
    val ending = immediate.successor?.axes?.joinToString(" · ") { axis ->
        val raw = axis.raw?.toString() ?: "none"
        "${axis.axisId} ${axis.status.name} $raw"
    } ?: "ending state not adopted"
    val history = when (neighborhood.stop.reason) {
        SuccessorStopReason.END_OF_RECORDED_HISTORY ->
            "Nothing recorded later is the end of recorded history, not reviewed absence."
        SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR ->
            "No graph-linked successor. Later sampled records exist. ${neighborhood.stop.reason.name}"
        SuccessorStopReason.DISCONNECTED_AT_BOUNDARY ->
            "No graph-linked successor. Every check-in at the earliest later time is a wake or day start. " +
                neighborhood.stop.reason.name
        SuccessorStopReason.BOUNDARY_REACHED ->
            "Graph-linked wake or day start stops the ordered path. The next waking segment is not entered."
        else -> "stop ${neighborhood.stop.reason.name}"
    }
    val windows = neighborhood.windows.joinToString("\n") { window ->
        val choices = if (window.choicesAreObservedZero) {
            "choices 0 observed zero"
        } else if (window.rightCensored && window.choices.intervals.isEmpty()) {
            "choices 0 censored"
        } else {
            "choices ${window.choices.intervals.size}"
        }
        "${window.scale.name} coverage ${window.coverage} · right censored ${window.rightCensored} · " +
            "$choices · outcomes ${window.outcomes.intervals.size}"
    }
    return "Contains recorded future behavior and outcomes. Retrospective only; excluded from forecast inputs.\n" +
        "ordered depth ${neighborhood.hops.size} · $history\n" +
        "immediate outgoing ${categoryPhrase(immediate.transition)} · exposures ${immediate.transition.exposures.size}\n" +
        "ending axes $ending\n" +
        windows
}

fun successorTechnicalLabel(neighborhood: RetrospectiveSuccessorNeighborhood): String {
    val hops = neighborhood.hops.joinToString("\n") { hop ->
        val source = hop.source?.let { "${it.stateKey.raw} at ${it.recordedAt}" } ?: "none"
        val successor = hop.successor?.let { view ->
            val axes = view.axes.joinToString(",") { axis ->
                "${axis.axisId} ${axis.status.name} ${axis.raw ?: "none"}"
            }
            val emotion = if (view.emotionPresent) view.emotion else "absent"
            "${view.stateKey.raw} at ${view.recordedAt} · $axes · emotion $emotion"
        } ?: "not adopted"
        val quantities = hop.transition.exposures.filterIsInstance<UsageBehavior>().joinToString(",") { usage ->
            usage.amounts.joinToString(",") { amount ->
                "${usage.identity.key} ${amount.status.name} ${amount.numericAmount ?: "none"} ${amount.unit ?: "no-unit"}"
            }
        }.ifEmpty { "none" }
        val gaps = hop.transition.eligibility.reasons.joinToString(",") { it.name }.ifEmpty { "none" }
        "depth ${hop.depth} · transition ${hop.transition.key.raw} · source $source · successor $successor · " +
            "${hop.transition.startAt} to ${hop.transition.endAt} · duration ${hop.transition.durationMillis} · " +
            "basis ${hop.transition.startBasis} · completed ${hop.transition.completed} · gaps $gaps · " +
            categoryPhrase(hop.transition) + " · quantities $quantities"
    }
    val stop = neighborhood.stop
    val candidates = if (stop.candidates.isEmpty()) {
        "none"
    } else {
        "occurrence-identity order " + stop.candidates.joinToString("; ") { candidate ->
            "${candidate.transitionKey.raw} ends ${candidate.endStateId} ${candidate.startAt} to ${candidate.endAt}"
        }
    }
    val windows = neighborhood.windows.joinToString("\n") { window ->
        fun keys(population: SuccessorPopulation) =
            population.intervals.joinToString(",") { it.key.raw }.ifEmpty { "none" }
        "${window.scale.name} anchor ${window.anchorAt} · requested end ${window.requestedEndAt} · " +
            "cutoff ${window.observableCutoffAt ?: "none"} · effective end ${window.effectiveEndAt} · " +
            "requested ${window.requestedDurationMillis} · effective ${window.effectiveDurationMillis} · " +
            "coverage ${window.coverage} · right censored ${window.rightCensored} · " +
            "observed zero choices ${window.choicesAreObservedZero} · " +
            "choice times ${window.choices.distinctTimestampCount} · outcome times ${window.outcomes.distinctTimestampCount} · " +
            "choices ${keys(window.choices)} · outcomes ${keys(window.outcomes)}"
    }
    val focal = neighborhood.focal?.let { "${it.stateKey.raw} at ${it.recordedAt}" } ?: "absent"
    return RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_ASSUMPTIONS + "\n" +
        "cutoff ${neighborhood.observableCutoffAt ?: "none"} · $RETROSPECTIVE_SUCCESSOR_CUTOFF_RULE\n" +
        "focal $focal · anchor ${neighborhood.anchorAt} · focal state ${neighborhood.anchorIsFocalState} · " +
        "unresolved source ${neighborhood.unresolvedSourceId ?: "none"}\n" +
        hops + "\n" +
        "stop ${stop.reason.name} at depth ${stop.depth} · transition ${stop.stoppingTransitionKey?.raw ?: "none"} · " +
        "unresolved successor ${stop.unresolvedSuccessorId ?: "none"} · candidates $candidates\n" +
        "Equal timestamps stay in occurrence-identity order, not a temporal order.\n" +
        windows
}

fun successorMissingLabel(): String = "This transition has no retrospective successor neighborhood."

private fun categoryPhrase(transition: IntervalBehaviorEvidence): String {
    return "activity ${transition.activityReview.name} · workout ${transition.workoutReview.name} · " +
        "diet ${transition.dietReview.name} · usage ${transition.usageReview.name}"
}
