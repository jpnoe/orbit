package com.orbit.wellness.domain.checkin

import java.time.Duration

/**
 * Past-only trajectory evidence for a later gate.
 *
 * These experts are candidate evidence channels. They are not independent
 * causes, and this pass does not blend them. A later model may learn how much
 * to trust each channel from the axis, support, fit error, floor and ceiling
 * distance, time of day, time since wake, volatility, physiology, exposure,
 * the requested horizon, and a broader regime.
 *
 * Windowed slopes are ordinary least squares in raw points per hour. The focal
 * reading is included, because it is known at prediction time. Earlier points
 * come only from the uniquely resolved predecessor path, inside `[t - scale, t)`.
 * A slope is not a next-state forecast until a positive horizon is supplied.
 * Step momentum answers a different question: whether the last recorded change
 * would repeat once. It is not one of these time-normalized experts.
 */
const val TRAJECTORY_EXPERT_PROTOCOL = "trajectory-expert-v1"

enum class TrajectoryScaleId(val duration: Duration?) {
    IMMEDIATE(null),
    HOURS_6(Duration.ofHours(6)),
    HOURS_24(Duration.ofHours(24)),
    DAYS_7(Duration.ofDays(7)),
}

enum class TrajectoryExpertGap {
    NO_RESOLVED_PREDECESSOR,
    BOUNDARY,
    UNRESOLVED,
    INVALID_CHRONOLOGY,
    AMBIGUOUS_PREDECESSOR,
    INSUFFICIENT_OBSERVATIONS,
    ZERO_TIME_SPAN,
}

data class TrendPoint(
    val stateKey: StateOccurrenceKey,
    val recordedAt: Long,
    val raw: Int,
)

data class TrajectoryTrend(
    val slopePerHour: Double?,
    val observedCount: Int,
    val earliestAt: Long?,
    val latestAt: Long?,
    val elapsedMillis: Long?,
    val valueDeviation: Double?,
    val fitResidualDeviation: Double?,
    val unavailable: TrajectoryExpertGap?,
    val occurrenceKeys: List<StateOccurrenceKey>,
) {
    init {
        require(observedCount == occurrenceKeys.size)
        require((slopePerHour == null) == (unavailable != null))
        require(slopePerHour == null || slopePerHour.isFinite())
        require((fitResidualDeviation == null) == (slopePerHour == null))
        require((observedCount == 0) == (earliestAt == null))
        require((earliestAt == null) == (latestAt == null))
        require((earliestAt == null) == (elapsedMillis == null))
        if (earliestAt != null && latestAt != null && elapsedMillis != null) {
            require(elapsedMillis == latestAt - earliestAt)
        }
        require((valueDeviation == null) == (observedCount == 0))
    }
}

data class TrajectoryExpert(
    val stateKey: StateOccurrenceKey,
    val recordedAt: Long,
    val axisId: String,
    val scale: TrajectoryScaleId,
    val slopePerHour: Double?,
    /** Raw check-in change across the immediate predecessor. Not a forecast. */
    val stepDelta: Int?,
    val observedCount: Int,
    val earliestAt: Long?,
    val latestAt: Long?,
    val elapsedMillis: Long?,
    val valueDeviation: Double?,
    val fitResidualDeviation: Double?,
    val unavailable: TrajectoryExpertGap?,
    val pathBreak: PathBreakReason?,
    val occurrenceKeys: List<StateOccurrenceKey>,
) {
    init {
        require(stepDelta == null || scale == TrajectoryScaleId.IMMEDIATE)
        require((slopePerHour == null) == (unavailable != null))
        require(observedCount == occurrenceKeys.size)
    }
}

data class TrajectoryExpertDataset(
    val protocol: String = TRAJECTORY_EXPERT_PROTOCOL,
    val pastOnly: Boolean = true,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val experts: List<TrajectoryExpert>,
) {
    init {
        require(protocol == TRAJECTORY_EXPERT_PROTOCOL) { "Unexpected trajectory-expert protocol" }
        require(pastOnly && !predictive && !suitableForRecommendation) {
            "Trajectory experts stay past-only and are not advice"
        }
    }
}

data class TrajectoryHorizonPreview(
    val expert: TrajectoryExpert,
    val forecast: ObservableForecast?,
)

fun projectTrajectoryExperts(dataset: CheckInAnalysisDatasetV2): TrajectoryExpertDataset {
    val trajectory = projectStateTrajectoryContext(dataset)
    val states = dataset.states.associateBy { it.checkInId }
    val experts = trajectory.contexts.flatMap { context ->
        val focal = states.getValue(context.stateKey.raw)
        val predecessors = context.resolvedPredecessorKeys.map { states.getValue(it.raw) }
        CheckInAnalysisDatasetV2.CORE_AXIS_IDS.flatMap { axisId ->
            TrajectoryScaleId.entries.map { scale ->
                expert(context, focal, predecessors, axisId, scale)
            }
        }
    }
    return TrajectoryExpertDataset(experts = experts)
}

/**
 * Projects one expert along a supplied horizon. A null horizon, a missing
 * start, or an unavailable slope produces no endpoint. The horizon is not
 * read from a later interval.
 */
fun projectExpertHorizon(
    startingRaw: Int?,
    expert: TrajectoryExpert,
    horizonHours: Double?,
): ObservableForecast? {
    if (horizonHours == null || startingRaw == null || expert.slopePerHour == null) return null
    require(horizonHours.isFinite() && horizonHours > 0.0) { "A forecast horizon is finite and positive" }
    return observableForecast(startingRaw + expert.slopePerHour * horizonHours)
}

fun fitTrajectoryTrend(points: List<TrendPoint>): TrajectoryTrend {
    val ordered = points.sortedBy { it.recordedAt }
    val keys = ordered.map { it.stateKey }
    val values = ordered.map { it.raw.toDouble() }
    val deviation = populationSummary(values)?.deviation
    if (ordered.isEmpty()) {
        return TrajectoryTrend(
            slopePerHour = null,
            observedCount = 0,
            earliestAt = null,
            latestAt = null,
            elapsedMillis = null,
            valueDeviation = null,
            fitResidualDeviation = null,
            unavailable = TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS,
            occurrenceKeys = emptyList(),
        )
    }
    val earliest = ordered.first().recordedAt
    val latest = ordered.last().recordedAt
    val elapsed = latest - earliest
    if (ordered.size < 2) {
        return TrajectoryTrend(
            slopePerHour = null,
            observedCount = ordered.size,
            earliestAt = earliest,
            latestAt = latest,
            elapsedMillis = elapsed,
            valueDeviation = deviation,
            fitResidualDeviation = null,
            unavailable = TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS,
            occurrenceKeys = keys,
        )
    }
    val hours = ordered.map { (it.recordedAt - earliest).toDouble() / MILLIS_PER_HOUR }
    val distinctTimes = ordered.map { it.recordedAt }.toSet().size
    val timeMean = hours.sum() / hours.size
    val timeDenominator = hours.sumOf { hour ->
        val delta = hour - timeMean
        delta * delta
    }
    if (distinctTimes < 2 || timeDenominator == 0.0) {
        return TrajectoryTrend(
            slopePerHour = null,
            observedCount = ordered.size,
            earliestAt = earliest,
            latestAt = latest,
            elapsedMillis = elapsed,
            valueDeviation = deviation,
            fitResidualDeviation = null,
            unavailable = TrajectoryExpertGap.ZERO_TIME_SPAN,
            occurrenceKeys = keys,
        )
    }
    val valueMean = values.sum() / values.size
    val slope = hours.zip(values).sumOf { (hour, value) ->
        (hour - timeMean) * (value - valueMean)
    } / timeDenominator
    val intercept = valueMean - slope * timeMean
    val residuals = hours.zip(values).map { (hour, value) ->
        value - (intercept + slope * hour)
    }
    return TrajectoryTrend(
        slopePerHour = slope,
        observedCount = ordered.size,
        earliestAt = earliest,
        latestAt = latest,
        elapsedMillis = elapsed,
        valueDeviation = deviation,
        fitResidualDeviation = populationSummary(residuals)?.deviation,
        unavailable = null,
        occurrenceKeys = keys,
    )
}

private fun expert(
    context: StateTrajectoryContext,
    focal: StateObservationV2,
    predecessors: List<StateObservationV2>,
    axisId: String,
    scale: TrajectoryScaleId,
): TrajectoryExpert {
    val earlier = earlierStates(scale, focal.recordedAt, predecessors)
    val points = (earlier + focal).mapNotNull { state ->
        state.recordedAxis(axisId)?.let { raw ->
            TrendPoint(StateOccurrenceKey(state.checkInId), state.recordedAt, raw)
        }
    }
    val trend = fitTrajectoryTrend(points)
    val unavailable = when (trend.unavailable) {
        null -> null
        TrajectoryExpertGap.ZERO_TIME_SPAN -> TrajectoryExpertGap.ZERO_TIME_SPAN
        else -> gapFor(context, earlier, predecessors)
    }
    val immediate = predecessors.firstOrNull { it.recordedAt < focal.recordedAt }
    val stepDelta = if (scale == TrajectoryScaleId.IMMEDIATE && immediate != null) {
        val later = focal.recordedAxis(axisId)
        val before = immediate.recordedAxis(axisId)
        if (later == null || before == null) null else later - before
    } else {
        null
    }
    return TrajectoryExpert(
        stateKey = context.stateKey,
        recordedAt = context.recordedAt,
        axisId = axisId,
        scale = scale,
        slopePerHour = trend.slopePerHour,
        stepDelta = stepDelta,
        observedCount = trend.observedCount,
        earliestAt = trend.earliestAt,
        latestAt = trend.latestAt,
        elapsedMillis = trend.elapsedMillis,
        valueDeviation = trend.valueDeviation,
        fitResidualDeviation = trend.fitResidualDeviation,
        unavailable = unavailable,
        pathBreak = context.pathBreak,
        occurrenceKeys = trend.occurrenceKeys,
    )
}

private fun gapFor(
    context: StateTrajectoryContext,
    earlier: List<StateObservationV2>,
    predecessors: List<StateObservationV2>,
): TrajectoryExpertGap? {
    if (earlier.isNotEmpty() || predecessors.isNotEmpty()) return TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS
    return when (context.pathBreak) {
        PathBreakReason.BOUNDARY -> TrajectoryExpertGap.BOUNDARY
        PathBreakReason.UNRESOLVED -> TrajectoryExpertGap.UNRESOLVED
        PathBreakReason.INVALID_CHRONOLOGY -> TrajectoryExpertGap.INVALID_CHRONOLOGY
        PathBreakReason.AMBIGUOUS_PREDECESSOR -> TrajectoryExpertGap.AMBIGUOUS_PREDECESSOR
        null -> TrajectoryExpertGap.NO_RESOLVED_PREDECESSOR
    }
}

private fun earlierStates(
    scale: TrajectoryScaleId,
    focalAt: Long,
    predecessors: List<StateObservationV2>,
): List<StateObservationV2> {
    val strictlyEarlier = predecessors.filter { it.recordedAt < focalAt }
    return when (scale) {
        TrajectoryScaleId.IMMEDIATE -> listOfNotNull(strictlyEarlier.firstOrNull())
        else -> {
            val duration = checkNotNull(scale.duration)
            val start = focalAt - duration.toMillis()
            strictlyEarlier.filter { it.recordedAt >= start }
        }
    }
}

private fun StateObservationV2.recordedAxis(axisId: String): Int? {
    return core.inOrder().firstOrNull { it.first == axisId }?.second?.raw
}

private const val MILLIS_PER_HOUR = 3_600_000.0
