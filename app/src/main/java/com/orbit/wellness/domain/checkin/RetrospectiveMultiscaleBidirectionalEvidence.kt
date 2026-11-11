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
 * The published span keeps interval identity and in-span endpoints. It does
 * not retain the predecessor or successor hop, so a crossing interval cannot
 * carry a state value from outside the requested radius.
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
        "An interval that crosses a span bound stays identified, and the state beyond that bound is not carried on the hop. " +
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
            segments == segments.sortedWith(compareBy({ it.rangeStart }, { it.rootStateId })),
        )
    }
}

enum class SegmentKind {
    FOCAL_ORDERED,
    ORDERED_CONTINUATION,
    SAMPLED_OR_DISCONNECTED,
    BRANCH_LOCAL_PREDECESSOR,
}

/**
 * One interval as seen inside a requested span.
 *
 * [transitionKey] names the interval. When [endOutsideSpan] is set, that key
 * is the check-in beyond the right bound and is not an in-span state. This
 * type has no axis values, emotion, or predecessor or successor state view.
 */
data class SpanInterval(
    val transitionKey: TransitionOccurrenceKey,
    val startAt: Long,
    val endAt: Long,
    val startBasis: String,
    val boundary: Boolean,
    val startOutsideSpan: Boolean,
    val endOutsideSpan: Boolean,
    val outcomeInSpan: Boolean,
    val inSpanStateIds: List<String>,
) {
    init {
        require(inSpanStateIds == inSpanStateIds.distinct())
        require(!endOutsideSpan || !outcomeInSpan)
        require(!endOutsideSpan || transitionKey.raw !in inSpanStateIds)
    }
}

data class BidirectionalSegment(
    val rootStateId: String,
    val rangeStart: Long,
    val rangeEnd: Long,
    val kind: SegmentKind,
    val statesInSpan: List<String>,
    val pastEvidence: List<SpanInterval>,
    val pastReason: PredecessorStopReason,
    val futureEvidence: List<SpanInterval>,
    val futureReason: SuccessorStopReason,
    val futureCandidateKeys: List<String>,
) {
    init {
        require(rangeStart <= rangeEnd)
        require(statesInSpan.isNotEmpty())
        require(futureCandidateKeys == futureCandidateKeys.sorted())
        require(futureReason != SuccessorStopReason.AMBIGUOUS_SUCCESSOR || futureCandidateKeys.size > 1)
        val evidence = pastEvidence + futureEvidence
        require(evidence.flatMap { it.inSpanStateIds }.all { it in statesInSpan })
        require(evidence.filter { it.endOutsideSpan }.none { it.transitionKey.raw in statesInSpan })
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
    val windowed = segments.mapNotNull { segment ->
        windowed(segment, anchor, effectiveStart, effectiveEnd)
    }
    val visible = classify(focal.stateKey.raw, windowed)
        .sortedWith(compareBy({ it.rangeStart }, { it.rootStateId }))
    val boundaryIds = visible.flatMap { segment ->
        (segment.pastEvidence + segment.futureEvidence)
            .filter { it.boundary }
            .map { it.transitionKey.raw }
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

private data class Windowed(
    val built: BuiltSegment,
    val rootStateId: String,
    val rangeStart: Long,
    val rangeEnd: Long,
    val statesInSpan: List<String>,
    val pastEvidence: List<SpanInterval>,
    val futureEvidence: List<SpanInterval>,
    val futureCandidateKeys: List<String>,
)

private fun windowed(
    segment: BuiltSegment,
    anchor: Long,
    effectiveStart: Long,
    effectiveEnd: Long,
): Windowed? {
    val timed = segmentStates(segment)
        .filter { (_, at) -> at in effectiveStart..effectiveEnd }
        .sortedWith(compareBy({ it.second }, { it.first }))
    if (timed.isEmpty()) return null
    val states = timed.map { it.first }.distinct()
    val rootInSpan = segment.root.state.recordedAt in effectiveStart..effectiveEnd
    val keys = segment.futureCandidates
        .filter { it.startAt < effectiveEnd && it.endAt > effectiveStart }
        .map { it.transitionKey.raw }
        .distinct()
        .sorted()
    return Windowed(
        built = segment,
        rootStateId = if (rootInSpan) segment.root.id else states.first(),
        rangeStart = timed.minOf { it.second },
        rangeEnd = timed.maxOf { it.second },
        statesInSpan = states,
        pastEvidence = segment.past.hops.mapNotNull { hop ->
            projectPredecessor(hop, anchor, effectiveStart, effectiveEnd)
        },
        futureEvidence = segment.futureHops.mapNotNull { hop ->
            projectSuccessor(hop, anchor, effectiveStart, effectiveEnd)
        },
        futureCandidateKeys = keys,
    )
}

private fun classify(focalId: String, windowed: List<Windowed>): List<BidirectionalSegment> {
    val focal = windowed.singleOrNull { focalId in forwardStateIds(it.built) }
    val continuation = if (focal == null) emptySet() else continuations(focal, windowed)
    val focalPath = focal?.let { pathStateIds(it.built) }.orEmpty()
    val adoptedFuture = focal?.built?.futureHops
        ?.mapNotNull { it.successor?.stateKey?.raw }
        ?.toSet()
        .orEmpty()
    val branchRoots = focal?.built?.futureCandidates
        ?.map { it.endStateId }
        ?.toSet()
        .orEmpty() - adoptedFuture
    return windowed.map { item ->
        val kind = when {
            item === focal -> SegmentKind.FOCAL_ORDERED
            continuation.any { it === item } -> SegmentKind.ORDERED_CONTINUATION
            isBranchLocal(item, focalPath, branchRoots) -> SegmentKind.BRANCH_LOCAL_PREDECESSOR
            else -> SegmentKind.SAMPLED_OR_DISCONNECTED
        }
        BidirectionalSegment(
            rootStateId = item.rootStateId,
            rangeStart = item.rangeStart,
            rangeEnd = item.rangeEnd,
            kind = kind,
            statesInSpan = item.statesInSpan,
            pastEvidence = item.pastEvidence,
            pastReason = item.built.past.stop.reason,
            futureEvidence = item.futureEvidence,
            futureReason = item.built.futureReason,
            futureCandidateKeys = item.futureCandidateKeys,
        )
    }
}

private fun continuations(focal: Windowed, windowed: List<Windowed>): Set<Windowed> {
    val linked = mutableSetOf<Windowed>()
    linked += focal
    val keys = boundaryKeys(focal.built).toMutableSet()
    var grew = true
    while (grew) {
        grew = false
        windowed.forEach { item ->
            if (linked.any { it === item }) return@forEach
            val itemKeys = boundaryKeys(item.built)
            if (itemKeys.any { it in keys }) {
                linked += item
                keys += itemKeys
                grew = true
            }
        }
    }
    linked.removeAll { it === focal }
    return linked
}

private fun isBranchLocal(
    item: Windowed,
    focalPath: Set<String>,
    branchRoots: Set<String>,
): Boolean {
    if (item.built.root.id in branchRoots) return true
    return item.built.past.hops.any { hop -> hop.predecessor?.stateKey?.raw in focalPath }
}

private fun boundaryKeys(segment: BuiltSegment): Set<String> {
    val past = segment.past.hops.filter { it.boundary }.map { it.transition.key.raw }
    val future = segment.futureHops.filter { isBoundary(it.transition) }.map { it.transition.key.raw }
    return (past + future).toSet()
}

private fun forwardStateIds(segment: BuiltSegment): Set<String> {
    val ids = mutableSetOf(segment.root.id)
    segment.futureHops.mapNotNullTo(ids) { it.successor?.stateKey?.raw }
    return ids
}

private fun pathStateIds(segment: BuiltSegment): Set<String> {
    val ids = forwardStateIds(segment).toMutableSet()
    segment.past.hops.mapNotNullTo(ids) { it.predecessor?.stateKey?.raw }
    return ids
}

private fun projectPredecessor(
    hop: PredecessorHop,
    anchor: Long,
    start: Long,
    end: Long,
): SpanInterval? {
    val transition = hop.transition
    if (!overlaps(transition, start, end)) return null
    val startOutside = transition.startAt < start
    val endOutside = transition.endAt > end
    val ids = mutableListOf<String>()
    val earlier = hop.predecessor
    if (!startOutside && earlier != null && earlier.recordedAt in start..end) {
        ids += earlier.stateKey.raw
    }
    if (!endOutside && transition.endAt in start..end) {
        ids += transition.key.raw
    }
    return spanInterval(transition, hop.boundary, startOutside, endOutside, anchor, end, ids)
}

private fun projectSuccessor(
    hop: SuccessorHop,
    anchor: Long,
    start: Long,
    end: Long,
): SpanInterval? {
    val transition = hop.transition
    if (!overlaps(transition, start, end)) return null
    val startOutside = transition.startAt < start
    val endOutside = transition.endAt > end
    val ids = mutableListOf<String>()
    val source = hop.source
    if (!startOutside && source != null && source.recordedAt in start..end) {
        ids += source.stateKey.raw
    }
    val successor = hop.successor
    if (!endOutside && successor != null && successor.recordedAt in start..end) {
        ids += successor.stateKey.raw
    }
    return spanInterval(transition, false, startOutside, endOutside, anchor, end, ids)
}

private fun spanInterval(
    transition: IntervalBehaviorEvidence,
    boundaryHop: Boolean,
    startOutside: Boolean,
    endOutside: Boolean,
    anchor: Long,
    effectiveEnd: Long,
    ids: List<String>,
): SpanInterval {
    val safeIds = ids.distinct().filter { id -> !endOutside || id != transition.key.raw }
    return SpanInterval(
        transitionKey = transition.key,
        startAt = transition.startAt,
        endAt = transition.endAt,
        startBasis = transition.startBasis,
        boundary = boundaryHop || isBoundary(transition),
        startOutsideSpan = startOutside,
        endOutsideSpan = endOutside,
        outcomeInSpan = !endOutside && transition.endAt > anchor && transition.endAt <= effectiveEnd,
        inSpanStateIds = safeIds,
    )
}

private fun isBoundary(transition: IntervalBehaviorEvidence): Boolean {
    return CausalIneligibilityReason.BOUNDARY_INTERVAL in transition.eligibility.reasons
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
        "Retrospective only. Every span stays visible. No preferred scale."
    return header + "\n" + view.candidates.joinToString("\n") { candidate -> auditCandidate(candidate) }
}

private fun auditCandidate(candidate: BidirectionalCandidate): String {
    val span = candidate.span
    val stateCount = candidate.segments.flatMap { it.statesInSpan }.distinct().size
    val transitionCount = candidate.segments
        .flatMap { it.pastEvidence + it.futureEvidence }
        .map { it.transitionKey.raw }
        .distinct()
        .size
    val stops = candidate.segments
        .flatMap { listOf(it.pastReason.name, it.futureReason.name) }
        .distinct()
        .ifEmpty { listOf("none") }
        .joinToString(",")
    val summary = "left ${radius(span.backwardMillis)} right ${radius(span.forwardMillis)}" +
        " · bounds ${candidate.requestedStartAt} to ${candidate.requestedEndAt}" +
        " · left coverage ${formatCoverage(candidate.leftCoverage)} ${if (candidate.leftTruncated) "truncated" else "full"}" +
        " · right coverage ${formatCoverage(candidate.rightCoverage)} ${if (candidate.rightCensored) "censored" else "open"}" +
        " · states $stateCount transitions $transitionCount segments ${candidate.segments.size} boundaries ${candidate.boundaryCount}" +
        " · stops $stops"
    val segments = candidate.segments.joinToString("\n") { segmentLine(it) }
    return if (segments.isEmpty()) summary else summary + "\n" + segments
}

private fun segmentLine(segment: BidirectionalSegment): String {
    val states = segment.statesInSpan.joinToString(",")
    val boundaries = (segment.pastEvidence + segment.futureEvidence)
        .filter { it.boundary }
        .map { it.transitionKey.raw }
        .distinct()
    val boundaryText = if (boundaries.isEmpty()) "no boundary" else "boundaries ${boundaries.joinToString(",")}"
    val marks = (segment.pastEvidence + segment.futureEvidence).mapNotNull { boundMark(it) }
    val candidates = if (segment.futureCandidateKeys.isEmpty()) {
        ""
    } else {
        " · candidates ${segment.futureCandidateKeys.joinToString(",")}"
    }
    val markText = if (marks.isEmpty()) "" else " · ${marks.joinToString("; ")}"
    return "${kindLabel(segment.kind)} · $states · ${segment.rangeStart} to ${segment.rangeEnd}" +
        " · $boundaryText · past ${segment.pastReason.name} · future ${segment.futureReason.name}" +
        candidates + markText
}

private fun boundMark(interval: SpanInterval): String? {
    val parts = mutableListOf<String>()
    if (interval.startOutsideSpan) {
        parts += "left-truncated start outside span ${interval.transitionKey.raw}"
    }
    if (interval.endOutsideSpan) {
        parts += "right-truncated end outside span ${interval.transitionKey.raw}"
    }
    return parts.joinToString("; ").ifEmpty { null }
}

private fun kindLabel(kind: SegmentKind): String = when (kind) {
    SegmentKind.FOCAL_ORDERED -> "focal ordered"
    SegmentKind.ORDERED_CONTINUATION -> "ordered continuation"
    SegmentKind.SAMPLED_OR_DISCONNECTED -> "sampled or disconnected"
    SegmentKind.BRANCH_LOCAL_PREDECESSOR ->
        "branch-local predecessor, not adopted by the focal successor path"
}

private fun radius(millis: Long): String = when {
    millis % MILLIS_PER_DAY == 0L -> "${millis / MILLIS_PER_DAY}d"
    millis % MILLIS_PER_HOUR == 0L -> "${millis / MILLIS_PER_HOUR}h"
    else -> "${millis}ms"
}

private fun formatCoverage(value: Double): String {
    val thousandths = kotlin.math.round(value * 1000.0).toLong()
    val whole = thousandths / 1000
    val fraction = (kotlin.math.abs(thousandths % 1000)).toString().padStart(3, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}

fun bidirectionalMissingLabel(): String = "This check-in has no retrospective multiscale bidirectional evidence."
