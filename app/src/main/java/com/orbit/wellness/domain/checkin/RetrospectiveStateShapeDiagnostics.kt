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
 * into that branch. A state claimed by two segments is ambiguous and stays out of
 * every segment summary. State-weighted summaries use each admitted check-in once.
 *
 * [InclusiveWakingSegmentSummary] describes the segments and may include the focal
 * reading. [LeaveOneOutFocalReference] judges the focal reading and excludes it.
 * The inclusive slope stays the median pairwise slope of the whole focal segment.
 * The leave-one-out trend residual refits that line without the focal reading,
 * evaluates it at the focal timestamp, and subtracts the prediction from the
 * observed value. It is separate from a Null or bounded-forecast residual.
 * Each segment level also carries the median recorded time of the residents
 * that formed its axis median.
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
        "Leave-one-out references exclude the focal check-in. " +
        "The inclusive segment median, segment-balanced median, and segment slope include the residents of each waking segment. " +
        "Those inclusive summaries are descriptions. They are not evidence about the focal point. " +
        "The leave-one-out trend residual refits the median pairwise line without the focal reading and subtracts its value at the focal timestamp. " +
        "That trend residual is separate from a Null or bounded-forecast residual. " +
        "Incoming and outgoing slopes include the focal reading only as an endpoint. " +
        "A segment level carries the median recorded time of the residents that formed its axis median. " +
        "A slope or trend residual is unavailable when fewer than two distinct timestamps remain. " +
        "A deviation is unavailable when fewer than two values remain. " +
        "An admitted state is a resident of one segment, predecessor context, or ambiguous. " +
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

enum class StateResidenceKind {
    RESIDENT,
    PREDECESSOR_CONTEXT,
    AMBIGUOUS,
}

data class StateResidence(
    val stateId: String,
    val kind: StateResidenceKind,
    val segmentIndex: Int?,
) {
    init {
        require(stateId.isNotEmpty())
        require(segmentIndex == null || segmentIndex >= 0)
        require((kind == StateResidenceKind.RESIDENT) == (segmentIndex != null))
    }
}

data class StateShapeCandidate(
    val span: BidirectionalSpan,
    val wakingSegmentCount: Int,
    val residences: List<StateResidence>,
    val emotions: List<EmotionCount>,
    val emotionAbsentCount: Int,
    val axes: List<AxisShapeDiagnostic>,
) {
    init {
        require(wakingSegmentCount >= 0)
        require(residences.map { it.stateId } == residences.map { it.stateId }.distinct())
        require(residences.filter { it.kind == StateResidenceKind.RESIDENT }.all { checkNotNull(it.segmentIndex) < wakingSegmentCount })
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
    val medianRecordedAtMillis: Double?,
) {
    init {
        require(recordedStateCount >= 0)
        require(median == null || median.isFinite())
        require(medianRecordedAtMillis == null || medianRecordedAtMillis.isFinite())
        require((median == null) == (medianRecordedAtMillis == null))
        require(recordedStateCount > 0 || median == null)
    }
}

/**
 * Descriptions of the waking segments. Medians and the full-segment slope include
 * every resident, including the focal check-in when it resides there. Incoming and
 * outgoing slopes include the focal reading only because it is an endpoint.
 * These fields do not judge the focal point.
 */
data class InclusiveWakingSegmentSummary(
    val levels: List<SegmentLevel>,
    val balancedMedian: Double?,
    val medianSpread: Double?,
    val slope: Double?,
    val incomingSlope: Double?,
    val outgoingSlope: Double?,
) {
    init {
        require(balancedMedian == null || balancedMedian.isFinite())
        require(medianSpread == null || medianSpread >= 0.0)
        require(slope == null || slope.isFinite())
        require(incomingSlope == null || incomingSlope.isFinite())
        require(outgoingSlope == null || outgoingSlope.isFinite())
    }
}

/**
 * References that judge the focal reading. Every value is computed without that reading.
 * [trendResidual] is the focal observation minus the others-only local waking-segment
 * trend prediction, evaluated at the focal timestamp. It is separate from the Null
 * learner's residual, which is an actual ending value minus a bounded forecast.
 */
data class LeaveOneOutFocalReference(
    val stateMedian: Double?,
    val stateMad: Double?,
    val distance: Double?,
    val trendResidual: Double?,
) {
    init {
        require(stateMedian == null || stateMedian.isFinite())
        require(stateMad == null || stateMad >= 0.0)
        require(distance == null || distance >= 0.0)
        require(trendResidual == null || trendResidual.isFinite())
    }
}

data class AxisShapeDiagnostic(
    val axisId: String,
    val recordedStateCount: Int,
    val wakingSegmentCount: Int,
    val focalValue: Double?,
    val leaveOneOut: LeaveOneOutFocalReference,
    val inclusive: InclusiveWakingSegmentSummary,
) {
    init {
        require(axisId in CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(recordedStateCount >= 0)
        require(wakingSegmentCount == inclusive.levels.size)
        require(focalValue == null || focalValue.isFinite())
        require(leaveOneOut.trendResidual == null || inclusive.slope != null)
        if (focalValue != null && leaveOneOut.stateMedian != null) {
            require(leaveOneOut.distance == abs(focalValue - leaveOneOut.stateMedian))
        } else {
            require(leaveOneOut.distance == null)
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
        residences = placement.residences,
        emotions = emotions,
        emotionAbsentCount = members.count { it.emotion.isEmpty() },
        axes = axes,
    )
}

private data class Placement(
    val members: List<Member>,
    val residences: List<StateResidence>,
)

private fun placeMembers(
    candidate: BidirectionalCandidate,
    statesById: Map<String, StateObservationV2>,
): Placement {
    val claims = candidate.segments.map { segment -> residentIds(segment, candidate) }
    val admitted = candidate.segments.flatMap { it.statesInSpan }
        .mapNotNull { candidate.comparableState(it) }
        .distinct()
    val residences = mutableListOf<StateResidence>()
    val members = mutableListOf<Member>()
    admitted.forEach { id ->
        val claimedBy = claims.mapIndexedNotNull { index, ids -> index.takeIf { id in ids } }
        val state = statesById[id]
        when {
            claimedBy.size > 1 -> residences += StateResidence(id, StateResidenceKind.AMBIGUOUS, null)
            claimedBy.size == 1 && state != null -> {
                val index = claimedBy.single()
                residences += StateResidence(id, StateResidenceKind.RESIDENT, index)
                members += Member(
                    segmentIndex = index,
                    id = id,
                    at = state.recordedAt,
                    emotion = state.emotion,
                    raw = rawPoints(state),
                )
            }
            claimedBy.isEmpty() && state != null ->
                residences += StateResidence(id, StateResidenceKind.PREDECESSOR_CONTEXT, null)
        }
    }
    return Placement(members, residences)
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
        val readings = recorded.filter { it.first.segmentIndex == index }
        val values = readings.map { it.second }
        val median = diagnosticMedian(values)
        SegmentLevel(
            kind = segment.kind,
            recordedStateCount = values.size,
            median = median,
            medianRecordedAtMillis = if (median == null) null else diagnosticMedian(readings.map { it.first.at.toDouble() }),
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
    val inclusiveSlopes = if (focalSegments.size == 1) {
        val index = focalSegments.single()
        val inSegment = recorded.filter { it.first.segmentIndex == index }
        val full = inSegment.map { TimedValue(it.first.at, it.second) }
        val incoming = inSegment.filter { it.first.at <= focalMember.at }.map { TimedValue(it.first.at, it.second) }
        val outgoing = inSegment.filter { it.first.at >= focalMember.at }.map { TimedValue(it.first.at, it.second) }
        val others = inSegment.filter { it.first.id != focalStateId }.map { TimedValue(it.first.at, it.second) }
        InclusiveSlopes(
            slope = pairwiseSlope(full),
            incomingSlope = pairwiseSlope(incoming),
            outgoingSlope = pairwiseSlope(outgoing),
            trendResidual = if (focalValue == null) null else leaveOneOutTrendResidual(others, focalMember.at, focalValue),
        )
    } else {
        InclusiveSlopes(null, null, null, null)
    }
    return AxisShapeDiagnostic(
        axisId = axisId,
        recordedStateCount = recorded.size,
        wakingSegmentCount = candidate.segments.size,
        focalValue = focalValue,
        leaveOneOut = LeaveOneOutFocalReference(
            stateMedian = stateMedian,
            stateMad = stateMad,
            distance = if (focalValue != null && stateMedian != null) abs(focalValue - stateMedian) else null,
            trendResidual = inclusiveSlopes.trendResidual,
        ),
        inclusive = InclusiveWakingSegmentSummary(
            levels = levels,
            balancedMedian = balanced,
            medianSpread = spread,
            slope = inclusiveSlopes.slope,
            incomingSlope = inclusiveSlopes.incomingSlope,
            outgoingSlope = inclusiveSlopes.outgoingSlope,
        ),
    )
}

private data class InclusiveSlopes(
    val slope: Double?,
    val incomingSlope: Double?,
    val outgoingSlope: Double?,
    val trendResidual: Double?,
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

private fun leaveOneOutTrendResidual(others: List<TimedValue>, focalAt: Long, focalValue: Double): Double? {
    val slope = pairwiseSlope(others) ?: return null
    val predicted = diagnosticMedian(collapse(others).map { point ->
        val hours = (point.at - focalAt).toDouble() / MILLIS_PER_HOUR.toDouble()
        point.value - slope * hours
    }) ?: return null
    return focalValue - predicted
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
    val levels = axis.inclusive.levels.joinToString(", ") { level ->
        "${kindToken(level.kind)} ${measurement(level.median)}"
    }
    return axis.axisId +
        " · states ${axis.recordedStateCount} · segments ${axis.wakingSegmentCount}\n" +
        "focal ${measurement(axis.focalValue)}\n" +
        "leave-one-out median ${measurement(axis.leaveOneOut.stateMedian)}" +
        " · mad ${measurement(axis.leaveOneOut.stateMad)}" +
        " · distance ${measurement(axis.leaveOneOut.distance)}\n" +
        "inclusive segment medians $levels" +
        " · balanced ${measurement(axis.inclusive.balancedMedian)}" +
        " · spread ${measurement(axis.inclusive.medianSpread)}\n" +
        "inclusive slope ${measurement(axis.inclusive.slope)}\n" +
        "leave-one-out trend residual ${measurement(axis.leaveOneOut.trendResidual)}\n" +
        "inclusive incoming ${measurement(axis.inclusive.incomingSlope)}" +
        " · outgoing ${measurement(axis.inclusive.outgoingSlope)}"
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
