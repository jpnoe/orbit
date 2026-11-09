package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes
import kotlin.math.abs

const val STATE_TRAJECTORY_CONTEXT_PROTOCOL = "state-trajectory-context-v1"

private const val AXIS_FLOOR = 0
private const val AXIS_CEILING = 10
private const val STORED_LAGS = 3

private val TRAJECTORY_AXES = listOf(
    DefaultCheckInAxes.MOOD,
    DefaultCheckInAxes.ENERGY,
    DefaultCheckInAxes.CONFIDENCE,
    DefaultCheckInAxes.ANXIETY,
)

/**
 * Past-only psychological trajectory at each state occurrence.
 *
 * Ordered lags walk the alternating graph backward. The walk stops at a
 * boundary, an unresolved start, invalid chronology, or more than one incoming
 * transition. It does not invent an order between equal timestamps.
 *
 * Trailing baselines use the same half-open durations as [TrailingScale]:
 * a day is `[t - 24 hours, t)` and a week is `[t - 7 days, t)`. The focal
 * state is outside both. Those windows are not copies of [TrailingDescriptor].
 * That descriptor is in prepared geometry, and the preparation behind it is
 * fit on the whole supplied history, so a later state could change an earlier
 * coordinate. These baselines stay on the raw 0–10 readings.
 *
 * A large incoming change stays a magnitude, a raw deviation, and a
 * standardized surprise when the earlier deltas support one. Nothing here
 * names that pattern or assigns it to a behavior.
 */
fun projectStateTrajectoryContext(dataset: CheckInAnalysisDatasetV2): StateTrajectoryContextDataset {
    val ids = dataset.states.map { it.checkInId }
    require(ids.size == ids.toSet().size) { "State occurrences must stay distinct" }
    return projectStateTrajectoryContext(projectAlternatingTemporalGraph(dataset))
}

internal fun projectStateTrajectoryContext(graph: AlternatingTemporalGraph): StateTrajectoryContextDataset {
    val ids = graph.states.map { it.id }
    require(ids.size == ids.toSet().size) { "State occurrences must stay distinct" }
    val steps = graph.causalSteps.associateBy { it.input.transitionId }
    val states = graph.states.sortedWith(compareBy({ it.state.recordedAt }, { it.id }))
    return StateTrajectoryContextDataset(
        contexts = states.map { state -> trajectory(state, states, steps) },
    )
}

private fun trajectory(
    state: StateOccurrenceNode,
    states: List<StateOccurrenceNode>,
    steps: Map<String, CausalStep>,
): StateTrajectoryContext {
    val path = resolvedPath(state, states.associateBy { it.id }, steps)
    val predecessors = path.keys.map { key -> states.first { it.id == key.raw } }
    val day = window(state, states, TrailingScale.DAY)
    val week = window(state, states, TrailingScale.WEEK)
    return StateTrajectoryContext(
        stateKey = StateOccurrenceKey(state.id),
        recordedAt = state.state.recordedAt,
        axes = TRAJECTORY_AXES.map { axis ->
            axisTrajectory(axis, state, predecessors, day.members, week.members)
        },
        resolvedPredecessorKeys = path.keys,
        pathBreak = path.breakReason,
        trailingDay = day.reference,
        trailingWeek = week.reference,
        resolvedPredecessorCount = path.keys.size,
    )
}

private fun resolvedPath(
    focal: StateOccurrenceNode,
    byId: Map<String, StateOccurrenceNode>,
    steps: Map<String, CausalStep>,
): ResolvedPath {
    val keys = mutableListOf<StateOccurrenceKey>()
    var cursor = focal.id
    var breakReason: PathBreakReason? = null
    while (true) {
        val node = byId.getValue(cursor)
        val incoming = node.incomingTransitionIds
        if (incoming.isEmpty()) break
        if (incoming.size > 1 || incoming.toSet().size != 1) {
            breakReason = PathBreakReason.AMBIGUOUS_PREDECESSOR
            break
        }
        val step = steps.getValue(incoming.single())
        val reasons = step.eligibility.reasons
        val startId = step.input.startingState?.checkInId
        when {
            CausalIneligibilityReason.BOUNDARY_INTERVAL in reasons -> {
                breakReason = PathBreakReason.BOUNDARY
                break
            }
            startId == null || CausalIneligibilityReason.START_UNRESOLVED in reasons -> {
                breakReason = PathBreakReason.UNRESOLVED
                break
            }
            CausalIneligibilityReason.INVALID_CHRONOLOGY in reasons ||
                step.input.startingState.recordedAt >= node.state.recordedAt -> {
                breakReason = PathBreakReason.INVALID_CHRONOLOGY
                break
            }
            startId == focal.id || keys.any { it.raw == startId } -> {
                breakReason = PathBreakReason.AMBIGUOUS_PREDECESSOR
                break
            }
            else -> {
                keys += StateOccurrenceKey(startId)
                cursor = startId
            }
        }
    }
    return ResolvedPath(keys, breakReason)
}

private fun window(
    focal: StateOccurrenceNode,
    states: List<StateOccurrenceNode>,
    scale: TrailingScale,
): TrailingMembers {
    val end = focal.state.recordedAt
    val start = end - scale.duration.toMillis()
    val members = states
        .filter { it.id != focal.id && it.state.recordedAt >= start && it.state.recordedAt < end }
        .sortedWith(compareBy({ it.state.recordedAt }, { it.id }))
    return TrailingMembers(
        reference = TrailingWindowUse(
            scale = scale,
            windowStartAt = start,
            windowEndAt = end,
            stateCount = members.size,
        ),
        members = members,
    )
}

private fun axisTrajectory(
    axisId: String,
    focal: StateOccurrenceNode,
    predecessors: List<StateOccurrenceNode>,
    day: List<StateOccurrenceNode>,
    week: List<StateOccurrenceNode>,
): AxisTrajectoryEvidence {
    val current = focal.state.axisRaw(axisId)
    val chain = listOf(focal) + predecessors
    val deltas = chain.zipWithNext().map { (later, earlier) ->
        val after = later.state.axisRaw(axisId)
        val before = earlier.state.axisRaw(axisId)
        if (after == null || before == null) null else after - before
    }
    val incoming = deltas.getOrNull(0)
    val preceding = deltas.getOrNull(1)
    val third = deltas.getOrNull(2)
    val consecutive = mutableListOf<Int>()
    for (delta in deltas) {
        if (delta == null) break
        consecutive += delta
    }
    val earlier = consecutive.drop(1)
    val earlierSummary = populationSummary(earlier.map { it.toDouble() })
    val dayBaseline = baseline(axisId, day)
    val weekBaseline = baseline(axisId, week)
    val dayDeviation = rawDeviation(current, dayBaseline.summary)
    val weekDeviation = rawDeviation(current, weekBaseline.summary)
    val (dayStandardized, dayGap) = standardized(current?.toDouble(), dayBaseline.summary)
    val (weekStandardized, weekGap) = standardized(current?.toDouble(), weekBaseline.summary)
    val (surprise, surpriseGap) = standardized(incoming?.toDouble(), earlierSummary)
    val direction = incoming?.let { directionOf(it) }
    val run = sameDirectionRun(consecutive)
    return AxisTrajectoryEvidence(
        axisId = axisId,
        currentRaw = current,
        previousRaw = predecessors.firstOrNull()?.state?.axisRaw(axisId),
        incomingDelta = incoming,
        precedingDelta = preceding,
        thirdIncomingDelta = third,
        acceleration = if (incoming != null && preceding != null) incoming - preceding else null,
        usablePredecessorCount = predecessors.count { it.state.axisRaw(axisId) != null },
        trailingDay = dayBaseline,
        trailingWeek = weekBaseline,
        deviationFromDay = dayDeviation,
        deviationFromWeek = weekDeviation,
        standardizedDayDeviation = dayStandardized,
        standardizedWeekDeviation = weekStandardized,
        dayStandardizationGap = dayGap,
        weekStandardizationGap = weekGap,
        floorDistance = current?.minus(AXIS_FLOOR),
        ceilingDistance = current?.let { AXIS_CEILING - it },
        atFloor = current?.let { it == AXIS_FLOOR },
        atCeiling = current?.let { it == AXIS_CEILING },
        absoluteIncomingDelta = incoming?.let { abs(it) },
        earlierDeltaCount = earlier.size,
        earlierDeltaBaseline = earlierSummary,
        incomingDeltaSurprise = surprise,
        surpriseGap = surpriseGap,
        direction = direction,
        sameDirectionRun = run?.count,
        sameDirectionNetChange = run?.net,
    )
}

private fun baseline(axisId: String, states: List<StateOccurrenceNode>): TemporalAxisBaseline {
    val summary = populationSummary(states.mapNotNull { it.state.axisRaw(axisId)?.toDouble() })
    return TemporalAxisBaseline(
        observedCount = summary?.count ?: 0,
        summary = summary,
    )
}

private fun rawDeviation(current: Int?, summary: NumericSummary?): Double? {
    if (current == null || summary == null) return null
    return current - summary.mean
}

private fun standardized(value: Double?, summary: NumericSummary?): Pair<Double?, StandardizationGap?> {
    if (value == null) return null to null
    if (summary == null || summary.count < 2) return null to StandardizationGap.INSUFFICIENT_SUPPORT
    if (!deviationChangesGeometry(summary.deviation, summary.mean)) {
        return null to StandardizationGap.NEGLIGIBLE_VARIATION
    }
    return (value - summary.mean) / summary.deviation to null
}

private fun directionOf(delta: Int): DeltaDirection {
    return when {
        delta > 0 -> DeltaDirection.RISING
        delta < 0 -> DeltaDirection.FALLING
        else -> DeltaDirection.FLAT
    }
}

private fun sameDirectionRun(consecutive: List<Int>): DirectionRun? {
    val first = consecutive.firstOrNull() ?: return null
    val direction = directionOf(first)
    var count = 0
    var net = 0
    for (delta in consecutive) {
        if (directionOf(delta) != direction) break
        count += 1
        net += delta
    }
    return DirectionRun(count, net)
}

private fun StateObservationV2.axisRaw(axisId: String): Int? {
    return when (axisId) {
        DefaultCheckInAxes.MOOD -> core.mood?.raw
        DefaultCheckInAxes.ENERGY -> core.energy?.raw
        DefaultCheckInAxes.CONFIDENCE -> core.confidence?.raw
        DefaultCheckInAxes.ANXIETY -> core.anxiety?.raw
        else -> null
    }
}

private data class ResolvedPath(
    val keys: List<StateOccurrenceKey>,
    val breakReason: PathBreakReason?,
)

private data class TrailingMembers(
    val reference: TrailingWindowUse,
    val members: List<StateOccurrenceNode>,
)

private data class DirectionRun(
    val count: Int,
    val net: Int,
)

data class StateTrajectoryContextDataset(
    val protocol: String = STATE_TRAJECTORY_CONTEXT_PROTOCOL,
    val retrospective: Boolean = true,
    val pastOnly: Boolean = true,
    val withinIndividualHistory: Boolean = true,
    val causal: Boolean = false,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val contexts: List<StateTrajectoryContext>,
) {
    init {
        require(protocol == STATE_TRAJECTORY_CONTEXT_PROTOCOL) { "Unexpected trajectory protocol" }
        require(retrospective && pastOnly && withinIndividualHistory) {
            "Trajectory context stays inside earlier history"
        }
        require(!causal && !predictive && !suitableForRecommendation) {
            "Trajectory context is not a cause or a recommendation"
        }
        require(contexts == contexts.sortedWith(compareBy({ it.recordedAt }, { it.stateKey.raw }))) {
            "Trajectory contexts stay in recorded-time order"
        }
    }
}

data class StateTrajectoryContext(
    val stateKey: StateOccurrenceKey,
    val recordedAt: Long,
    val axes: List<AxisTrajectoryEvidence>,
    /** Most recent resolved predecessor first. */
    val resolvedPredecessorKeys: List<StateOccurrenceKey>,
    val pathBreak: PathBreakReason?,
    val trailingDay: TrailingWindowUse,
    val trailingWeek: TrailingWindowUse,
    val resolvedPredecessorCount: Int,
) {
    init {
        require(axes.map { it.axisId } == TRAJECTORY_AXES) { "Trajectory axes stay in core order" }
        require(resolvedPredecessorCount == resolvedPredecessorKeys.size) {
            "Predecessor coverage counts the resolved path"
        }
    }
}

/**
 * Why ordered history stopped. A path that has simply reached its first
 * recorded state has no break. [BOUNDARY] includes a wake or day-start even
 * when a previous id resolves, so that link is not used.
 */
enum class PathBreakReason {
    BOUNDARY,
    UNRESOLVED,
    INVALID_CHRONOLOGY,
    AMBIGUOUS_PREDECESSOR,
}

data class TrailingWindowUse(
    val scale: TrailingScale,
    val windowStartAt: Long,
    val windowEndAt: Long,
    val stateCount: Int,
)

data class AxisTrajectoryEvidence(
    val axisId: String,
    val currentRaw: Int?,
    val previousRaw: Int?,
    val incomingDelta: Int?,
    val precedingDelta: Int?,
    val thirdIncomingDelta: Int?,
    val acceleration: Int?,
    val usablePredecessorCount: Int,
    val trailingDay: TemporalAxisBaseline,
    val trailingWeek: TemporalAxisBaseline,
    val deviationFromDay: Double?,
    val deviationFromWeek: Double?,
    val standardizedDayDeviation: Double?,
    val standardizedWeekDeviation: Double?,
    val dayStandardizationGap: StandardizationGap?,
    val weekStandardizationGap: StandardizationGap?,
    val floorDistance: Int?,
    val ceilingDistance: Int?,
    val atFloor: Boolean?,
    val atCeiling: Boolean?,
    val absoluteIncomingDelta: Int?,
    val earlierDeltaCount: Int,
    val earlierDeltaBaseline: NumericSummary?,
    /** Signed `(recent delta - earlier-delta mean) / earlier-delta deviation`. */
    val incomingDeltaSurprise: Double?,
    val surpriseGap: StandardizationGap?,
    val direction: DeltaDirection?,
    val sameDirectionRun: Int?,
    val sameDirectionNetChange: Int?,
)

data class TemporalAxisBaseline(
    val observedCount: Int,
    val summary: NumericSummary?,
) {
    init {
        require(observedCount >= 0)
        require((summary == null) == (observedCount == 0)) { "An empty baseline has no summary" }
        if (summary != null) require(summary.count == observedCount) { "Baseline support must match" }
    }
}

enum class StandardizationGap {
    INSUFFICIENT_SUPPORT,
    NEGLIGIBLE_VARIATION,
}

enum class DeltaDirection {
    RISING,
    FALLING,
    FLAT,
}
