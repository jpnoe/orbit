package com.orbit.wellness.domain.checkin

import java.time.ZoneId
import kotlin.math.max

/**
 * Retrospective spans around one stored check-in.
 *
 * `retrospective-multiscale-bidirectional-evidence-v1` keeps every requested
 * span. It does not pick a scale, score coherence, or feed a forecast.
 * Past evidence is the predecessor path. Future evidence is the successor
 * path. A wake or day-start remains a boundary marker and starts another
 * waking segment. An ambiguous fork is not assigned to one of its branches.
 */
const val RETROSPECTIVE_MULTISCALE_BIDIRECTIONAL_EVIDENCE_PROTOCOL =
    "retrospective-multiscale-bidirectional-evidence-v1"

const val RETROSPECTIVE_MULTISCALE_BIDIRECTIONAL_EVIDENCE_ASSUMPTIONS =
    "Retrospective event-time reconstruction of predecessor and successor evidence around one check-in. " +
        "Strict as-known availability is false. This contains recorded future behavior and outcomes. " +
        "It is not a forecast input, does not update a model, and is not for recommendation. " +
        "Past hops and past stops are the causal-predecessor-neighborhood-v1 path of the segment root. " +
        "A unique future path is the retrospective-successor-neighborhood-v2 neighborhood of that one outgoing edge. " +
        "Several outgoing edges stay candidates and are not assigned to a branch. " +
        "A wake or day-start stops the ordered path and is retained as a boundary marker. " +
        "The next waking segment is separate. " +
        "Left coverage is truncated at the earliest check-in at or before the anchor. " +
        "Right coverage is censored at the latest stored check-in. " +
        "The two sides use their own radii. Every requested span stays visible. " +
        "No span is preferred. Coherence, stability, penalties, clusters, activity effects, and recommendations are not computed."

private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR

val DEFAULT_BIDIRECTIONAL_AUDIT_SPANS: List<BidirectionalSpan> = listOf(
    BidirectionalSpan(6L * MILLIS_PER_HOUR, 6L * MILLIS_PER_HOUR),
    BidirectionalSpan(24L * MILLIS_PER_HOUR, 6L * MILLIS_PER_HOUR),
    BidirectionalSpan(24L * MILLIS_PER_HOUR, 24L * MILLIS_PER_HOUR),
    BidirectionalSpan(72L * MILLIS_PER_HOUR, 24L * MILLIS_PER_HOUR),
    BidirectionalSpan(96L * MILLIS_PER_HOUR, 48L * MILLIS_PER_HOUR),
    BidirectionalSpan(7L * MILLIS_PER_DAY, 3L * MILLIS_PER_DAY),
)

data class BidirectionalSpan(
    val backwardMillis: Long,
    val forwardMillis: Long,
) {
    init {
        require(backwardMillis >= 0L)
        require(forwardMillis >= 0L)
    }
}

fun projectRetrospectiveMultiscaleBidirectionalEvidence(
    dataset: CheckInAnalysisDatasetV2,
    zone: ZoneId,
    spans: List<BidirectionalSpan> = DEFAULT_BIDIRECTIONAL_AUDIT_SPANS,
): BidirectionalEvidenceDataset {
    val prepared = prepare(dataset, zone)
    val views = prepared.graph.states.map { state ->
        viewFor(state.id, prepared, spans)
    }
    return BidirectionalEvidenceDataset(views = views)
}

fun projectRetrospectiveMultiscaleBidirectionalEvidence(
    dataset: CheckInAnalysisDatasetV2,
    focalStateId: String,
    zone: ZoneId,
    spans: List<BidirectionalSpan> = DEFAULT_BIDIRECTIONAL_AUDIT_SPANS,
): BidirectionalEvidenceView {
    return viewFor(focalStateId, prepare(dataset, zone), spans)
}

data class BidirectionalEvidenceDataset(
    val protocol: String = RETROSPECTIVE_MULTISCALE_BIDIRECTIONAL_EVIDENCE_PROTOCOL,
    val views: List<BidirectionalEvidenceView>,
) {
    init {
        require(protocol == RETROSPECTIVE_MULTISCALE_BIDIRECTIONAL_EVIDENCE_PROTOCOL)
        require(
            views == views.sortedWith(compareBy({ it.anchorAt ?: Long.MAX_VALUE }, { it.focalStateId })),
        )
    }

    fun forFocal(focalStateId: String): BidirectionalEvidenceView {
        return views.singleOrNull { it.focalStateId == focalStateId } ?: absentView(focalStateId)
    }
}

data class BidirectionalEvidenceView(
    val protocol: String = RETROSPECTIVE_MULTISCALE_BIDIRECTIONAL_EVIDENCE_PROTOCOL,
    val retrospective: Boolean = true,
    val containsFuture: Boolean = true,
    val containsOutcome: Boolean = true,
    val inputPastOnly: Boolean = false,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val availabilityMode: ObservationAvailabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
    val strictAsKnownAvailability: Boolean = false,
    val predecessorProtocol: String = CAUSAL_PREDECESSOR_NEIGHBORHOOD_PROTOCOL,
    val successorProtocol: String = RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_PROTOCOL,
    val focalStateId: String,
    val focal: PredecessorStateView?,
    val anchorAt: Long?,
    val candidates: List<BidirectionalCandidate>,
) {
    init {
        require(protocol == RETROSPECTIVE_MULTISCALE_BIDIRECTIONAL_EVIDENCE_PROTOCOL)
        require(retrospective && containsFuture && containsOutcome)
        require(!inputPastOnly && !predictive && !updatesModel && !suitableForRecommendation)
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION)
        require(!strictAsKnownAvailability)
        require(predecessorProtocol == CAUSAL_PREDECESSOR_NEIGHBORHOOD_PROTOCOL)
        require(successorProtocol == RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_PROTOCOL)
        require(focal == null || anchorAt == focal.recordedAt)
        require(candidates.all { it.anchorAt == anchorAt && it.focal == focal })
    }
}

data class BidirectionalCandidate(
    val focal: PredecessorStateView,
    val anchorAt: Long,
    val span: BidirectionalSpan,
    val predecessorProtocol: String = CAUSAL_PREDECESSOR_NEIGHBORHOOD_PROTOCOL,
    val successorProtocol: String = RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_PROTOCOL,
    val requestedStartAt: Long,
    val requestedEndAt: Long,
    val effectiveStartAt: Long,
    val effectiveEndAt: Long,
    val earliestRecordedAt: Long,
    val latestRecordedAt: Long,
    val leftCoverage: Double,
    val rightCoverage: Double,
    val leftTruncated: Boolean,
    val rightCensored: Boolean,
    val boundaryCount: Int,
    val segments: List<BidirectionalSegment>,
) {
    init {
        require(predecessorProtocol == CAUSAL_PREDECESSOR_NEIGHBORHOOD_PROTOCOL)
        require(successorProtocol == RETROSPECTIVE_SUCCESSOR_NEIGHBORHOOD_PROTOCOL)
        require(requestedStartAt == subtractRadius(anchorAt, span.backwardMillis))
        require(requestedEndAt == addRadius(anchorAt, span.forwardMillis))
        require(effectiveStartAt == max(requestedStartAt, earliestRecordedAt))
        require(leftCoverage.isFinite() && leftCoverage in 0.0..1.0)
        require(rightCoverage.isFinite() && rightCoverage in 0.0..1.0)
        require(leftTruncated == (requestedStartAt < earliestRecordedAt))
        require(rightCensored == (latestRecordedAt < requestedEndAt))
        val leftSpan = (anchorAt - effectiveStartAt).coerceAtLeast(0L)
        val rightSpan = (effectiveEndAt - anchorAt).coerceAtLeast(0L)
        val expectedLeft = if (span.backwardMillis == 0L) 0.0 else leftSpan.toDouble() / span.backwardMillis.toDouble()
        val expectedRight = if (span.forwardMillis == 0L) 0.0 else rightSpan.toDouble() / span.forwardMillis.toDouble()
        require(leftCoverage == expectedLeft)
        require(rightCoverage == expectedRight)
        require(
            segments == segments.sortedWith(compareBy({ it.rootRecordedAt }, { it.rootStateId })),
        )
    }
}

data class BidirectionalSegment(
    val rootStateId: String,
    val rootRecordedAt: Long,
    val statesInSpan: List<String>,
    val pastHops: List<PredecessorHop>,
    val pastStop: PredecessorStop,
    val futureHops: List<SuccessorHop>,
    val futureReason: SuccessorStopReason,
    val futureCandidates: List<SuccessorCandidate>,
) {
    init {
        require(futureCandidates == futureCandidates.sortedBy { it.transitionKey.raw })
        require(futureReason != SuccessorStopReason.AMBIGUOUS_SUCCESSOR || futureCandidates.size > 1)
    }
}

private data class PreparedEvidence(
    val graph: AlternatingTemporalGraph,
    val evidence: List<IntervalBehaviorEvidence>,
    val predecessors: CausalPredecessorNeighborhoodDataset,
    val successors: RetrospectiveSuccessorNeighborhoodDataset,
    val segments: List<BuiltSegment>,
)

private data class BuiltSegment(
    val root: StateOccurrenceNode,
    val past: CausalPredecessorNeighborhood,
    val futureHops: List<SuccessorHop>,
    val futureReason: SuccessorStopReason,
    val futureCandidates: List<SuccessorCandidate>,
)

private fun prepare(dataset: CheckInAnalysisDatasetV2, zone: ZoneId): PreparedEvidence {
    val graph = projectAlternatingTemporalGraph(dataset)
    val evidence = projectIntervalBehaviorEvidence(graph, dataset)
    val predecessors = projectCausalPredecessorNeighborhood(dataset, zone)
    val successors = projectRetrospectiveSuccessorNeighborhood(dataset)
    return PreparedEvidence(
        graph = graph,
        evidence = evidence,
        predecessors = predecessors,
        successors = successors,
        segments = wakingSegments(graph, evidence, predecessors, successors),
    )
}

private fun viewFor(
    focalStateId: String,
    prepared: PreparedEvidence,
    spans: List<BidirectionalSpan>,
): BidirectionalEvidenceView {
    val focalNode = prepared.graph.state(focalStateId) ?: return absentView(focalStateId)
    val focal = prepared.predecessors.neighborhoods
        .single { it.focal.stateKey.raw == focalStateId }
        .focal
    val anchor = focal.recordedAt
    val earliest = prepared.graph.states
        .filter { it.state.recordedAt <= anchor }
        .minOf { it.state.recordedAt }
    val latest = prepared.graph.states.maxOf { it.state.recordedAt }
    val candidates = spans.map { span ->
        candidate(focal, anchor, span, earliest, latest, prepared.segments)
    }
    return BidirectionalEvidenceView(
        focalStateId = focalStateId,
        focal = focal,
        anchorAt = anchor,
        candidates = candidates,
    )
}

private fun absentView(focalStateId: String) = BidirectionalEvidenceView(
    focalStateId = focalStateId,
    focal = null,
    anchorAt = null,
    candidates = emptyList(),
)

private fun candidate(
    focal: PredecessorStateView,
    anchor: Long,
    span: BidirectionalSpan,
    earliest: Long,
    latest: Long,
    segments: List<BuiltSegment>,
): BidirectionalCandidate {
    val requestedStart = subtractRadius(anchor, span.backwardMillis)
    val requestedEnd = addRadius(anchor, span.forwardMillis)
    val effectiveStart = max(requestedStart, earliest)
    val effectiveEnd = when {
        latest <= anchor -> anchor
        latest >= requestedEnd -> requestedEnd
        else -> latest
    }
    val visible = segments.mapNotNull { segment ->
        windowed(segment, anchor, effectiveStart, effectiveEnd)
    }.sortedWith(compareBy({ it.rootRecordedAt }, { it.rootStateId }))
    val boundaryIds = visible.flatMap { segment ->
        val transitions = segment.pastHops.map { it.transition } + segment.futureHops.map { it.transition }
        transitions
            .filter { CausalIneligibilityReason.BOUNDARY_INTERVAL in it.eligibility.reasons }
            .map { it.key.raw }
    }.toSet()
    val leftSpan = (anchor - effectiveStart).coerceAtLeast(0L)
    val rightSpan = (effectiveEnd - anchor).coerceAtLeast(0L)
    return BidirectionalCandidate(
        focal = focal,
        anchorAt = anchor,
        span = span,
        requestedStartAt = requestedStart,
        requestedEndAt = requestedEnd,
        effectiveStartAt = effectiveStart,
        effectiveEndAt = effectiveEnd,
        earliestRecordedAt = earliest,
        latestRecordedAt = latest,
        leftCoverage = coverage(leftSpan, span.backwardMillis),
        rightCoverage = coverage(rightSpan, span.forwardMillis),
        leftTruncated = requestedStart < earliest,
        rightCensored = latest < requestedEnd,
        boundaryCount = boundaryIds.size,
        segments = visible,
    )
}

private fun windowed(
    segment: BuiltSegment,
    anchor: Long,
    effectiveStart: Long,
    effectiveEnd: Long,
): BidirectionalSegment? {
    val past = segment.past.hops.filter { overlaps(it.transition, effectiveStart, effectiveEnd) }
    val future = segment.futureHops.filter { overlaps(it.transition, effectiveStart, effectiveEnd) }
    val states = segmentStates(segment)
        .filter { (_, at) -> at in effectiveStart..effectiveEnd }
        .sortedWith(compareBy({ it.second }, { it.first }))
        .map { it.first }
    if (past.isEmpty() && future.isEmpty() && states.isEmpty()) return null
    return BidirectionalSegment(
        rootStateId = segment.root.id,
        rootRecordedAt = segment.root.state.recordedAt,
        statesInSpan = states,
        pastHops = past,
        pastStop = segment.past.stop,
        futureHops = future,
        futureReason = segment.futureReason,
        futureCandidates = segment.futureCandidates,
    )
}

private fun overlaps(transition: IntervalBehaviorEvidence, start: Long, end: Long): Boolean {
    return transition.startAt < end && transition.endAt > start
}

private fun segmentStates(segment: BuiltSegment): List<Pair<String, Long>> {
    val past = segment.past.hops.mapNotNull { hop ->
        hop.predecessor?.let { it.stateKey.raw to it.recordedAt }
    }
    val future = segment.futureHops.mapNotNull { hop ->
        hop.successor?.let { it.stateKey.raw to it.recordedAt }
    }
    return past + (segment.root.id to segment.root.state.recordedAt) + future
}

private fun wakingSegments(
    graph: AlternatingTemporalGraph,
    evidence: List<IntervalBehaviorEvidence>,
    predecessors: CausalPredecessorNeighborhoodDataset,
    successors: RetrospectiveSuccessorNeighborhoodDataset,
): List<BuiltSegment> {
    val adopted = adoptedByUniqueSuccessorWalk(graph, successors)
    return graph.states
        .filter { it.id !in adopted }
        .map { root ->
            val past = predecessors.neighborhoods.single { it.focal.stateKey.raw == root.id }
            val future = futureOf(root, graph, evidence, successors)
            BuiltSegment(
                root = root,
                past = past,
                futureHops = future.hops,
                futureReason = future.reason,
                futureCandidates = future.candidates,
            )
        }
}

private fun adoptedByUniqueSuccessorWalk(
    graph: AlternatingTemporalGraph,
    successors: RetrospectiveSuccessorNeighborhoodDataset,
): Set<String> {
    val adopted = mutableSetOf<String>()
    graph.states.forEach { state ->
        val outgoing = outgoingFrom(graph, state.id)
        if (outgoing.size != 1) return@forEach
        val neighborhood = successors.neighborhoods.single { it.transitionId == outgoing.single().id }
        neighborhood.hops.forEach { hop ->
            hop.successor?.stateKey?.raw?.let { adopted += it }
        }
    }
    return adopted
}

private data class FuturePath(
    val hops: List<SuccessorHop>,
    val reason: SuccessorStopReason,
    val candidates: List<SuccessorCandidate>,
)

private fun futureOf(
    root: StateOccurrenceNode,
    graph: AlternatingTemporalGraph,
    evidence: List<IntervalBehaviorEvidence>,
    successors: RetrospectiveSuccessorNeighborhoodDataset,
): FuturePath {
    val outgoing = outgoingFrom(graph, root.id)
    if (outgoing.size > 1) {
        return FuturePath(
            hops = emptyList(),
            reason = SuccessorStopReason.AMBIGUOUS_SUCCESSOR,
            candidates = outgoing.map { node ->
                SuccessorCandidate(
                    transitionKey = TransitionOccurrenceKey(node.id),
                    endStateId = node.endStateId,
                    startAt = node.interval.windowStartAt,
                    endAt = node.interval.windowEndAt,
                )
            }.sortedBy { it.transitionKey.raw },
        )
    }
    if (outgoing.size == 1) {
        val neighborhood = successors.neighborhoods.single { it.transitionId == outgoing.single().id }
        return FuturePath(
            hops = neighborhood.hops,
            reason = neighborhood.stop.reason,
            candidates = neighborhood.stop.candidates,
        )
    }
    val endedHere = successors.neighborhoods.filter { view ->
        view.hops.lastOrNull()?.successor?.stateKey?.raw == root.id &&
            view.stop.reason in NO_OUTGOING_REASONS
    }
    val reused = endedHere.minByOrNull { it.transitionId }
    if (reused != null) {
        return FuturePath(
            hops = emptyList(),
            reason = reused.stop.reason,
            candidates = reused.stop.candidates,
        )
    }
    return FuturePath(
        hops = emptyList(),
        reason = disconnectedReason(root, graph, evidence),
        candidates = emptyList(),
    )
}

private val NO_OUTGOING_REASONS = setOf(
    SuccessorStopReason.END_OF_RECORDED_HISTORY,
    SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR,
    SuccessorStopReason.DISCONNECTED_AT_BOUNDARY,
)

private fun disconnectedReason(
    terminal: StateOccurrenceNode,
    graph: AlternatingTemporalGraph,
    evidence: List<IntervalBehaviorEvidence>,
): SuccessorStopReason {
    val cutoff = graph.states.maxOfOrNull { it.state.recordedAt }
    val terminalAt = terminal.state.recordedAt
    if (cutoff == null || terminalAt >= cutoff) return SuccessorStopReason.END_OF_RECORDED_HISTORY
    val later = graph.states.filter { it.state.recordedAt > terminalAt }
    if (later.isEmpty()) return SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR
    val earliestAt = later.minOf { it.state.recordedAt }
    val cohort = later.filter { it.state.recordedAt == earliestAt }
    val boundaryCohort = cohort.all { state ->
        val rows = evidence.filter { it.endStateKey.raw == state.id }
        rows.isNotEmpty() && rows.all { CausalIneligibilityReason.BOUNDARY_INTERVAL in it.eligibility.reasons }
    }
    return if (boundaryCohort) {
        SuccessorStopReason.DISCONNECTED_AT_BOUNDARY
    } else {
        SuccessorStopReason.NO_GRAPH_LINKED_SUCCESSOR
    }
}

private fun outgoingFrom(
    graph: AlternatingTemporalGraph,
    stateId: String,
): List<TransitionOccurrenceNode> {
    return graph.transitions
        .filter { it.startStateId == stateId }
        .sortedBy { it.id }
}

private fun coverage(effective: Long, requested: Long): Double {
    if (requested == 0L) return 0.0
    return effective.toDouble() / requested.toDouble()
}

private fun subtractRadius(anchor: Long, radius: Long): Long {
    if (anchor < radius) return Long.MIN_VALUE
    return anchor - radius
}

private fun addRadius(anchor: Long, radius: Long): Long {
    if (radius > Long.MAX_VALUE - anchor) return Long.MAX_VALUE
    return anchor + radius
}

fun bidirectionalAuditLabel(view: BidirectionalEvidenceView): String {
    val focal = view.focal
    val anchor = view.anchorAt
    if (focal == null || anchor == null) {
        return "This check-in has no retrospective multiscale bidirectional evidence."
    }
    val header = RETROSPECTIVE_MULTISCALE_BIDIRECTIONAL_EVIDENCE_PROTOCOL + "\n" +
        "focal ${focal.stateKey.raw} at $anchor\n" +
        "derived from ${view.predecessorProtocol} and ${view.successorProtocol}\n" +
        "Retrospective only. Every span stays visible. No preferred scale.\n"
    return header + view.candidates.joinToString("\n") { candidate -> auditCandidate(candidate) }
}

private fun auditCandidate(candidate: BidirectionalCandidate): String {
    val span = candidate.span
    val segments = candidate.segments.joinToString("\n") { segment ->
        val past = segment.pastHops.joinToString("; ") { hopPhrase(it.transition) }.ifEmpty { "none" }
        val future = segment.futureHops.joinToString("; ") { hopPhrase(it.transition) }.ifEmpty { "none" }
        val candidates = segment.futureCandidates.joinToString(",") { it.transitionKey.raw }.ifEmpty { "none" }
        val states = segment.statesInSpan.joinToString(",").ifEmpty { "none" }
        "segment ${segment.rootStateId} at ${segment.rootRecordedAt}\n" +
            "states $states\n" +
            "past $past stop ${segment.pastStop.reason.name}\n" +
            "future $future stop ${segment.futureReason.name} candidates $candidates"
    }.ifEmpty { "no segments" }
    return "span back ${span.backwardMillis} forward ${span.forwardMillis}\n" +
        "requested ${candidate.requestedStartAt} to ${candidate.requestedEndAt}\n" +
        "effective ${candidate.effectiveStartAt} to ${candidate.effectiveEndAt}\n" +
        "left coverage ${candidate.leftCoverage} truncated ${candidate.leftTruncated}\n" +
        "right coverage ${candidate.rightCoverage} censored ${candidate.rightCensored}\n" +
        "segments ${candidate.segments.size} boundaries ${candidate.boundaryCount}\n" +
        segments
}

private fun hopPhrase(transition: IntervalBehaviorEvidence): String {
    val gaps = transition.eligibility.reasons.joinToString(",") { it.name }.ifEmpty { "none" }
    val quantities = transition.exposures.joinToString(",") { exposure ->
        "${exposure.identity.kind.name}:${exposure.identity.key}"
    }.ifEmpty { "none" }
    return "${transition.key.raw} ${transition.startAt} to ${transition.endAt} " +
        "basis ${transition.startBasis} gaps $gaps quantities $quantities"
}

fun bidirectionalMissingLabel(): String = "This check-in has no retrospective multiscale bidirectional evidence."
