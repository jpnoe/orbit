package com.orbit.wellness.domain.checkin

import java.time.ZoneId
import kotlin.math.abs

/**
 * Raw shape measurements for each span of `retrospective-multiscale-bidirectional-evidence-v1`.
 *
 * Membership comes from [BidirectionalCandidate.comparableState]. A transition key is not
 * a lookup. Values are the recorded raw points of those admitted check-ins. Missing and
 * conflicting axes stay missing. The four core axes are reported in core order.
 *
 * A segment's own median and slope use its root and the states named by its future
 * evidence. Predecessor ids on a branch stay path context, so the fork is not copied
 * into that branch. State-weighted summaries use each admitted check-in once.
 * The leave-one-out reference drops the focal check-in. The focal segment median
 * keeps it. Slopes stay inside the focal waking segment, in raw points per hour,
 * and use the median of pairwise slopes across distinct timestamps.
 *
 * This pass does not score a shape, pick a scale, or feed a forecast.
 */
const val RETROSPECTIVE_STATE_SHAPE_DIAGNOSTICS_PROTOCOL =
    "retrospective-state-shape-diagnostics-v1"

const val RETROSPECTIVE_STATE_SHAPE_DIAGNOSTICS_ASSUMPTIONS =
    "Retrospective measurements of admitted check-ins in each bidirectional span. " +
        "Strict as-known availability is false. This contains recorded future behavior. " +
        "It is not a forecast input, does not update a model, and is not for recommendation. " +
        "Membership is comparableState. Transition keys and canonical ids are not rejoined to recover values. " +
        "Readings are raw core-axis points. Missing and conflicting axes stay missing. " +
        "The leave-one-out median and deviation exclude the focal check-in. " +
        "A segment median includes the check-ins that reside in that waking segment. " +
        "State-weighted and segment-balanced summaries are both kept. " +
        "Slopes are the median pairwise slope in raw points per hour inside the focal waking segment. " +
        "A slope is unavailable when fewer than two distinct timestamps remain. " +
        "A deviation is unavailable when fewer than two values remain. " +
        "Emotion labels are counted and are not placed on a number line. " +
        "No shape score, preferred scale, cluster, or learner update is computed."

private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR

fun projectRetrospectiveStateShapeDiagnostics(
    dataset: CheckInAnalysisDatasetV2,
    zone: ZoneId,
    spans: List<BidirectionalSpan> = DEFAULT_BIDIRECTIONAL_AUDIT_SPANS,
): StateShapeDiagnosticDataset {
    val evidence = projectRetrospectiveMultiscaleBidirectionalEvidence(dataset, zone, spans)
    return projectRetrospectiveStateShapeDiagnostics(dataset, evidence)
}

fun projectRetrospectiveStateShapeDiagnostics(
    dataset: CheckInAnalysisDatasetV2,
    focalStateId: String,
    zone: ZoneId,
    spans: List<BidirectionalSpan> = DEFAULT_BIDIRECTIONAL_AUDIT_SPANS,
): StateShapeDiagnosticView {
    val evidence = projectRetrospectiveMultiscaleBidirectionalEvidence(dataset, focalStateId, zone, spans)
    return diagnoseView(dataset, evidence)
}

fun projectRetrospectiveStateShapeDiagnostics(
    dataset: CheckInAnalysisDatasetV2,
    evidence: BidirectionalEvidenceDataset,
): StateShapeDiagnosticDataset {
    return StateShapeDiagnosticDataset(views = evidence.views.map { diagnoseView(dataset, it) })
}

data class StateShapeDiagnosticDataset(
    val protocol: String = RETROSPECTIVE_STATE_SHAPE_DIAGNOSTICS_PROTOCOL,
    val views: List<StateShapeDiagnosticView>,
) {
    init {
        require(protocol == RETROSPECTIVE_STATE_SHAPE_DIAGNOSTICS_PROTOCOL)
        require(views == views.sortedWith(compareBy({ it.anchorAt ?: Long.MAX_VALUE }, { it.focalStateId })))
    }

    fun forFocal(focalStateId: String): StateShapeDiagnosticView {
        return views.singleOrNull { it.focalStateId == focalStateId } ?: absentShapeView(focalStateId)
    }
}

data class StateShapeDiagnosticView(
    val protocol: String = RETROSPECTIVE_STATE_SHAPE_DIAGNOSTICS_PROTOCOL,
    val retrospective: Boolean = true,
    val containsFuture: Boolean = true,
    val containsOutcome: Boolean = true,
    val inputPastOnly: Boolean = false,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val focalStateId: String,
    val focalAlias: String?,
    val anchorAt: Long?,
    val candidates: List<StateShapeCandidate>,
) {
    init {
        require(protocol == RETROSPECTIVE_STATE_SHAPE_DIAGNOSTICS_PROTOCOL)
        require(retrospective && containsFuture && containsOutcome)
        require(!inputPastOnly && !predictive && !updatesModel && !suitableForRecommendation)
        require((anchorAt == null) == (focalAlias == null))
        require(focalAlias == null || focalAlias == FOCAL_STATE_ALIAS)
    }
}

data class EmotionCount(
    val emotion: String,
    val count: Int,
) {
    init {
        require(emotion.isNotEmpty())
        require(count > 0)
    }
}

data class StateShapeCandidate(
    val span: BidirectionalSpan,
    val wakingSegmentCount: Int,
    val repeatedMembershipCount: Int,
    val emotions: List<EmotionCount>,
    val emotionAbsentCount: Int,
    val axes: List<AxisShapeDiagnostic>,
) {
    init {
        require(wakingSegmentCount >= 0)
        require(repeatedMembershipCount >= 0)
        require(emotionAbsentCount >= 0)
        require(emotions == emotions.sortedBy { it.emotion })
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(axes.all { it.wakingSegmentCount == wakingSegmentCount })
    }
}

data class SegmentLevel(
    val kind: SegmentKind,
    val recordedStateCount: Int,
    val median: Double?,
) {
    init {
        require(recordedStateCount >= 0)
        require(median == null || median.isFinite())
        require(recordedStateCount > 0 || median == null)
    }
}

data class AxisShapeDiagnostic(
    val axisId: String,
    val recordedStateCount: Int,
    val wakingSegmentCount: Int,
    val focalValue: Double?,
    val stateWeightedMedian: Double?,
    val stateWeightedMad: Double?,
    val focalDistance: Double?,
    val segmentLevels: List<SegmentLevel>,
    val segmentBalancedMedian: Double?,
    val segmentMedianSpread: Double?,
    val segmentSlope: Double?,
    val segmentResidual: Double?,
    val incomingSlope: Double?,
    val outgoingSlope: Double?,
) {
    init {
        require(axisId in CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(recordedStateCount >= 0)
        require(wakingSegmentCount == segmentLevels.size)
        require(focalValue == null || focalValue.isFinite())
        require(stateWeightedMedian == null || stateWeightedMedian.isFinite())
        require(stateWeightedMad == null || stateWeightedMad >= 0.0)
        require(segmentBalancedMedian == null || segmentBalancedMedian.isFinite())
        require(segmentMedianSpread == null || segmentMedianSpread >= 0.0)
        require(segmentSlope == null || segmentSlope.isFinite())
        require(segmentResidual == null || segmentResidual.isFinite())
        require(incomingSlope == null || incomingSlope.isFinite())
        require(outgoingSlope == null || outgoingSlope.isFinite())
        require(segmentSlope != null || segmentResidual == null)
        if (focalValue != null && stateWeightedMedian != null) {
            require(focalDistance == abs(focalValue - stateWeightedMedian))
        } else {
            require(focalDistance == null)
        }
    }
}

private data class Member(
    val segmentIndex: Int,
    val id: String,
    val at: Long,
    val emotion: String,
    val raw: Map<String, Double>,
)

private data class TimedValue(val at: Long, val value: Double)

private fun diagnoseView(
    dataset: CheckInAnalysisDatasetV2,
    evidence: BidirectionalEvidenceView,
): StateShapeDiagnosticView {
    if (evidence.focal == null || evidence.anchorAt == null) return absentShapeView(evidence.focalStateId)
    val statesById = dataset.states.associateBy { it.checkInId }
    return StateShapeDiagnosticView(
        focalStateId = evidence.focalStateId,
        focalAlias = evidence.auditIndex.stateAlias(evidence.focalStateId),
        anchorAt = evidence.anchorAt,
        candidates = evidence.candidates.map { candidate -> diagnoseCandidate(candidate, evidence.focalStateId, statesById) },
    )
}

private fun absentShapeView(focalStateId: String) = StateShapeDiagnosticView(
    focalStateId = focalStateId,
    focalAlias = null,
    anchorAt = null,
    candidates = emptyList(),
)

private fun diagnoseCandidate(
    candidate: BidirectionalCandidate,
    focalStateId: String,
    statesById: Map<String, StateObservationV2>,
): StateShapeCandidate {
    val placement = placeMembers(candidate, statesById)
    val members = placement.members
    val emotions = members.map { it.emotion }.filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .map { (emotion, count) -> EmotionCount(emotion, count) }
        .sortedBy { it.emotion }
    val axes = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        diagnoseAxis(axisId, candidate, focalStateId, members)
    }
    return StateShapeCandidate(
        span = candidate.span,
        wakingSegmentCount = candidate.segments.size,
        repeatedMembershipCount = placement.repeatedMembershipCount,
        emotions = emotions,
        emotionAbsentCount = members.count { it.emotion.isEmpty() },
        axes = axes,
    )
}

private data class Placement(
    val members: List<Member>,
    val repeatedMembershipCount: Int,
)

private fun placeMembers(
    candidate: BidirectionalCandidate,
    statesById: Map<String, StateObservationV2>,
): Placement {
    val owners = linkedMapOf<String, Int>()
    var repeated = 0
    candidate.segments.forEachIndexed { index, segment ->
        residentIds(segment, candidate).forEach { id ->
            val existing = owners[id]
            if (existing == null) {
                owners[id] = index
            } else {
                repeated += 1
                if (id == segment.rootStateId && id != candidate.segments[existing].rootStateId) {
                    owners[id] = index
                }
            }
        }
    }
    val members = owners.mapNotNull { (id, index) ->
        val state = statesById[id] ?: return@mapNotNull null
        Member(
            segmentIndex = index,
            id = id,
            at = state.recordedAt,
            emotion = state.emotion,
            raw = rawPoints(state),
        )
    }
    return Placement(members, repeated)
}

private fun residentIds(segment: BidirectionalSegment, candidate: BidirectionalCandidate): List<String> {
    val forward = segment.futureEvidence.flatMap { it.inSpanStateIds }.toSet()
    return segment.statesInSpan.mapNotNull { id ->
        if (id != segment.rootStateId && id !in forward) return@mapNotNull null
        candidate.comparableState(id)
    }.distinct()
}

private fun rawPoints(state: StateObservationV2): Map<String, Double> {
    return CheckInAnalysisDatasetV2.CORE_AXIS_IDS.mapNotNull { axisId ->
        if (axisId in state.conflictingAxisIds) return@mapNotNull null
        val reading = state.axes[axisId] ?: return@mapNotNull null
        axisId to reading.raw.toDouble()
    }.toMap()
}

private fun diagnoseAxis(
    axisId: String,
    candidate: BidirectionalCandidate,
    focalStateId: String,
    members: List<Member>,
): AxisShapeDiagnostic {
    val focalMember = members.single { it.id == focalStateId }
    val focalValue = focalMember.raw[axisId]
    val recorded = members.mapNotNull { member -> member.raw[axisId]?.let { member to it } }
    val surrounding = recorded.filter { it.first.id != focalStateId }.map { it.second }
    val stateMedian = diagnosticMedian(surrounding)
    val stateMad = if (surrounding.size < 2 || stateMedian == null) {
        null
    } else {
        diagnosticMedian(surrounding.map { abs(it - stateMedian) })
    }
    val levels = candidate.segments.mapIndexed { index, segment ->
        val values = recorded.filter { it.first.segmentIndex == index }.map { it.second }
        SegmentLevel(
            kind = segment.kind,
            recordedStateCount = values.size,
            median = diagnosticMedian(values),
        )
    }
    val segmentMedians = levels.mapNotNull { it.median }
    val balanced = diagnosticMedian(segmentMedians)
    val spread = if (segmentMedians.size < 2 || balanced == null) {
        null
    } else {
        diagnosticMedian(segmentMedians.map { abs(it - balanced) })
    }
    val focalSegments = candidate.segments.mapIndexedNotNull { index, segment ->
        if (segment.kind == SegmentKind.FOCAL_ORDERED) index else null
    }
    val slopes = if (focalSegments.size == 1) {
        val index = focalSegments.single()
        val inSegment = recorded.filter { it.first.segmentIndex == index }
        val full = inSegment.map { TimedValue(it.first.at, it.second) }
        val incoming = inSegment.filter { it.first.at <= focalMember.at }.map { TimedValue(it.first.at, it.second) }
        val outgoing = inSegment.filter { it.first.at >= focalMember.at }.map { TimedValue(it.first.at, it.second) }
        val slope = pairwiseSlope(full)
        SlopeSet(
            segmentSlope = slope,
            segmentResidual = if (focalValue == null) null else focalResidual(full, focalMember.at, focalValue),
            incomingSlope = pairwiseSlope(incoming),
            outgoingSlope = pairwiseSlope(outgoing),
        )
    } else {
        SlopeSet(null, null, null, null)
    }
    return AxisShapeDiagnostic(
        axisId = axisId,
        recordedStateCount = recorded.size,
        wakingSegmentCount = candidate.segments.size,
        focalValue = focalValue,
        stateWeightedMedian = stateMedian,
        stateWeightedMad = stateMad,
        focalDistance = if (focalValue != null && stateMedian != null) abs(focalValue - stateMedian) else null,
        segmentLevels = levels,
        segmentBalancedMedian = balanced,
        segmentMedianSpread = spread,
        segmentSlope = slopes.segmentSlope,
        segmentResidual = slopes.segmentResidual,
        incomingSlope = slopes.incomingSlope,
        outgoingSlope = slopes.outgoingSlope,
    )
}

private data class SlopeSet(
    val segmentSlope: Double?,
    val segmentResidual: Double?,
    val incomingSlope: Double?,
    val outgoingSlope: Double?,
)

private fun diagnosticMedian(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private fun pairwiseSlope(points: List<TimedValue>): Double? {
    val series = collapse(points)
    if (series.size < 2) return null
    val slopes = mutableListOf<Double>()
    for (i in series.indices) {
        for (j in i + 1 until series.size) {
            val hours = (series[j].at - series[i].at).toDouble() / MILLIS_PER_HOUR.toDouble()
            if (hours == 0.0) continue
            slopes += (series[j].value - series[i].value) / hours
        }
    }
    return diagnosticMedian(slopes)
}

private fun focalResidual(points: List<TimedValue>, focalAt: Long, focalValue: Double): Double? {
    val slope = pairwiseSlope(points) ?: return null
    val intercepts = collapse(points).map { point ->
        val hours = (point.at - focalAt).toDouble() / MILLIS_PER_HOUR.toDouble()
        point.value - slope * hours
    }
    val intercept = diagnosticMedian(intercepts) ?: return null
    return focalValue - intercept
}

private fun collapse(points: List<TimedValue>): List<TimedValue> {
    return points.groupBy { it.at }
        .entries
        .sortedBy { it.key }
        .map { (at, group) ->
            TimedValue(at, checkNotNull(diagnosticMedian(group.map { it.value })))
        }
}

fun stateShapeAuditLabel(view: StateShapeDiagnosticView): String {
    if (view.anchorAt == null || view.focalAlias == null) return stateShapeMissingLabel()
    val header = RETROSPECTIVE_STATE_SHAPE_DIAGNOSTICS_PROTOCOL + "\n" +
        "focal ${view.focalAlias}\n" +
        "Retrospective only. Every span stays visible. No preferred scale.\n" +
        "Slopes are raw axis points per hour inside the focal waking segment."
    return header + "\n" + view.candidates.joinToString("\n") { candidate -> auditCandidate(candidate) }
}

fun stateShapeMissingLabel(): String = "This check-in has no retrospective state shape diagnostics."

private fun auditCandidate(candidate: StateShapeCandidate): String {
    val span = candidate.span
    val emotions = candidate.emotions.joinToString(" · ") { "${it.emotion} ${it.count}" }
        .ifEmpty { "none" }
    val summary = "left ${radius(span.backwardMillis)} right ${radius(span.forwardMillis)}" +
        " · segments ${candidate.wakingSegmentCount}" +
        " · emotion $emotions · absent ${candidate.emotionAbsentCount}"
    val axes = candidate.axes.joinToString("\n") { axis -> auditAxis(axis) }
    return summary + "\n" + axes
}

private fun auditAxis(axis: AxisShapeDiagnostic): String {
    val levels = axis.segmentLevels.joinToString(", ") { level ->
        "${kindToken(level.kind)} ${measurement(level.median)}"
    }
    return axis.axisId +
        " · states ${axis.recordedStateCount} · segments ${axis.wakingSegmentCount}\n" +
        "focal ${measurement(axis.focalValue)}\n" +
        "state median ${measurement(axis.stateWeightedMedian)}" +
        " · mad ${measurement(axis.stateWeightedMad)}" +
        " · distance ${measurement(axis.focalDistance)}\n" +
        "segment medians $levels" +
        " · balanced ${measurement(axis.segmentBalancedMedian)}" +
        " · spread ${measurement(axis.segmentMedianSpread)}\n" +
        "slope ${measurement(axis.segmentSlope)} · residual ${measurement(axis.segmentResidual)}\n" +
        "incoming ${measurement(axis.incomingSlope)} · outgoing ${measurement(axis.outgoingSlope)}"
}

private fun kindToken(kind: SegmentKind): String = when (kind) {
    SegmentKind.FOCAL_ORDERED -> "focal-ordered"
    SegmentKind.ORDERED_CONTINUATION -> "continuation"
    SegmentKind.SAMPLED_OR_DISCONNECTED -> "sampled"
    SegmentKind.BRANCH_LOCAL_PREDECESSOR -> "branch-local"
}

private fun measurement(value: Double?): String {
    if (value == null) return "unavailable"
    val thousandths = kotlin.math.round(value * 1000.0).toLong()
    val sign = if (thousandths < 0) "-" else ""
    val absolute = kotlin.math.abs(thousandths)
    val whole = absolute / 1000
    val fraction = (absolute % 1000).toString().padStart(3, '0').trimEnd('0')
    val digits = if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    return sign + digits
}

private fun radius(millis: Long): String = when {
    millis % MILLIS_PER_DAY == 0L -> "${millis / MILLIS_PER_DAY}d"
    millis % MILLIS_PER_HOUR == 0L -> "${millis / MILLIS_PER_HOUR}h"
    else -> "${millis}ms"
}
