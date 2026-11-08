package com.orbit.wellness.domain.checkin

import java.time.Duration
import kotlin.math.sqrt

/**
 * Equal weight for every major multiscale segment. It is schema-level.
 * A later pass can replace it. Rows are not stretched to unit length.
 */
const val MULTISCALE_SEGMENT_WEIGHT = 1.0

private val TRAILING_DAY: Duration = Duration.ofHours(24)
private val TRAILING_WEEK: Duration = Duration.ofDays(7)
private const val MILLIS_PER_HOUR = 3_600_000.0

enum class TrailingScale(val duration: Duration) {
    DAY(TRAILING_DAY),
    WEEK(TRAILING_WEEK),
}

/**
 * Whether a within-window slope is part of this dimension.
 * [NotApplicable] is an emotion or other non-axis column.
 * [Unavailable] is an axis without two distinct timestamps.
 */
sealed interface TrendStatus {
    data object NotApplicable : TrendStatus
    data object Unavailable : TrendStatus
    data class Slope(val perHour: Double) : TrendStatus
}

data class StateDimensionSummary(
    val index: Int,
    val semantic: FeatureSemantic,
    val observedCount: Int,
    val mean: Double,
    val deviation: Double,
    val minimum: Double,
    val maximum: Double,
    val trend: TrendStatus,
    val available: Boolean,
)

data class TransitionDimensionSummary(
    val index: Int,
    val semantic: FeatureSemantic,
    val observedCount: Int,
    val mean: Double,
    val deviation: Double,
    /**
     * Fraction of observed intervals in which a binary presence bit is on.
     * Null for numeric columns. This is interval prevalence, not minutes spent.
     */
    val intervalPrevalence: Double?,
    val available: Boolean,
)

data class TemporalCoverage(
    val historicalStateCount: Int,
    val containedTransitionCount: Int,
    val overlappingTransitionCount: Int,
    val incompleteTransitionCount: Int,
    val earliestStateAt: Long?,
    val latestStateAt: Long?,
    val earliestIntervalStartAt: Long?,
    val latestIntervalEndAt: Long?,
    val representedDurationMillis: Long,
)

data class StateHistorySummary(
    val available: Boolean,
    val coverage: TemporalCoverage,
    val dimensions: List<StateDimensionSummary>,
    /** Mask-aware means. An empty window is entirely unobserved, not a mean of zero. */
    val geometry: MaskedVector,
)

data class TransitionHistorySummary(
    val available: Boolean,
    val includedTransitionCount: Int,
    val overlappingTransitionCount: Int,
    val incompleteTransitionCount: Int,
    val representedDurationMillis: Long,
    val dimensions: List<TransitionDimensionSummary>,
    val geometry: MaskedVector,
)

data class TrailingDescriptor(
    val scale: TrailingScale,
    val windowStartAt: Long,
    val windowEndAt: Long,
    val stateHistory: StateHistorySummary,
    val transitionHistory: TransitionHistorySummary,
)

data class DeviationGroup(
    val name: String,
    val values: MaskedVector,
    /** Historical observed count supporting each coordinate. Zero when unavailable. */
    val support: List<Int>,
)

data class MultiscaleSegment(
    val name: String,
    val weight: Double,
    val effectiveBlockCount: Int,
    val factor: Double,
    val features: List<FeatureScale>,
) {
    init {
        if (effectiveBlockCount == 0) {
            require(factor == 0.0) { "$name has no effective blocks" }
        } else {
            require(factor.isFinite() && factor > 0.0) { "$name factor must be finite" }
        }
    }
}

data class MultiscaleSchema(
    val retrospective: List<MultiscaleSegment>,
    val causal: List<MultiscaleSegment>,
)

data class MultiscaleState(
    val key: StateOccurrenceKey,
    val recordedAt: Long,
    /** The committed local contextual state. This projection does not rebuild it. */
    val local: ContextualState,
    val trailingDay: TrailingDescriptor,
    val trailingWeek: TrailingDescriptor,
    val currentMinusDay: DeviationGroup,
    val currentMinusWeek: DeviationGroup,
    val dayMinusWeek: DeviationGroup,
)

/**
 * Causal view of the starting state. Outgoing local context is not a field.
 * The transition that is being predicted stays on [CausalMultiscaleInput.transition].
 */
data class CausalStartingState(
    val key: StateOccurrenceKey,
    val recordedAt: Long,
    val self: MaskedVector,
    val quality: MaskedVector,
    val trailingDay: TrailingDescriptor,
    val trailingWeek: TrailingDescriptor,
    val currentMinusDay: DeviationGroup,
    val currentMinusWeek: DeviationGroup,
    val dayMinusWeek: DeviationGroup,
)

data class CausalMultiscaleInput(
    val startingState: CausalStartingState?,
    val transition: PreparedChannel,
)

data class CausalMultiscaleExample(
    val exampleKey: TransitionOccurrenceKey,
    val input: CausalMultiscaleInput,
    val target: PreparedChannel,
    val eligibility: CausalEligibility,
)

enum class MultiscaleDiagnosticCode {
    INTERVAL_ENDS_BEFORE_IT_STARTS,
    MISSING_PREPARED_STATE,
    MISSING_PREPARED_TRANSITION,
    CAUSAL_START_UNRESOLVED,
}

data class MultiscaleDiagnostic(
    val code: MultiscaleDiagnosticCode,
    val anchorKey: String?,
    val detail: String,
)

data class FlatMultiscaleVector(
    val values: List<Double>,
    val observed: List<Boolean>,
) {
    init {
        require(values.size == observed.size) { "A flat vector needs one mask bit per value" }
        require(values.all { it.isFinite() }) { "A flat value must be finite" }
    }
}

data class MultiscaleContext(
    val schema: MultiscaleSchema,
    val states: List<MultiscaleState>,
    val causalExamples: List<CausalMultiscaleExample>,
    val eligibleCausalExamples: List<CausalMultiscaleExample>,
    val diagnostics: List<MultiscaleDiagnostic>,
)

/**
 * Fixed trailing day and week descriptors over a committed [LocalContext].
 *
 * Windows use [TemporalWindowProjector.rolling] and [classifyInterval].
 * Nothing here refits the local context or writes a transition vector back.
 * Fitting that produced [local] describes the dataset it was given.
 */
fun projectMultiscale(evidence: ContextualEvidence, local: LocalContext): MultiscaleContext {
    val diagnostics = mutableListOf<MultiscaleDiagnostic>()
    val schema = multiscaleSchema(local.model)
    val preparedStates = local.states.associateBy { it.key }
    val preparedTransitions = local.transitions.associateBy { it.key }
    val evidenceStates = evidence.states.sortedWith(compareBy({ it.metadata.recordedAt }, { it.key.raw }))
    val evidenceTransitions = evidence.transitions.sortedWith(
        compareBy({ it.evidence.temporal.endAt }, { it.key.raw }),
    )
    val states = local.contextualStates.sortedBy { it.key.raw }.map { contextual ->
        val recordedAt = evidence.statesByKey[contextual.key]?.metadata?.recordedAt
        if (recordedAt == null) {
            diagnostics += MultiscaleDiagnostic(
                code = MultiscaleDiagnosticCode.MISSING_PREPARED_STATE,
                anchorKey = contextual.key.raw,
                detail = "Contextual state ${contextual.key.raw} has no recorded time",
            )
        }
        val clock = recordedAt
        if (clock == null) {
            return@map unavailableState(contextual, local.model)
        }
        val day = descriptor(
            scale = TrailingScale.DAY,
            anchor = contextual.key,
            recordedAt = clock,
            evidenceStates = evidenceStates,
            evidenceTransitions = evidenceTransitions,
            preparedStates = preparedStates,
            preparedTransitions = preparedTransitions,
            model = local.model,
            diagnostics = diagnostics,
        )
        val week = descriptor(
            scale = TrailingScale.WEEK,
            anchor = contextual.key,
            recordedAt = clock,
            evidenceStates = evidenceStates,
            evidenceTransitions = evidenceTransitions,
            preparedStates = preparedStates,
            preparedTransitions = preparedTransitions,
            model = local.model,
            diagnostics = diagnostics,
        )
        val self = contextual.self
        MultiscaleState(
            key = contextual.key,
            recordedAt = clock,
            local = contextual,
            trailingDay = day,
            trailingWeek = week,
            currentMinusDay = deviation(
                "deviation.current_minus_day",
                self,
                day.stateHistory,
            ),
            currentMinusWeek = deviation(
                "deviation.current_minus_week",
                self,
                week.stateHistory,
            ),
            dayMinusWeek = historyDeviation(day.stateHistory, week.stateHistory),
        )
    }
    val statesByKey = states.associateBy { it.key }
    val links = evidence.retrospectiveTransitions.associateBy { it.frame.key }
    val causal = local.causalExamples.map { example ->
        val link = links[example.exampleKey]
        val startKey = link?.startStateKey
        val starting = when {
            example.input.startingState == null || startKey == null -> null
            else -> {
                val anchor = statesByKey[startKey]
                if (anchor == null) {
                    diagnostics += MultiscaleDiagnostic(
                        code = MultiscaleDiagnosticCode.CAUSAL_START_UNRESOLVED,
                        anchorKey = example.exampleKey.raw,
                        detail = "Causal start ${startKey.raw} has no multiscale state",
                    )
                    null
                } else {
                    CausalStartingState(
                        key = anchor.key,
                        recordedAt = anchor.recordedAt,
                        self = anchor.local.self,
                        quality = anchor.local.quality,
                        trailingDay = anchor.trailingDay,
                        trailingWeek = anchor.trailingWeek,
                        currentMinusDay = anchor.currentMinusDay,
                        currentMinusWeek = anchor.currentMinusWeek,
                        dayMinusWeek = anchor.dayMinusWeek,
                    )
                }
            }
        }
        CausalMultiscaleExample(
            exampleKey = example.exampleKey,
            input = CausalMultiscaleInput(
                startingState = starting,
                transition = example.input.transition,
            ),
            target = example.target,
            eligibility = example.eligibility,
        )
    }
    return MultiscaleContext(
        schema = schema,
        states = states,
        causalExamples = causal,
        eligibleCausalExamples = causal.filter { it.eligibility.eligible },
        diagnostics = diagnostics.sortedWith(compareBy({ it.anchorKey ?: "" }, { it.code.name }, { it.detail })),
    )
}

fun flatten(state: MultiscaleState, schema: MultiscaleSchema): FlatMultiscaleVector {
    return flattenSegments(schema.retrospective) { segment ->
        when (segment.name) {
            "local.self" -> state.local.self
            "local.incoming.transition" -> pooled(state.local.incoming.transition, segment.features.size)
            "local.incoming.opposite_state" -> pooled(state.local.incoming.oppositeState, segment.features.size)
            "local.outgoing.transition" -> pooled(state.local.outgoing.transition, segment.features.size)
            "local.outgoing.opposite_state" -> pooled(state.local.outgoing.oppositeState, segment.features.size)
            "trailing.day.state" -> state.trailingDay.stateHistory.geometry
            "trailing.day.transition" -> state.trailingDay.transitionHistory.geometry
            "trailing.week.state" -> state.trailingWeek.stateHistory.geometry
            "trailing.week.transition" -> state.trailingWeek.transitionHistory.geometry
            "deviation.current_minus_day" -> state.currentMinusDay.values
            "deviation.current_minus_week" -> state.currentMinusWeek.values
            "deviation.day_minus_week" -> state.dayMinusWeek.values
            else -> error("Unknown retrospective segment ${segment.name}")
        }
    }
}

fun flattenCausalStart(start: CausalStartingState, schema: MultiscaleSchema): FlatMultiscaleVector {
    return flattenSegments(schema.causal) { segment ->
        when (segment.name) {
            "local.self" -> start.self
            "trailing.day.state" -> start.trailingDay.stateHistory.geometry
            "trailing.day.transition" -> start.trailingDay.transitionHistory.geometry
            "trailing.week.state" -> start.trailingWeek.stateHistory.geometry
            "trailing.week.transition" -> start.trailingWeek.transitionHistory.geometry
            "deviation.current_minus_day" -> start.currentMinusDay.values
            "deviation.current_minus_week" -> start.currentMinusWeek.values
            "deviation.day_minus_week" -> start.dayMinusWeek.values
            else -> error("Causal layout does not include ${segment.name}")
        }
    }
}

private fun unavailableState(contextual: ContextualState, model: FeaturePreparationModel): MultiscaleState {
    val day = emptyDescriptor(TrailingScale.DAY, model)
    val week = emptyDescriptor(TrailingScale.WEEK, model)
    val self = contextual.self
    return MultiscaleState(
        key = contextual.key,
        recordedAt = 0L,
        local = contextual,
        trailingDay = day,
        trailingWeek = week,
        currentMinusDay = deviation("deviation.current_minus_day", self, day.stateHistory),
        currentMinusWeek = deviation("deviation.current_minus_week", self, week.stateHistory),
        dayMinusWeek = historyDeviation(day.stateHistory, week.stateHistory),
    )
}

private fun emptyDescriptor(scale: TrailingScale, model: FeaturePreparationModel): TrailingDescriptor {
    val window = TemporalWindowProjector.rolling(0L, scale.duration)
    val stateFeatures = model.state.substantive.features
    val transitionFeatures = model.transition.substantive.features
    val coverage = TemporalCoverage(0, 0, 0, 0, null, null, null, null, 0L)
    return TrailingDescriptor(
        scale = scale,
        windowStartAt = window.startAt,
        windowEndAt = window.endAt,
        stateHistory = StateHistorySummary(
            available = false,
            coverage = coverage,
            dimensions = stateFeatures.map { emptyStateDimension(it) },
            geometry = unavailable(stateFeatures.size),
        ),
        transitionHistory = TransitionHistorySummary(
            available = false,
            includedTransitionCount = 0,
            overlappingTransitionCount = 0,
            incompleteTransitionCount = 0,
            representedDurationMillis = 0L,
            dimensions = transitionFeatures.map { emptyTransitionDimension(it) },
            geometry = unavailable(transitionFeatures.size),
        ),
    )
}

private fun descriptor(
    scale: TrailingScale,
    anchor: StateOccurrenceKey,
    recordedAt: Long,
    evidenceStates: List<StateEvidenceFrame>,
    evidenceTransitions: List<TransitionEvidenceFrame>,
    preparedStates: Map<StateOccurrenceKey, PreparedState>,
    preparedTransitions: Map<TransitionOccurrenceKey, PreparedTransition>,
    model: FeaturePreparationModel,
    diagnostics: MutableList<MultiscaleDiagnostic>,
): TrailingDescriptor {
    val window = TemporalWindowProjector.rolling(recordedAt, scale.duration)
    val history = evidenceStates.mapNotNull { frame ->
        val at = frame.metadata.recordedAt
        val inside = frame.key != anchor && at >= window.startAt && at < window.endAt
        if (!inside) return@mapNotNull null
        val prepared = preparedStates[frame.key]
        if (prepared == null) {
            diagnostics += MultiscaleDiagnostic(
                code = MultiscaleDiagnosticCode.MISSING_PREPARED_STATE,
                anchorKey = anchor.raw,
                detail = "Historical state ${frame.key.raw} is not prepared",
            )
            null
        } else {
            TimedState(at, prepared)
        }
    }
    var overlapping = 0
    var incomplete = 0
    val included = mutableListOf<TimedTransition>()
    evidenceTransitions.forEach { frame ->
        val temporal = frame.evidence.temporal
        if (temporal.startAt > temporal.endAt) {
            diagnostics += MultiscaleDiagnostic(
                code = MultiscaleDiagnosticCode.INTERVAL_ENDS_BEFORE_IT_STARTS,
                anchorKey = anchor.raw,
                detail = "Interval ${frame.key.raw} ends before it starts",
            )
            return@forEach
        }
        when (
            classifyInterval(
                intervalStart = temporal.startAt,
                intervalEnd = temporal.endAt,
                windowStart = window.startAt,
                windowEnd = window.endAt,
            )
        ) {
            IntervalPlacement.OUTSIDE -> Unit
            IntervalPlacement.OVERLAPPING -> overlapping += 1
            IntervalPlacement.CONTAINED -> {
                if (!temporal.completed) {
                    incomplete += 1
                } else {
                    val prepared = preparedTransitions[frame.key]
                    if (prepared == null) {
                        diagnostics += MultiscaleDiagnostic(
                            code = MultiscaleDiagnosticCode.MISSING_PREPARED_TRANSITION,
                            anchorKey = anchor.raw,
                            detail = "Contained interval ${frame.key.raw} is not prepared",
                        )
                    } else {
                        included += TimedTransition(temporal.startAt, temporal.endAt, temporal.durationMillis, prepared)
                    }
                }
            }
        }
    }
    val stateSummary = summarizeStates(
        history = history,
        overlapping = overlapping,
        incomplete = incomplete,
        included = included,
        features = model.state.substantive.features,
    )
    val transitionSummary = summarizeTransitions(
        included = included,
        overlapping = overlapping,
        incomplete = incomplete,
        features = model.transition.substantive.features,
    )
    return TrailingDescriptor(
        scale = scale,
        windowStartAt = window.startAt,
        windowEndAt = window.endAt,
        stateHistory = stateSummary,
        transitionHistory = transitionSummary,
    )
}

private data class TimedState(val recordedAt: Long, val prepared: PreparedState)

private data class TimedTransition(
    val startAt: Long,
    val endAt: Long,
    val durationMillis: Long,
    val prepared: PreparedTransition,
)

private fun summarizeStates(
    history: List<TimedState>,
    overlapping: Int,
    incomplete: Int,
    included: List<TimedTransition>,
    features: List<FeatureScale>,
): StateHistorySummary {
    val coverage = TemporalCoverage(
        historicalStateCount = history.size,
        containedTransitionCount = included.size,
        overlappingTransitionCount = overlapping,
        incompleteTransitionCount = incomplete,
        earliestStateAt = history.minOfOrNull { it.recordedAt },
        latestStateAt = history.maxOfOrNull { it.recordedAt },
        earliestIntervalStartAt = included.minOfOrNull { it.startAt },
        latestIntervalEndAt = included.maxOfOrNull { it.endAt },
        representedDurationMillis = included.sumOf { it.durationMillis },
    )
    if (history.isEmpty()) {
        return StateHistorySummary(
            available = false,
            coverage = coverage,
            dimensions = features.map { emptyStateDimension(it) },
            geometry = unavailable(features.size),
        )
    }
    val dimensions = features.map { feature ->
        val samples = history.mapNotNull { row ->
            val vector = row.prepared.channel.substantive
            if (!vector.observed[feature.index]) {
                null
            } else {
                Sample(row.recordedAt, vector.values[feature.index])
            }
        }
        stateDimension(feature, samples)
    }
    return StateHistorySummary(
        available = true,
        coverage = coverage,
        dimensions = dimensions,
        geometry = MaskedVector(
            values = dimensions.map { if (it.available) it.mean else 0.0 },
            observed = dimensions.map { it.available },
        ),
    )
}

private data class Sample(val at: Long, val value: Double)

private fun stateDimension(feature: FeatureScale, samples: List<Sample>): StateDimensionSummary {
    if (samples.isEmpty()) {
        return emptyStateDimension(feature)
    }
    val values = samples.map { it.value }
    val mean = values.sum() / values.size
    return StateDimensionSummary(
        index = feature.index,
        semantic = feature.sourceSemantic,
        observedCount = values.size,
        mean = mean,
        deviation = populationDeviation(values, mean),
        minimum = values.min(),
        maximum = values.max(),
        trend = trend(feature, samples),
        available = true,
    )
}

private fun emptyStateDimension(feature: FeatureScale) = StateDimensionSummary(
    index = feature.index,
    semantic = feature.sourceSemantic,
    observedCount = 0,
    mean = 0.0,
    deviation = 0.0,
    minimum = 0.0,
    maximum = 0.0,
    trend = if (feature.sourceSemantic is FeatureSemantic.AxisValue) {
        TrendStatus.Unavailable
    } else {
        TrendStatus.NotApplicable
    },
    available = false,
)

private fun trend(feature: FeatureScale, samples: List<Sample>): TrendStatus {
    if (feature.sourceSemantic !is FeatureSemantic.AxisValue) return TrendStatus.NotApplicable
    if (samples.size < 2 || samples.map { it.at }.distinct().size < 2) return TrendStatus.Unavailable
    val origin = samples.first().at
    val xs = samples.map { (it.at - origin) / MILLIS_PER_HOUR }
    val ys = samples.map { it.value }
    val xMean = xs.sum() / xs.size
    val yMean = ys.sum() / ys.size
    var numerator = 0.0
    var denominator = 0.0
    for (index in xs.indices) {
        val dx = xs[index] - xMean
        numerator += dx * (ys[index] - yMean)
        denominator += dx * dx
    }
    if (denominator == 0.0) return TrendStatus.Unavailable
    val slope = numerator / denominator
    return if (slope.isFinite()) TrendStatus.Slope(slope) else TrendStatus.Unavailable
}

private fun summarizeTransitions(
    included: List<TimedTransition>,
    overlapping: Int,
    incomplete: Int,
    features: List<FeatureScale>,
): TransitionHistorySummary {
    val duration = included.sumOf { it.durationMillis }
    if (included.isEmpty()) {
        return TransitionHistorySummary(
            available = false,
            includedTransitionCount = 0,
            overlappingTransitionCount = overlapping,
            incompleteTransitionCount = incomplete,
            representedDurationMillis = 0L,
            dimensions = features.map { emptyTransitionDimension(it) },
            geometry = unavailable(features.size),
        )
    }
    val dimensions = features.map { feature -> transitionDimension(feature, included) }
    return TransitionHistorySummary(
        available = true,
        includedTransitionCount = included.size,
        overlappingTransitionCount = overlapping,
        incompleteTransitionCount = incomplete,
        representedDurationMillis = duration,
        dimensions = dimensions,
        geometry = MaskedVector(
            values = dimensions.map { if (it.available) it.mean else 0.0 },
            observed = dimensions.map { it.available },
        ),
    )
}

private fun transitionDimension(
    feature: FeatureScale,
    included: List<TimedTransition>,
): TransitionDimensionSummary {
    val values = included.mapNotNull { row ->
        val vector = row.prepared.channel.substantive
        if (!vector.observed[feature.index]) null else vector.values[feature.index]
    }
    if (values.isEmpty()) return emptyTransitionDimension(feature)
    val mean = values.sum() / values.size
    val prevalence = if (feature.scaling == FeatureScaling.BINARY) {
        values.count { presenceBit(feature, it) }.toDouble() / values.size
    } else {
        null
    }
    return TransitionDimensionSummary(
        index = feature.index,
        semantic = feature.sourceSemantic,
        observedCount = values.size,
        mean = mean,
        deviation = populationDeviation(values, mean),
        intervalPrevalence = prevalence,
        available = true,
    )
}

private fun emptyTransitionDimension(feature: FeatureScale) = TransitionDimensionSummary(
    index = feature.index,
    semantic = feature.sourceSemantic,
    observedCount = 0,
    mean = 0.0,
    deviation = 0.0,
    intervalPrevalence = null,
    available = false,
)

/**
 * Recovers the 0/1 bit from a block-balanced presence coordinate.
 * A nonvarying bit keeps its fitted center, which is that constant bit.
 */
private fun presenceBit(feature: FeatureScale, balanced: Double): Boolean {
    val bit = if (feature.nonvarying) {
        feature.center
    } else if (feature.blockFactor == 0.0) {
        0.0
    } else {
        balanced / feature.blockFactor
    }
    return bit >= 0.5
}

private fun populationDeviation(values: List<Double>, mean: Double): Double {
    if (values.isEmpty()) return 0.0
    return sqrt(values.sumOf { value ->
        val delta = value - mean
        delta * delta
    } / values.size)
}

private fun deviation(
    name: String,
    current: MaskedVector,
    history: StateHistorySummary,
): DeviationGroup {
    val values = ArrayList<Double>(current.values.size)
    val observed = ArrayList<Boolean>(current.values.size)
    val support = ArrayList<Int>(current.values.size)
        current.values.forEachIndexed { index, value ->
        val historical = history.dimensions.getOrNull(index)
        if (current.observed[index] && historical != null && historical.available) {
            values += value - historical.mean
            observed += true
            support += historical.observedCount
        } else {
            values += 0.0
            observed += false
            support += 0
        }
    }
    return DeviationGroup(name, MaskedVector(values, observed), support)
}

private fun historyDeviation(day: StateHistorySummary, week: StateHistorySummary): DeviationGroup {
    val width = day.geometry.values.size
    val values = ArrayList<Double>(width)
    val observed = ArrayList<Boolean>(width)
    val support = ArrayList<Int>(width)
    for (index in 0 until width) {
        val left = day.dimensions[index]
        val right = week.dimensions[index]
        if (left.available && right.available) {
            values += left.mean - right.mean
            observed += true
            support += minOf(left.observedCount, right.observedCount)
        } else {
            values += 0.0
            observed += false
            support += 0
        }
    }
    return DeviationGroup("deviation.day_minus_week", MaskedVector(values, observed), support)
}

private fun pooled(segment: PooledSegment, width: Int): MaskedVector {
    require(segment.values.size == width) { "Pooled segment width does not match its layout" }
    return MaskedVector(segment.values, segment.observed)
}

private fun unavailable(width: Int) = MaskedVector(List(width) { 0.0 }, List(width) { false })

private fun multiscaleSchema(model: FeaturePreparationModel): MultiscaleSchema {
    val state = model.state.substantive.features
    val transition = model.transition.substantive.features
    val stateBlocks = model.state.blocks
    val transitionBlocks = model.transition.blocks
    fun stateSegment(name: String) = segment(name, state, stateBlocks)
    fun transitionSegment(name: String) = segment(name, transition, transitionBlocks)
    val retrospective = listOf(
        stateSegment("local.self"),
        transitionSegment("local.incoming.transition"),
        stateSegment("local.incoming.opposite_state"),
        transitionSegment("local.outgoing.transition"),
        stateSegment("local.outgoing.opposite_state"),
        stateSegment("trailing.day.state"),
        transitionSegment("trailing.day.transition"),
        stateSegment("trailing.week.state"),
        transitionSegment("trailing.week.transition"),
        stateSegment("deviation.current_minus_day"),
        stateSegment("deviation.current_minus_week"),
        stateSegment("deviation.day_minus_week"),
    )
    val causal = listOf(
        stateSegment("local.self"),
        stateSegment("trailing.day.state"),
        transitionSegment("trailing.day.transition"),
        stateSegment("trailing.week.state"),
        transitionSegment("trailing.week.transition"),
        stateSegment("deviation.current_minus_day"),
        stateSegment("deviation.current_minus_week"),
        stateSegment("deviation.day_minus_week"),
    )
    return MultiscaleSchema(retrospective, causal)
}

private fun segment(
    name: String,
    features: List<FeatureScale>,
    blocks: List<BlockBalance>,
): MultiscaleSegment {
    val effectiveBlocks = blocks.count { it.effectiveDimensionCount > 0 }
    val factor = if (effectiveBlocks == 0) {
        0.0
    } else {
        MULTISCALE_SEGMENT_WEIGHT / sqrt(effectiveBlocks.toDouble())
    }
    return MultiscaleSegment(
        name = name,
        weight = MULTISCALE_SEGMENT_WEIGHT,
        effectiveBlockCount = effectiveBlocks,
        factor = factor,
        features = features.mapIndexed { index, feature -> feature.copy(index = index) },
    )
}

private fun flattenSegments(
    segments: List<MultiscaleSegment>,
    source: (MultiscaleSegment) -> MaskedVector,
): FlatMultiscaleVector {
    val values = ArrayList<Double>()
    val observed = ArrayList<Boolean>()
    segments.forEach { segment ->
        val vector = source(segment)
        require(vector.values.size == segment.features.size) {
            "${segment.name} does not match its layout"
        }
        vector.values.forEachIndexed { index, value ->
            val seen = vector.observed[index]
            val scaled = if (seen) value * segment.factor else 0.0
            require(scaled.isFinite()) { "${segment.name} did not stay finite" }
            values += scaled
            observed += seen
        }
    }
    return FlatMultiscaleVector(values, observed)
}
