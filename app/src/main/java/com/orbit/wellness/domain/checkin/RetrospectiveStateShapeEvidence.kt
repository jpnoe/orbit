package com.orbit.wellness.domain.checkin

import kotlin.math.abs

/**
 * Raw shape evidence read from [StateShapeDiagnosticView].
 *
 * Local slopes stay in raw points per hour. Between-segment slopes are raw
 * points per day because each waking segment is one observation, whatever its
 * check-in count. The leave-one-out trend residual is copied through and is
 * not a forecast residual. No shape is chosen and no support is scored.
 */
const val RETROSPECTIVE_STATE_SHAPE_EVIDENCE_PROTOCOL =
    "retrospective-state-shape-evidence-v1"

const val RETROSPECTIVE_STATE_SHAPE_EVIDENCE_ASSUMPTIONS =
    "Retrospective evidence copied from retrospective-state-shape-diagnostics-v1. " +
        "Strict as-known availability is false. This contains recorded future behavior. " +
        "It is not a forecast input, does not update a model, and is not for recommendation. " +
        "Local incoming and outgoing slopes stay in raw points per hour, with their exact signs. " +
        "The leave-one-out median, deviation, focal distance, and leave-one-out trend residual are shock and outlier ingredients. " +
        "The trend residual is the focal observation minus the others-only waking-segment trend, and it is separate from a bounded-forecast residual. " +
        "Each waking segment with a level is one observation at the median recorded time of the residents that formed that level. " +
        "A densely logged segment has the same weight as a sparse segment. " +
        "The between-segment slope is the median pairwise slope in raw points per day. " +
        "The fitted first-to-last change is that line's movement from the earliest representative to the latest, in raw points. " +
        "The fit error is the median absolute miss of that line. " +
        "Adjacent agreement counts steps whose sign matches the between-segment slope. " +
        "Five or more representatives open turn inspection. An interior representative is listed when both arms have a slope and those slopes have strictly opposite signs. " +
        "No turn is preferred. No state type, normalized support, winning shape, or preferred scale is computed."

private const val MILLIS_PER_HOUR = 3_600_000.0
private const val MILLIS_PER_DAY = 24.0 * MILLIS_PER_HOUR
private const val MINIMUM_TURN_REPRESENTATIVES = 5

fun projectRetrospectiveStateShapeEvidence(
    diagnostics: StateShapeDiagnosticDataset,
): StateShapeEvidenceDataset {
    return StateShapeEvidenceDataset(views = diagnostics.views.map { projectRetrospectiveStateShapeEvidence(it) })
}

fun projectRetrospectiveStateShapeEvidence(
    view: StateShapeDiagnosticView,
): StateShapeEvidenceView {
    if (view.anchorAt == null || view.focalAlias == null || view.candidates.isEmpty()) {
        return if (view.anchorAt == null || view.focalAlias == null) {
            absentEvidenceView(view.focalStateId)
        } else {
            StateShapeEvidenceView(
                focalStateId = view.focalStateId,
                focalAlias = view.focalAlias,
                anchorAt = view.anchorAt,
                axes = emptyList(),
            )
        }
    }
    val axisIds = view.candidates.first().axes.map { it.axisId }
    return StateShapeEvidenceView(
        focalStateId = view.focalStateId,
        focalAlias = view.focalAlias,
        anchorAt = view.anchorAt,
        axes = axisIds.map { axisId ->
            AxisShapeEvidence(
                axisId = axisId,
                scales = view.candidates.map { candidate ->
                    val axis = candidate.axes.single { it.axisId == axisId }
                    ScaleShapeEvidence(
                        span = candidate.span,
                        local = localEvidence(axis),
                        across = acrossEvidence(axis, view.anchorAt),
                    )
                },
            )
        },
    )
}

data class StateShapeEvidenceDataset(
    val protocol: String = RETROSPECTIVE_STATE_SHAPE_EVIDENCE_PROTOCOL,
    val views: List<StateShapeEvidenceView>,
) {
    init {
        require(protocol == RETROSPECTIVE_STATE_SHAPE_EVIDENCE_PROTOCOL)
        require(views == views.sortedWith(compareBy({ it.anchorAt ?: Long.MAX_VALUE }, { it.focalStateId })))
    }

    fun forFocal(focalStateId: String): StateShapeEvidenceView {
        return views.singleOrNull { it.focalStateId == focalStateId } ?: absentEvidenceView(focalStateId)
    }
}

data class StateShapeEvidenceView(
    val protocol: String = RETROSPECTIVE_STATE_SHAPE_EVIDENCE_PROTOCOL,
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
    val axes: List<AxisShapeEvidence>,
) {
    init {
        require(protocol == RETROSPECTIVE_STATE_SHAPE_EVIDENCE_PROTOCOL)
        require(retrospective && containsFuture && containsOutcome)
        require(!inputPastOnly && !predictive && !updatesModel && !suitableForRecommendation)
        require((anchorAt == null) == (focalAlias == null))
        require(anchorAt != null || axes.isEmpty())
    }
}

data class AxisShapeEvidence(
    val axisId: String,
    val scales: List<ScaleShapeEvidence>,
)

data class ScaleShapeEvidence(
    val span: BidirectionalSpan,
    val local: LocalFocalSegmentEvidence,
    val across: AcrossSegmentEvidence,
)

enum class ExactSlopeSign {
    POSITIVE,
    NEGATIVE,
    ZERO,
    UNAVAILABLE,
}

data class LocalFocalSegmentEvidence(
    val recordedStateCount: Int,
    val wakingSegmentCount: Int,
    val focalValue: Double?,
    val leaveOneOutMedian: Double?,
    val leaveOneOutMad: Double?,
    val focalDistance: Double?,
    val incomingSlope: Double?,
    val outgoingSlope: Double?,
    val incomingSign: ExactSlopeSign,
    val outgoingSign: ExactSlopeSign,
    val trendResidual: Double?,
) {
    init {
        require(recordedStateCount >= 0)
        require(wakingSegmentCount >= 0)
        require(incomingSign == exactSign(incomingSlope))
        require(outgoingSign == exactSign(outgoingSlope))
        require(focalDistance == null || focalDistance >= 0.0)
        require(leaveOneOutMad == null || leaveOneOutMad >= 0.0)
    }
}

data class AdjacentDirectionCount(
    val agreeingSteps: Int,
    val comparableSteps: Int,
) {
    init {
        require(comparableSteps > 0)
        require(agreeingSteps in 0..comparableSteps)
    }
}

data class SegmentTurnCandidate(
    val anchorRelativeMillis: Double,
    val leftSlope: Double,
    val rightSlope: Double,
    val leftArmCount: Int,
    val rightArmCount: Int,
    val leftFitError: Double,
    val rightFitError: Double,
) {
    init {
        require(anchorRelativeMillis.isFinite())
        require(leftSlope.isFinite() && rightSlope.isFinite())
        require(leftArmCount >= 2 && rightArmCount >= 2)
        require(leftFitError >= 0.0 && rightFitError >= 0.0)
        require(oppositeSigns(exactSign(leftSlope), exactSign(rightSlope)))
    }
}

data class AcrossSegmentEvidence(
    val balancedCenter: Double?,
    val balancedSpread: Double?,
    val representativeCount: Int,
    val betweenSegmentSlope: Double?,
    val fittedFirstToLastChange: Double?,
    val fitError: Double?,
    val adjacentDirection: AdjacentDirectionCount?,
    val turnsInspected: Boolean,
    val turns: List<SegmentTurnCandidate>,
) {
    init {
        require(representativeCount >= 0)
        require(balancedSpread == null || balancedSpread >= 0.0)
        require(fitError == null || fitError >= 0.0)
        require(betweenSegmentSlope == null || fittedFirstToLastChange != null)
        if (betweenSegmentSlope != null && fittedFirstToLastChange != null) {
            require(exactSign(betweenSegmentSlope) == exactSign(fittedFirstToLastChange))
        }
        require(turnsInspected == (representativeCount >= MINIMUM_TURN_REPRESENTATIVES))
        require(turnsInspected || turns.isEmpty())
        require(
            turns == turns.sortedWith(compareBy({ it.anchorRelativeMillis }, { it.leftArmCount }, { it.rightArmCount })),
        )
    }
}

private data class TimedLevel(
    val atMillis: Double,
    val level: Double,
)

private fun absentEvidenceView(focalStateId: String) = StateShapeEvidenceView(
    focalStateId = focalStateId,
    focalAlias = null,
    anchorAt = null,
    axes = emptyList(),
)

private fun localEvidence(axis: AxisShapeDiagnostic): LocalFocalSegmentEvidence {
    return LocalFocalSegmentEvidence(
        recordedStateCount = axis.recordedStateCount,
        wakingSegmentCount = axis.wakingSegmentCount,
        focalValue = axis.focalValue,
        leaveOneOutMedian = axis.leaveOneOut.stateMedian,
        leaveOneOutMad = axis.leaveOneOut.stateMad,
        focalDistance = axis.leaveOneOut.distance,
        incomingSlope = axis.inclusive.incomingSlope,
        outgoingSlope = axis.inclusive.outgoingSlope,
        incomingSign = exactSign(axis.inclusive.incomingSlope),
        outgoingSign = exactSign(axis.inclusive.outgoingSlope),
        trendResidual = axis.leaveOneOut.trendResidual,
    )
}

private fun acrossEvidence(axis: AxisShapeDiagnostic, anchorAt: Long): AcrossSegmentEvidence {
    val representatives = axis.inclusive.levels.mapIndexedNotNull { index, level ->
        val levelValue = level.median ?: return@mapIndexedNotNull null
        val at = level.medianRecordedAtMillis ?: return@mapIndexedNotNull null
        index to TimedLevel(at, levelValue)
    }.sortedWith(compareBy({ it.second.atMillis }, { it.first }))
        .map { it.second }
    val slope = robustSlope(representatives)
    val change = if (slope == null || representatives.size < 2) {
        null
    } else {
        slope * ((representatives.last().atMillis - representatives.first().atMillis) / MILLIS_PER_DAY)
    }
    val error = if (slope == null) null else robustFitError(representatives, slope)
    return AcrossSegmentEvidence(
        balancedCenter = axis.inclusive.balancedMedian,
        balancedSpread = axis.inclusive.medianSpread,
        representativeCount = representatives.size,
        betweenSegmentSlope = slope,
        fittedFirstToLastChange = change,
        fitError = error,
        adjacentDirection = if (slope == null) null else adjacentDirection(representatives, slope),
        turnsInspected = representatives.size >= MINIMUM_TURN_REPRESENTATIVES,
        turns = turnCandidates(representatives, anchorAt),
    )
}

private fun turnCandidates(representatives: List<TimedLevel>, anchorAt: Long): List<SegmentTurnCandidate> {
    if (representatives.size < MINIMUM_TURN_REPRESENTATIVES) return emptyList()
    return representatives.indices.drop(1).dropLast(1).mapNotNull { index ->
        val left = representatives.subList(0, index + 1)
        val right = representatives.subList(index, representatives.size)
        val leftSlope = robustSlope(left) ?: return@mapNotNull null
        val rightSlope = robustSlope(right) ?: return@mapNotNull null
        if (!oppositeSigns(exactSign(leftSlope), exactSign(rightSlope))) return@mapNotNull null
        SegmentTurnCandidate(
            anchorRelativeMillis = representatives[index].atMillis - anchorAt.toDouble(),
            leftSlope = leftSlope,
            rightSlope = rightSlope,
            leftArmCount = left.size,
            rightArmCount = right.size,
            leftFitError = robustFitError(left, leftSlope),
            rightFitError = robustFitError(right, rightSlope),
        )
    }.sortedWith(compareBy({ it.anchorRelativeMillis }, { it.leftArmCount }, { it.rightArmCount }))
}

private fun adjacentDirection(representatives: List<TimedLevel>, slope: Double): AdjacentDirectionCount? {
    val slopeSign = exactSign(slope)
    var comparable = 0
    var agreeing = 0
    for (index in 0 until representatives.lastIndex) {
        val elapsed = representatives[index + 1].atMillis - representatives[index].atMillis
        if (elapsed <= 0.0) continue
        comparable += 1
        val step = exactSign(representatives[index + 1].level - representatives[index].level)
        if (step == slopeSign) agreeing += 1
    }
    if (comparable == 0) return null
    return AdjacentDirectionCount(agreeing, comparable)
}

private fun robustSlope(points: List<TimedLevel>): Double? {
    val slopes = mutableListOf<Double>()
    for (left in points.indices) {
        for (right in left + 1 until points.size) {
            val days = (points[right].atMillis - points[left].atMillis) / MILLIS_PER_DAY
            if (days == 0.0) continue
            slopes += (points[right].level - points[left].level) / days
        }
    }
    return median(slopes)
}

private fun robustFitError(points: List<TimedLevel>, slope: Double): Double {
    val origin = points.first().atMillis
    val intercept = checkNotNull(median(points.map { point ->
        point.level - slope * ((point.atMillis - origin) / MILLIS_PER_DAY)
    }))
    return checkNotNull(median(points.map { point ->
        val days = (point.atMillis - origin) / MILLIS_PER_DAY
        abs(point.level - (intercept + slope * days))
    }))
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private fun exactSign(value: Double?): ExactSlopeSign = when {
    value == null -> ExactSlopeSign.UNAVAILABLE
    value > 0.0 -> ExactSlopeSign.POSITIVE
    value < 0.0 -> ExactSlopeSign.NEGATIVE
    else -> ExactSlopeSign.ZERO
}

private fun oppositeSigns(left: ExactSlopeSign, right: ExactSlopeSign): Boolean {
    return (left == ExactSlopeSign.POSITIVE && right == ExactSlopeSign.NEGATIVE) ||
        (left == ExactSlopeSign.NEGATIVE && right == ExactSlopeSign.POSITIVE)
}

fun stateShapeEvidenceMissingLabel(): String = "This check-in has no retrospective state shape evidence."

internal fun formatShapeEvidenceValue(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    val scaled = kotlin.math.round(value * 100.0).toLong()
    val absolute = kotlin.math.abs(scaled)
    val whole = absolute / 100
    val fraction = (absolute % 100).toString().padStart(2, '0').trimEnd('0')
    val digits = if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    return if (scaled < 0) "-$digits" else digits
}

fun stateShapeEvidenceTable(view: StateShapeEvidenceView): String {
    if (view.anchorAt == null || view.focalAlias == null) return stateShapeEvidenceMissingLabel()
    val header = RETROSPECTIVE_STATE_SHAPE_EVIDENCE_PROTOCOL + "\n" +
        "focal ${view.focalAlias}\n" +
        "Retrospective only. Every scale stays visible. No preferred shape.\n" +
        LEGEND
    return header + "\n\n" + view.axes.joinToString("\n\n") { axis -> axisTable(axis) }
}

private const val LEGEND =
    "Legend. Local in/out are raw points per hour. Signs are exact. " +
        "Reference, MAD, distance, and trend residual judge the focal reading. " +
        "The trend residual is not the Null/bounded-forecast residual. " +
        "Across-segment rows give each waking segment one vote. " +
        "Slope is raw points per day. Change is the fitted first-to-last movement in raw points. " +
        "Error is the median absolute miss of that line. " +
        "Agreement is agreeing/comparable adjacent steps. " +
        "A turn lists anchor-relative time, left/right slopes, arm counts n, and arm errors e. " +
        "Every opposite-sign turn is listed. — is unavailable."

private fun axisTable(axis: AxisShapeEvidence): String {
    val localHeader = listOf("scale", "states", "segs", "focal", "ref", "mad", "dist", "in → out", "trend residual")
    val localRows = axis.scales.map { scale ->
        val local = scale.local
        listOf(
            scaleLabel(scale.span),
            local.recordedStateCount.toString(),
            local.wakingSegmentCount.toString(),
            show(local.focalValue),
            show(local.leaveOneOutMedian),
            show(local.leaveOneOutMad),
            show(local.focalDistance),
            "${show(local.incomingSlope)} → ${show(local.outgoingSlope)}",
            show(local.trendResidual),
        )
    }
    val acrossHeader = listOf("scale", "center", "spread", "slope", "change", "error", "agreement", "turns")
    val acrossRows = axis.scales.map { scale ->
        val across = scale.across
        listOf(
            scaleLabel(scale.span),
            show(across.balancedCenter),
            show(across.balancedSpread),
            show(across.betweenSegmentSlope),
            show(across.fittedFirstToLastChange),
            show(across.fitError),
            agreementCell(across.adjacentDirection),
            turnsCell(across),
        )
    }
    return axis.axisId + "\nlocal\n" + monospaceTable(localHeader, localRows) +
        "\nacross\n" + monospaceTable(acrossHeader, acrossRows)
}

private fun agreementCell(agreement: AdjacentDirectionCount?): String {
    if (agreement == null) return "—"
    return "${agreement.agreeingSteps}/${agreement.comparableSteps}"
}

private fun turnsCell(across: AcrossSegmentEvidence): String {
    if (!across.turnsInspected) return "—"
    if (across.turns.isEmpty()) return "none"
    return across.turns.joinToString("; ") { turn ->
        showRelative(turn.anchorRelativeMillis) +
            " ${show(turn.leftSlope)}/${show(turn.rightSlope)}" +
            " n ${turn.leftArmCount}/${turn.rightArmCount}" +
            " e ${show(turn.leftFitError)}/${show(turn.rightFitError)}"
    }
}

private fun monospaceTable(header: List<String>, rows: List<List<String>>): String {
    val widths = header.indices.map { column ->
        maxOf(header[column].length, rows.maxOfOrNull { it[column].length } ?: 0)
    }
    fun line(cells: List<String>) = cells.mapIndexed { index, cell -> cell.padEnd(widths[index]) }.joinToString("  ")
    return (listOf(line(header)) + rows.map { line(it) }).joinToString("\n")
}

private fun scaleLabel(span: BidirectionalSpan): String {
    return "${radius(span.backwardMillis)}/${radius(span.forwardMillis)}"
}

private fun radius(millis: Long): String = when {
    millis % MILLIS_PER_DAY.toLong() == 0L -> "${millis / MILLIS_PER_DAY.toLong()}d"
    millis % MILLIS_PER_HOUR.toLong() == 0L -> "${millis / MILLIS_PER_HOUR.toLong()}h"
    else -> "${millis}ms"
}

private fun show(value: Double?): String = formatShapeEvidenceValue(value)

private fun showRelative(millis: Double): String {
    val negative = millis < 0.0
    val hours = kotlin.math.round(abs(millis) / MILLIS_PER_HOUR).toLong()
    val days = hours / 24
    val rest = hours % 24
    val body = when {
        hours == 0L -> "0h"
        rest == 0L -> "${days}d"
        days == 0L -> "${rest}h"
        else -> "${days}d${rest}h"
    }
    return (if (negative && hours != 0L) "-" else if (hours == 0L) "" else "+") + body
}
