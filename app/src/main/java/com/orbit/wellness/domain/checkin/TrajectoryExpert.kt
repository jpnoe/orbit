package com.orbit.wellness.domain.checkin

import java.time.Duration

/**
 * Past-only trajectory evidence for a later gate.
 *
 * [TrajectoryContinuity.CONTINUOUS_PATH] follows one resolved waking path.
 * It stops at a wake or day-start boundary, an unresolved link, an ambiguous
 * predecessor, invalid chronology, or a sibling branch. The focal reading is
 * included because it is already known.
 *
 * [TrajectoryContinuity.SAMPLED_BACKGROUND] summarizes earlier timestamped
 * observations and may cross those boundaries. The focal state is outside the
 * fit, so a sharp current value does not rewrite its own prior baseline. A
 * background slope is not continuous psychological motion.
 *
 * Neither family is a cause, and this pass does not blend them. Identical
 * slopes stay distinct evidence when their support or continuity differs. A
 * later gate may learn their relevance by axis, context, horizon, and person.
 * A slope is not a next-state forecast until a positive horizon is supplied.
 */
const val TRAJECTORY_EXPERT_PROTOCOL = "trajectory-expert-v2"

const val CAUSAL_STATE_SIDE_PROTOCOL = "causal-state-side-v1"

enum class TrajectoryContinuity {
    CONTINUOUS_PATH,
    SAMPLED_BACKGROUND,
}

enum class TrajectoryScaleId(
    val continuity: TrajectoryContinuity,
    val duration: Duration?,
) {
    IMMEDIATE_PATH(TrajectoryContinuity.CONTINUOUS_PATH, null),
    SIX_HOUR_PATH(TrajectoryContinuity.CONTINUOUS_PATH, Duration.ofHours(6)),
    WAKE_CYCLE_PATH(TrajectoryContinuity.CONTINUOUS_PATH, null),
    TRAILING_24_HOUR_BACKGROUND(TrajectoryContinuity.SAMPLED_BACKGROUND, Duration.ofHours(24)),
    TRAILING_7_DAY_BACKGROUND(TrajectoryContinuity.SAMPLED_BACKGROUND, Duration.ofDays(7)),
}

val PATH_TRAJECTORY_SCALES: List<TrajectoryScaleId> = listOf(
    TrajectoryScaleId.IMMEDIATE_PATH,
    TrajectoryScaleId.SIX_HOUR_PATH,
    TrajectoryScaleId.WAKE_CYCLE_PATH,
)

val BACKGROUND_TRAJECTORY_SCALES: List<TrajectoryScaleId> = listOf(
    TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND,
    TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND,
)

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
    val distinctTimestampCount: Int,
    val earliestAt: Long?,
    val latestAt: Long?,
    val elapsedMillis: Long?,
    val valueSummary: NumericSummary?,
    val fitResidualDeviation: Double?,
    val unavailable: TrajectoryExpertGap?,
    val occurrenceKeys: List<StateOccurrenceKey>,
) {
    init {
        require(observedCount == occurrenceKeys.size)
        require(distinctTimestampCount in 0..observedCount)
        require((distinctTimestampCount == 0) == (observedCount == 0))
        require((slopePerHour == null) == (unavailable != null))
        require(slopePerHour == null || (slopePerHour.isFinite() && distinctTimestampCount >= 2))
        require((fitResidualDeviation == null) == (slopePerHour == null))
        require((observedCount == 0) == (earliestAt == null))
        require((earliestAt == null) == (latestAt == null))
        require((earliestAt == null) == (elapsedMillis == null))
        require((valueSummary == null) == (observedCount == 0))
        if (valueSummary != null) require(valueSummary.count == observedCount)
        if (earliestAt != null && latestAt != null && elapsedMillis != null) {
            require(elapsedMillis == latestAt - earliestAt)
        }
    }
}

data class TrajectoryExpert(
    val stateKey: StateOccurrenceKey,
    val recordedAt: Long,
    val axisId: String,
    val scale: TrajectoryScaleId,
    val continuity: TrajectoryContinuity,
    val slopePerHour: Double?,
    /** Raw check-in change across the immediate predecessor. Not a forecast. */
    val stepDelta: Int?,
    val observedCount: Int,
    val distinctTimestampCount: Int,
    val earliestAt: Long?,
    val latestAt: Long?,
    val elapsedMillis: Long?,
    val valueSummary: NumericSummary?,
    val fitResidualDeviation: Double?,
    val unavailable: TrajectoryExpertGap?,
    val pathBreak: PathBreakReason?,
    val focalIncluded: Boolean,
    val occurrenceKeys: List<StateOccurrenceKey>,
) {
    init {
        require(continuity == scale.continuity)
        require(stepDelta == null || scale == TrajectoryScaleId.IMMEDIATE_PATH)
        require((slopePerHour == null) == (unavailable != null))
        require(observedCount == occurrenceKeys.size)
        require((valueSummary == null) == (observedCount == 0))
        when (continuity) {
            TrajectoryContinuity.SAMPLED_BACKGROUND -> {
                require(!focalIncluded)
                require(pathBreak == null)
            }
            TrajectoryContinuity.CONTINUOUS_PATH -> {
                require(focalIncluded == occurrenceKeys.any { it == stateKey })
            }
        }
    }
}

data class BackgroundDeviation(
    val scale: TrajectoryScaleId,
    val axisId: String,
    val currentMinusBackgroundMean: Double?,
    val standardizedDeviation: Double?,
    val standardizationGap: StandardizationGap?,
) {
    init {
        require(scale.continuity == TrajectoryContinuity.SAMPLED_BACKGROUND)
        require((standardizedDeviation == null) || (standardizationGap == null))
        require((currentMinusBackgroundMean == null) == (standardizedDeviation == null && standardizationGap == null))
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

/**
 * Information available at one state occurrence.
 *
 * Floor and ceiling distance stay on [trajectory]. This object does not copy
 * them and does not carry an outgoing transition, a target, or a later state.
 */
data class CausalStateSide(
    val focal: StateObservationV2,
    val trajectory: StateTrajectoryContext,
    val pathExperts: List<TrajectoryExpert>,
    val backgroundExperts: List<TrajectoryExpert>,
    val backgroundDeviations: List<BackgroundDeviation>,
) {
    init {
        require(focal.checkInId == trajectory.stateKey.raw)
        require(focal.recordedAt == trajectory.recordedAt)
        require(pathExperts.map { it.axisId to it.scale } == expectedExperts(PATH_TRAJECTORY_SCALES))
        require(backgroundExperts.map { it.axisId to it.scale } == expectedExperts(BACKGROUND_TRAJECTORY_SCALES))
        require(backgroundDeviations.map { it.axisId to it.scale } == expectedExperts(BACKGROUND_TRAJECTORY_SCALES))
        require(pathExperts.all { it.stateKey == trajectory.stateKey && it.continuity == TrajectoryContinuity.CONTINUOUS_PATH })
        require(backgroundExperts.all { it.stateKey == trajectory.stateKey && it.continuity == TrajectoryContinuity.SAMPLED_BACKGROUND })
    }
}

data class CausalStateSideDataset(
    val protocol: String = CAUSAL_STATE_SIDE_PROTOCOL,
    val pastOnly: Boolean = true,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val states: List<CausalStateSide>,
) {
    init {
        require(protocol == CAUSAL_STATE_SIDE_PROTOCOL) { "Unexpected state-side protocol" }
        require(pastOnly && !predictive && !suitableForRecommendation) {
            "State-side evidence stays past-only and is not advice"
        }
        require(states == states.sortedWith(compareBy({ it.trajectory.recordedAt }, { it.trajectory.stateKey.raw }))) {
            "State-side evidence stays in recorded-time order"
        }
    }
}

data class TrajectoryHorizonPreview(
    val expert: TrajectoryExpert,
    val forecast: ObservableForecast?,
)

fun projectCausalStateSide(dataset: CheckInAnalysisDatasetV2): CausalStateSideDataset {
    val trajectory = projectStateTrajectoryContext(dataset)
    val states = dataset.states.associateBy { it.checkInId }
    val orderedStates = dataset.states.sortedWith(compareBy({ it.recordedAt }, { it.checkInId }))
    return CausalStateSideDataset(
        states = trajectory.contexts.map { context ->
            val focal = states.getValue(context.stateKey.raw)
            val predecessors = context.resolvedPredecessorKeys.map { states.getValue(it.raw) }
            val experts = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.flatMap { axisId ->
                TrajectoryScaleId.entries.map { scale ->
                    expert(context, focal, predecessors, orderedStates, axisId, scale)
                }
            }
            val path = experts.filter { it.continuity == TrajectoryContinuity.CONTINUOUS_PATH }
            val background = experts.filter { it.continuity == TrajectoryContinuity.SAMPLED_BACKGROUND }
            CausalStateSide(
                focal = focal,
                trajectory = context,
                pathExperts = path,
                backgroundExperts = background,
                backgroundDeviations = background.map { backgroundDeviation(focal, it) },
            )
        },
    )
}

fun projectTrajectoryExperts(dataset: CheckInAnalysisDatasetV2): TrajectoryExpertDataset {
    val side = projectCausalStateSide(dataset)
    return TrajectoryExpertDataset(
        experts = side.states.flatMap { it.pathExperts + it.backgroundExperts },
    )
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
    val ordered = points.sortedWith(compareBy({ it.recordedAt }, { it.stateKey.raw }))
    val keys = ordered.map { it.stateKey }
    val summary = populationSummary(ordered.map { it.raw.toDouble() })
    val distinctTimes = ordered.map { it.recordedAt }.toSet().size
    if (ordered.isEmpty()) {
        return emptyTrend(TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS)
    }
    val earliest = ordered.first().recordedAt
    val latest = ordered.last().recordedAt
    val elapsed = latest - earliest
    if (ordered.size < 2) {
        return TrajectoryTrend(
            slopePerHour = null,
            observedCount = ordered.size,
            distinctTimestampCount = distinctTimes,
            earliestAt = earliest,
            latestAt = latest,
            elapsedMillis = elapsed,
            valueSummary = summary,
            fitResidualDeviation = null,
            unavailable = TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS,
            occurrenceKeys = keys,
        )
    }
    val hours = ordered.map { (it.recordedAt - earliest).toDouble() / MILLIS_PER_HOUR }
    val timeMean = hours.sum() / hours.size
    val timeDenominator = hours.sumOf { hour ->
        val delta = hour - timeMean
        delta * delta
    }
    if (distinctTimes < 2 || timeDenominator == 0.0) {
        return TrajectoryTrend(
            slopePerHour = null,
            observedCount = ordered.size,
            distinctTimestampCount = distinctTimes,
            earliestAt = earliest,
            latestAt = latest,
            elapsedMillis = elapsed,
            valueSummary = summary,
            fitResidualDeviation = null,
            unavailable = TrajectoryExpertGap.ZERO_TIME_SPAN,
            occurrenceKeys = keys,
        )
    }
    val values = ordered.map { it.raw.toDouble() }
    val valueMean = checkNotNull(summary).mean
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
        distinctTimestampCount = distinctTimes,
        earliestAt = earliest,
        latestAt = latest,
        elapsedMillis = elapsed,
        valueSummary = summary,
        fitResidualDeviation = populationSummary(residuals)?.deviation,
        unavailable = null,
        occurrenceKeys = keys,
    )
}

private fun expert(
    context: StateTrajectoryContext,
    focal: StateObservationV2,
    predecessors: List<StateObservationV2>,
    allStates: List<StateObservationV2>,
    axisId: String,
    scale: TrajectoryScaleId,
): TrajectoryExpert {
    val members = when (scale.continuity) {
        TrajectoryContinuity.CONTINUOUS_PATH -> pathMembers(scale, focal, predecessors)
        TrajectoryContinuity.SAMPLED_BACKGROUND -> backgroundMembers(scale, focal, allStates)
    }
    val points = members.mapNotNull { state ->
        state.recordedAxis(axisId)?.let { raw ->
            TrendPoint(StateOccurrenceKey(state.checkInId), state.recordedAt, raw)
        }
    }
    val trend = fitTrajectoryTrend(points)
    val unavailable = when (scale.continuity) {
        TrajectoryContinuity.CONTINUOUS_PATH -> when (trend.unavailable) {
            null -> null
            TrajectoryExpertGap.ZERO_TIME_SPAN -> TrajectoryExpertGap.ZERO_TIME_SPAN
            else -> gapFor(context, members.filter { it.checkInId != focal.checkInId }, predecessors)
        }
        TrajectoryContinuity.SAMPLED_BACKGROUND -> when (trend.unavailable) {
            null -> null
            TrajectoryExpertGap.ZERO_TIME_SPAN -> TrajectoryExpertGap.ZERO_TIME_SPAN
            else -> TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS
        }
    }
    val immediate = predecessors.firstOrNull { it.recordedAt < focal.recordedAt }
    val stepDelta = if (scale == TrajectoryScaleId.IMMEDIATE_PATH && immediate != null) {
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
        continuity = scale.continuity,
        slopePerHour = trend.slopePerHour,
        stepDelta = stepDelta,
        observedCount = trend.observedCount,
        distinctTimestampCount = trend.distinctTimestampCount,
        earliestAt = trend.earliestAt,
        latestAt = trend.latestAt,
        elapsedMillis = trend.elapsedMillis,
        valueSummary = trend.valueSummary,
        fitResidualDeviation = trend.fitResidualDeviation,
        unavailable = unavailable,
        pathBreak = if (scale.continuity == TrajectoryContinuity.CONTINUOUS_PATH) context.pathBreak else null,
        focalIncluded = points.any { it.stateKey.raw == focal.checkInId },
        occurrenceKeys = trend.occurrenceKeys,
    )
}

private fun backgroundDeviation(focal: StateObservationV2, expert: TrajectoryExpert): BackgroundDeviation {
    val current = focal.recordedAxis(expert.axisId)?.toDouble()
    val summary = expert.valueSummary
    val delta = if (current == null || summary == null) null else current - summary.mean
    val (standardized, gap) = if (current == null || summary == null) {
        null to null
    } else {
        standardizedAgainst(current, summary)
    }
    return BackgroundDeviation(
        scale = expert.scale,
        axisId = expert.axisId,
        currentMinusBackgroundMean = delta,
        standardizedDeviation = standardized,
        standardizationGap = gap,
    )
}

private fun standardizedAgainst(value: Double?, summary: NumericSummary): Pair<Double?, StandardizationGap?> {
    if (value == null || summary.count < 2) return null to StandardizationGap.INSUFFICIENT_SUPPORT
    if (!deviationChangesGeometry(summary.deviation, summary.mean)) {
        return null to StandardizationGap.NEGLIGIBLE_VARIATION
    }
    return (value - summary.mean) / summary.deviation to null
}

private fun pathMembers(
    scale: TrajectoryScaleId,
    focal: StateObservationV2,
    predecessors: List<StateObservationV2>,
): List<StateObservationV2> {
    val earlier = predecessors.filter { it.recordedAt < focal.recordedAt }
    val selected = when (scale) {
        TrajectoryScaleId.IMMEDIATE_PATH -> listOfNotNull(earlier.firstOrNull())
        TrajectoryScaleId.SIX_HOUR_PATH -> {
            val start = focal.recordedAt - checkNotNull(scale.duration).toMillis()
            earlier.filter { it.recordedAt >= start }
        }
        TrajectoryScaleId.WAKE_CYCLE_PATH -> earlier
        else -> error("A background scale is not a resolved path")
    }
    return selected + focal
}

private fun backgroundMembers(
    scale: TrajectoryScaleId,
    focal: StateObservationV2,
    allStates: List<StateObservationV2>,
): List<StateObservationV2> {
    val start = focal.recordedAt - checkNotNull(scale.duration).toMillis()
    return allStates.filter { state ->
        state.checkInId != focal.checkInId &&
            state.recordedAt >= start &&
            state.recordedAt < focal.recordedAt
    }
}

private fun gapFor(
    context: StateTrajectoryContext,
    earlier: List<StateObservationV2>,
    predecessors: List<StateObservationV2>,
): TrajectoryExpertGap {
    if (earlier.isNotEmpty() || predecessors.isNotEmpty()) return TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS
    return when (context.pathBreak) {
        PathBreakReason.BOUNDARY -> TrajectoryExpertGap.BOUNDARY
        PathBreakReason.UNRESOLVED -> TrajectoryExpertGap.UNRESOLVED
        PathBreakReason.INVALID_CHRONOLOGY -> TrajectoryExpertGap.INVALID_CHRONOLOGY
        PathBreakReason.AMBIGUOUS_PREDECESSOR -> TrajectoryExpertGap.AMBIGUOUS_PREDECESSOR
        null -> TrajectoryExpertGap.NO_RESOLVED_PREDECESSOR
    }
}

private fun emptyTrend(gap: TrajectoryExpertGap) = TrajectoryTrend(
    slopePerHour = null,
    observedCount = 0,
    distinctTimestampCount = 0,
    earliestAt = null,
    latestAt = null,
    elapsedMillis = null,
    valueSummary = null,
    fitResidualDeviation = null,
    unavailable = gap,
    occurrenceKeys = emptyList(),
)

private fun expectedExperts(scales: List<TrajectoryScaleId>): List<Pair<String, TrajectoryScaleId>> {
    return CheckInAnalysisDatasetV2.CORE_AXIS_IDS.flatMap { axisId ->
        scales.map { scale -> axisId to scale }
    }
}

private fun StateObservationV2.recordedAxis(axisId: String): Int? {
    return core.inOrder().firstOrNull { it.first == axisId }?.second?.raw
}

private const val MILLIS_PER_HOUR = 3_600_000.0
