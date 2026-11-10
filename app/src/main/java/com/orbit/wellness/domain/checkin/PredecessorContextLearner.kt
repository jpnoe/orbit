package com.orbit.wellness.domain.checkin

import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Candidate forecast beside the locked null trajectory learner.
 *
 * Protocol [PREDECESSOR_CONTEXT_PROTOCOL]. The gate is the locked
 * [NULL_TRAJECTORY_GATE] columns, in that order, followed by
 * [CAUSAL_PREDECESSOR_FEATURE_VIEW_PROTOCOL]. The null learner is called for
 * its own artifact and is not modified. A rejected predecessor view, or a
 * rejected candidate fit, copies the null forecast and names that fallback.
 *
 * This is a state forecast conditioned on the anchor's past. It does not
 * estimate a behavior effect and it is not for recommendation. Sampled
 * backgrounds, raw identities, quantities, deeper hops, and successor context
 * are not inputs.
 */
const val PREDECESSOR_CONTEXT_PROTOCOL = "predecessor-context-learner-v1"

const val PREDECESSOR_CONTEXT_GATE = "predecessor-context-gate-v1"

const val PREDECESSOR_CONTEXT_SHRINKAGE_LAMBDA = NULL_TRAJECTORY_SHRINKAGE_LAMBDA

const val PREDECESSOR_CONTEXT_ASSUMPTIONS =
    "Candidate beside null-trajectory-learner-v2, not a replacement. One model per core axis. " +
        "The fitted target is the ending value minus the starting value. The gate is " +
        "predecessor-context-gate-v1: the null-trajectory-gate-v2 columns in their existing order, " +
        "then the fourteen causal-predecessor-feature-view-v1 channels. The feature view is the " +
        "anchor state's predecessor neighborhood only. Held-out outgoing behavior, a later transition, " +
        "and a sibling that is not the resolved incoming edge are not inputs. " +
        "Reviewed absence is entries 0 and unknown 0. Reviewed entries is entries 1 and unknown 0. " +
        "Unknown knowledge is entries 0 and unknown 1. An immediate interval the ordered path did not " +
        "accept is unavailable. Its numeric channels are missing and are standardized to zero beside " +
        "that unavailable bit. That encoding is not reviewed absence and it is not an exposure count " +
        "of zero. Zero hours from a boundary that ends at the focal state stays observed. " +
        "Sampled backgrounds, raw behavior identities, names, quantities, episode counts, deeper-hop " +
        "summaries, and successor context are not read. " +
        "Membership, ordering, standardization, the nonvarying-column rule, and the shared precision " +
        "of 100 match the locked null learner. That precision is not tuned. The official scored " +
        "residual is the actual ending value minus the bounded 0–10 forecast. A Student-t predictive " +
        "scale uses the supervised count. Overlapping transitions are not independent, so the scale " +
        "is not a probability. If the predecessor view or the candidate fit is rejected, the forecast " +
        "is the locked null forecast and the fallback is named null-v2. Strict as-known availability " +
        "is false."

private const val CONTEXT_MINIMUM_ROWS = 2
private const val PAIRED_ERROR_TOLERANCE = 1e-9

enum class PredecessorContextColumn {
    INTERCEPT,
    RECENT_MEAN_CORRECTION,
    RECENT_MEAN_MISSING,
    INCOMING_DELTA,
    INCOMING_DELTA_MISSING,
    HORIZON_HOURS,
    HORIZON_END_HOUR_SIN,
    HORIZON_END_HOUR_COS,
    IMMEDIATE_DURATION_HOURS,
    IMMEDIATE_INTERVAL_UNAVAILABLE,
    IMMEDIATE_EXPOSURE_COUNT,
    ACTIVITY_REVIEWED_ENTRIES,
    ACTIVITY_UNKNOWN,
    WORKOUT_REVIEWED_ENTRIES,
    WORKOUT_UNKNOWN,
    DIET_REVIEWED_ENTRIES,
    DIET_UNKNOWN,
    USAGE_REVIEWED_ENTRIES,
    USAGE_UNKNOWN,
    RESOLVED_PREDECESSOR_COUNT,
    HOURS_SINCE_BOUNDARY,
    BOUNDARY_UNAVAILABLE,
    ;

    val binary: Boolean
        get() = when (this) {
            RECENT_MEAN_MISSING,
            INCOMING_DELTA_MISSING,
            IMMEDIATE_INTERVAL_UNAVAILABLE,
            ACTIVITY_REVIEWED_ENTRIES,
            ACTIVITY_UNKNOWN,
            WORKOUT_REVIEWED_ENTRIES,
            WORKOUT_UNKNOWN,
            DIET_REVIEWED_ENTRIES,
            DIET_UNKNOWN,
            USAGE_REVIEWED_ENTRIES,
            USAGE_UNKNOWN,
            BOUNDARY_UNAVAILABLE,
            -> true
            else -> false
        }

    val numeric: Boolean
        get() = this != INTERCEPT && !binary

    fun label(): String = when (this) {
        INTERCEPT -> "intercept"
        RECENT_MEAN_CORRECTION -> "recent-mean correction"
        RECENT_MEAN_MISSING -> "recent-mean missing"
        INCOMING_DELTA -> "incoming delta"
        INCOMING_DELTA_MISSING -> "incoming delta missing"
        HORIZON_HOURS -> "horizon hours"
        HORIZON_END_HOUR_SIN -> "horizon-end hour sine"
        HORIZON_END_HOUR_COS -> "horizon-end hour cosine"
        IMMEDIATE_DURATION_HOURS -> "immediate duration hours"
        IMMEDIATE_INTERVAL_UNAVAILABLE -> "immediate interval unavailable"
        IMMEDIATE_EXPOSURE_COUNT -> "immediate exposure count"
        ACTIVITY_REVIEWED_ENTRIES -> "activity reviewed entries"
        ACTIVITY_UNKNOWN -> "activity unknown"
        WORKOUT_REVIEWED_ENTRIES -> "workout reviewed entries"
        WORKOUT_UNKNOWN -> "workout unknown"
        DIET_REVIEWED_ENTRIES -> "diet reviewed entries"
        DIET_UNKNOWN -> "diet unknown"
        USAGE_REVIEWED_ENTRIES -> "usage reviewed entries"
        USAGE_UNKNOWN -> "usage unknown"
        RESOLVED_PREDECESSOR_COUNT -> "resolved predecessor count"
        HOURS_SINCE_BOUNDARY -> "hours since boundary"
        BOUNDARY_UNAVAILABLE -> "boundary unavailable"
    }
}

enum class PredecessorComparisonModel {
    PREDECESSOR_CONTEXT,
    NULL_TRAJECTORY,
    PERSISTENCE,
    RECENT_MEAN,
}

enum class PredecessorForecastFallback {
    NO_FORECAST_ORIGIN,
    NO_PRIOR_INPUT,
    INSUFFICIENT_SUPPORT,
    START_UNOBSERVED,
    NONFINITE_INPUT,
    SINGULAR_OR_NONFINITE,
    HELD_EXAMPLE_INELIGIBLE,
    NEGATIVE_RECORDED_HORIZON,
    NULL_V2,
}

enum class PredecessorNullFallbackCause {
    VIEW_UNAVAILABLE,
    FIT_REJECTED,
}

data class PredecessorGateParameter(
    val column: PredecessorContextColumn,
    val center: Double,
    val scale: Double,
    val nonvarying: Boolean,
    val coefficient: Double,
    val priorPrecision: Double = PREDECESSOR_CONTEXT_SHRINKAGE_LAMBDA,
) {
    init {
        require(center.isFinite() && scale.isFinite() && coefficient.isFinite())
        require(scale > 0.0)
        require(priorPrecision == PREDECESSOR_CONTEXT_SHRINKAGE_LAMBDA && priorPrecision > 0.0)
        if (column == PredecessorContextColumn.INTERCEPT) {
            require(!nonvarying && center == 0.0 && scale == 1.0)
        }
        if (column.binary && nonvarying) require(coefficient == 0.0)
    }
}

data class PredecessorAxisForecast(
    val axisId: String,
    val fallback: PredecessorForecastFallback?,
    val nullFallbackCause: PredecessorNullFallbackCause?,
    val observedTargetCount: Int,
    val supervisedCount: Int,
    val trainingIds: List<String>,
    val parameters: List<PredecessorGateParameter>?,
    val startingRaw: Int?,
    val expectedResidual: Double?,
    val forecast: ObservableForecast?,
    val officialResidual: Double?,
    val rawResidual: Double?,
    val predictiveScale: Double?,
    val predictiveDegreesOfFreedom: Int?,
    val trainingResidualSpread: Double?,
) {
    init {
        require(observedTargetCount >= supervisedCount)
        require(supervisedCount == trainingIds.size)
        require(trainingIds == trainingIds.distinct().sorted())
        require((nullFallbackCause == null) == (fallback != PredecessorForecastFallback.NULL_V2))
        if (fallback == null) require(parameters != null && forecast != null)
        if (fallback == PredecessorForecastFallback.NULL_V2) {
            require(parameters == null && forecast != null)
        }
        if (
            fallback == PredecessorForecastFallback.HELD_EXAMPLE_INELIGIBLE ||
            fallback == PredecessorForecastFallback.NEGATIVE_RECORDED_HORIZON
        ) {
            require(forecast == null && officialResidual == null && rawResidual == null && parameters == null)
        }
        if (parameters != null) {
            require(parameters.map { it.column } == PredecessorContextColumn.entries)
        }
        require((expectedResidual == null) == (forecast == null))
        require(predictiveScale == null || (predictiveScale >= 0.0 && predictiveScale.isFinite()))
        require(trainingResidualSpread == null || trainingResidualSpread >= 0.0)
    }
}

data class PredecessorContextFold(
    val identity: ExampleIdentity,
    val featureView: PredecessorFeatureView?,
    val viewRejected: Boolean,
    val retrospectiveHorizon: Boolean,
    val provenance: ScenarioProvenance,
    val horizonHours: Double?,
    val gaps: List<CausalIneligibilityReason>,
    val axes: List<PredecessorAxisForecast>,
) {
    init {
        require(provenance == ScenarioProvenance.RECORDED_REALIZED)
        require(retrospectiveHorizon)
        require(gaps == gaps.distinct())
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(horizonHours == null || horizonHours.isFinite())
        require(viewRejected == (identity.anchorKey != null && featureView == null))
        if (featureView != null) require(featureView.focalKey == identity.anchorKey)
    }
}

data class PredecessorSupportModel(
    val model: PredecessorComparisonModel,
    val bias: Double?,
    val mae: Double?,
    val rmse: Double?,
) {
    init {
        require(bias == null || bias.isFinite())
        require(mae == null || mae >= 0.0)
        require(rmse == null || rmse >= 0.0)
    }
}

data class PredecessorNullContrast(
    val maeDifference: Double?,
    val rmseDifference: Double?,
    val wins: Int,
    val ties: Int,
    val losses: Int,
) {
    init {
        require(wins >= 0 && ties >= 0 && losses >= 0)
        require(maeDifference == null || maeDifference.isFinite())
        require(rmseDifference == null || rmseDifference.isFinite())
    }
}

data class PredecessorCommonSupport(
    val axisId: String,
    val count: Int,
    val learnedForecastCount: Int,
    val nullFallbackCount: Int,
    val persistenceFallbackCount: Int,
    val models: List<PredecessorSupportModel>,
    val nullContrast: PredecessorNullContrast,
) {
    init {
        require(count >= 0)
        require(learnedForecastCount >= 0 && nullFallbackCount >= 0 && persistenceFallbackCount >= 0)
        require(models.map { it.model } == PredecessorComparisonModel.entries)
        require(nullContrast.wins + nullContrast.ties + nullContrast.losses == count)
        models.forEach { model -> require((model.mae == null) == (count == 0)) }
        require((nullContrast.maeDifference == null) == (count == 0))
    }
}

data class PredecessorContextEvaluation(
    val protocol: String = PREDECESSOR_CONTEXT_PROTOCOL,
    val gate: String = PREDECESSOR_CONTEXT_GATE,
    val nullProtocol: String = NULL_TRAJECTORY_PROTOCOL,
    val nullGate: String = NULL_TRAJECTORY_GATE,
    val featureViewProtocol: String = CAUSAL_PREDECESSOR_FEATURE_VIEW_PROTOCOL,
    val shrinkageLambda: Double = PREDECESSOR_CONTEXT_SHRINKAGE_LAMBDA,
    val cutoff: CausalCutoff = CausalCutoff.ENDING_BEFORE_ORIGIN_OR_EXACT_ANCHOR,
    val availabilityMode: ObservationAvailabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
    val strictAsKnownAvailability: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val updatesModel: Boolean = false,
    val estimatesBehaviorEffect: Boolean = false,
    val assumptions: String = PREDECESSOR_CONTEXT_ASSUMPTIONS,
    val folds: List<PredecessorContextFold>,
    val commonSupport: List<PredecessorCommonSupport>,
) {
    init {
        require(protocol == PREDECESSOR_CONTEXT_PROTOCOL)
        require(gate == PREDECESSOR_CONTEXT_GATE)
        require(nullProtocol == NULL_TRAJECTORY_PROTOCOL)
        require(nullGate == NULL_TRAJECTORY_GATE)
        require(featureViewProtocol == CAUSAL_PREDECESSOR_FEATURE_VIEW_PROTOCOL)
        require(shrinkageLambda == NULL_TRAJECTORY_SHRINKAGE_LAMBDA && shrinkageLambda == 100.0)
        require(assumptions == PREDECESSOR_CONTEXT_ASSUMPTIONS)
        require(cutoff == CausalCutoff.ENDING_BEFORE_ORIGIN_OR_EXACT_ANCHOR)
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION)
        require(!strictAsKnownAvailability && !suitableForRecommendation && !updatesModel && !estimatesBehaviorEffect)
        val order = folds.map { it.identity.endingAt to it.identity.transitionId }
        require(order == order.sortedWith(compareBy({ it.first }, { it.second })))
        require(commonSupport.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
    }
}

fun projectPredecessorContextLearner(
    stateExamples: List<StateForecastExample>,
    conditionalExamples: List<ConditionalTransitionExample>,
    neighborhoods: CausalPredecessorNeighborhoodDataset,
    zone: ZoneId,
): PredecessorContextEvaluation {
    val nullEvaluation = projectNullTrajectoryLearner(stateExamples, conditionalExamples, zone)
    val state = stateExamples.sortedWith(compareBy({ it.identity.endingAt }, { it.identity.transitionId }))
    val conditional = conditionalExamples.sortedWith(compareBy({ it.identity.endingAt }, { it.identity.transitionId }))
    require(state.map { it.identity } == conditional.map { it.identity }) {
        "Predecessor folds share the state and conditional identities"
    }
    require(nullEvaluation.folds.map { it.identity } == state.map { it.identity })
    val views = featureViews(neighborhoods)
    val pairs = state.zip(conditional).map { (stateExample, conditionalExample) ->
        require(stateExample.identity == conditionalExample.identity)
        require(stateExample.input == conditionalExample.input)
        require(stateExample.target == conditionalExample.target)
        require(stateExample.targetConflicts == conditionalExample.targetConflicts)
        require(stateExample.gaps == conditionalExample.gaps)
        require(conditionalExample.scenario.provenance == ScenarioProvenance.RECORDED_REALIZED)
        ContextExample(
            stateExample = stateExample,
            conditionalExample = conditionalExample,
            nullInput = stateExample.input?.let { projectNullTrajectoryInput(it, conditionalExample.scenario, zone) },
            featureView = views(stateExample.identity.anchorKey),
        )
    }
    val folds = pairs.zip(nullEvaluation.folds).map { (held, nullFold) -> fold(held, pairs, nullFold) }
    return PredecessorContextEvaluation(
        folds = folds,
        commonSupport = commonSupport(folds, pairs, nullEvaluation),
    )
}

private data class ContextExample(
    val stateExample: StateForecastExample,
    val conditionalExample: ConditionalTransitionExample,
    val nullInput: NullTrajectoryInput?,
    val featureView: PredecessorFeatureView?,
)

private data class ContextRow(
    val id: String,
    val nullGate: NullGateObservation,
    val featureView: PredecessorFeatureView,
    val residual: Double,
)

private data class ContextFit(
    val parameters: List<PredecessorGateParameter>,
    val coefficients: DoubleArray,
    val normal: Array<DoubleArray>,
    val trainingResidualSpread: Double?,
    val sse: Double,
    val count: Int,
)

private data class ContextScale(val center: Double, val scale: Double, val nonvarying: Boolean)

private fun featureViews(
    neighborhoods: CausalPredecessorNeighborhoodDataset,
): (StateOccurrenceKey?) -> PredecessorFeatureView? {
    val keys = neighborhoods.neighborhoods.map { it.focal.stateKey }
    if (keys.size != keys.distinct().size) return { null }
    val byKey = neighborhoods.neighborhoods.associateBy { it.focal.stateKey }
    return { anchor -> anchor?.let { byKey[it] }?.let(::projectPredecessorFeatureView) }
}

private fun fold(
    held: ContextExample,
    pairs: List<ContextExample>,
    nullFold: NullTrajectoryFold,
): PredecessorContextFold {
    val identity = held.stateExample.identity
    val gaps = held.stateExample.gaps
    val negativeHorizon = held.conditionalExample.scenario.span.millis < 0
    val axes = when {
        gaps.isNotEmpty() -> CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            unscored(axisId, PredecessorForecastFallback.HELD_EXAMPLE_INELIGIBLE, held.nullInput?.axis(axisId)?.startingRaw)
        }
        negativeHorizon -> CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            unscored(axisId, PredecessorForecastFallback.NEGATIVE_RECORDED_HORIZON, held.nullInput?.axis(axisId)?.startingRaw)
        }
        else -> {
            val priors = if (identity.anchorAt == null) emptyList() else pairs.filter { priorQualifies(it, identity) }
            CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
                axisForecast(axisId, held, priors, nullFold.axes.single { it.axisId == axisId })
            }
        }
    }
    return PredecessorContextFold(
        identity = identity,
        featureView = held.featureView,
        viewRejected = identity.anchorKey != null && held.featureView == null,
        retrospectiveHorizon = true,
        provenance = ScenarioProvenance.RECORDED_REALIZED,
        horizonHours = held.nullInput?.horizonHours(),
        gaps = gaps,
        axes = axes,
    )
}

private fun priorQualifies(prior: ContextExample, held: ExampleIdentity): Boolean {
    if (!prior.stateExample.available || !prior.conditionalExample.available) return false
    if (prior.stateExample.gaps.isNotEmpty() || prior.conditionalExample.gaps.isNotEmpty()) return false
    if (prior.conditionalExample.scenario.span.millis < 0) return false
    return causalPriorQualifies(prior.stateExample, held)
}

private fun axisForecast(
    axisId: String,
    held: ContextExample,
    priors: List<ContextExample>,
    nullAxis: NullAxisForecast,
): PredecessorAxisForecast {
    val input = held.nullInput
    val starting = input?.axis(axisId)?.startingRaw
    val actual = heldTarget(held, axisId)
    val observedTargets = priors.filter { targetStatus(it, axisId) == CausalTargetStatus.OBSERVED }
    val supervised = observedTargets.mapNotNull { supervisedRow(it, axisId) }.sortedBy { it.id }
    val trainingIds = supervised.map { it.id }
    if (anchorMissing(held)) {
        return plain(axisId, PredecessorForecastFallback.NO_FORECAST_ORIGIN, observedTargets.size, trainingIds, starting, actual)
    }
    if (held.featureView == null) {
        return nullOrPlain(axisId, nullAxis, PredecessorNullFallbackCause.VIEW_UNAVAILABLE, observedTargets.size, starting, actual)
    }
    if (supervised.size < CONTEXT_MINIMUM_ROWS) {
        return nullOrPlain(axisId, nullAxis, PredecessorNullFallbackCause.FIT_REJECTED, observedTargets.size, starting, actual)
    }
    if (supervised.any { !rowIsFinite(it) }) {
        return nullOrPlain(axisId, nullAxis, PredecessorNullFallbackCause.FIT_REJECTED, observedTargets.size, starting, actual)
    }
    val fit = fitContextRidge(supervised) ?: return nullOrPlain(
        axisId, nullAxis, PredecessorNullFallbackCause.FIT_REJECTED, observedTargets.size, starting, actual,
    )
    if (starting == null) {
        return PredecessorAxisForecast(
            axisId = axisId,
            fallback = PredecessorForecastFallback.START_UNOBSERVED,
            nullFallbackCause = null,
            observedTargetCount = observedTargets.size,
            supervisedCount = trainingIds.size,
            trainingIds = trainingIds,
            parameters = fit.parameters,
            startingRaw = null,
            expectedResidual = null,
            forecast = null,
            officialResidual = null,
            rawResidual = null,
            predictiveScale = null,
            predictiveDegreesOfFreedom = supervised.size,
            trainingResidualSpread = fit.trainingResidualSpread,
        )
    }
    val expected = predictResidual(checkNotNull(input), checkNotNull(held.featureView), axisId, fit)
        ?: return nullOrPlain(axisId, nullAxis, PredecessorNullFallbackCause.FIT_REJECTED, observedTargets.size, starting, actual)
    val raw = starting.toDouble() + expected
    if (!raw.isFinite()) {
        return nullOrPlain(axisId, nullAxis, PredecessorNullFallbackCause.FIT_REJECTED, observedTargets.size, starting, actual)
    }
    val forecast = observableForecast(raw)
    return PredecessorAxisForecast(
        axisId = axisId,
        fallback = null,
        nullFallbackCause = null,
        observedTargetCount = observedTargets.size,
        supervisedCount = trainingIds.size,
        trainingIds = trainingIds,
        parameters = fit.parameters,
        startingRaw = starting,
        expectedResidual = expected,
        forecast = forecast,
        officialResidual = actual?.let { it - forecast.boundedForecast },
        rawResidual = actual?.let { it - forecast.rawExtrapolation },
        predictiveScale = predictiveScale(checkNotNull(input), checkNotNull(held.featureView), axisId, fit),
        predictiveDegreesOfFreedom = supervised.size,
        trainingResidualSpread = fit.trainingResidualSpread,
    )
}

private fun nullOrPlain(
    axisId: String,
    nullAxis: NullAxisForecast,
    cause: PredecessorNullFallbackCause,
    observedTargetCount: Int,
    starting: Int?,
    actual: Double?,
): PredecessorAxisForecast {
    val forecast = nullAxis.forecast ?: return plain(
        axisId,
        nullAxis.fallback?.toPredecessor() ?: PredecessorForecastFallback.NO_FORECAST_ORIGIN,
        observedTargetCount,
        emptyList(),
        starting,
        actual,
    )
    return PredecessorAxisForecast(
        axisId = axisId,
        fallback = PredecessorForecastFallback.NULL_V2,
        nullFallbackCause = cause,
        observedTargetCount = observedTargetCount,
        supervisedCount = 0,
        trainingIds = emptyList(),
        parameters = null,
        startingRaw = starting,
        expectedResidual = nullAxis.expectedResidual,
        forecast = forecast,
        officialResidual = actual?.let { it - forecast.boundedForecast },
        rawResidual = actual?.let { it - forecast.rawExtrapolation },
        predictiveScale = null,
        predictiveDegreesOfFreedom = null,
        trainingResidualSpread = null,
    )
}

private fun plain(
    axisId: String,
    fallback: PredecessorForecastFallback,
    observedTargetCount: Int,
    trainingIds: List<String>,
    starting: Int?,
    actual: Double?,
): PredecessorAxisForecast {
    return PredecessorAxisForecast(
        axisId = axisId,
        fallback = fallback,
        nullFallbackCause = null,
        observedTargetCount = observedTargetCount,
        supervisedCount = trainingIds.size,
        trainingIds = trainingIds,
        parameters = null,
        startingRaw = starting,
        expectedResidual = null,
        forecast = null,
        officialResidual = null,
        rawResidual = null,
        predictiveScale = null,
        predictiveDegreesOfFreedom = null,
        trainingResidualSpread = null,
    )
}

private fun unscored(axisId: String, fallback: PredecessorForecastFallback, starting: Int?): PredecessorAxisForecast {
    return plain(axisId, fallback, 0, emptyList(), starting, null)
}

private fun NullForecastFallback.toPredecessor(): PredecessorForecastFallback = when (this) {
    NullForecastFallback.NO_FORECAST_ORIGIN -> PredecessorForecastFallback.NO_FORECAST_ORIGIN
    NullForecastFallback.NO_PRIOR_INPUT -> PredecessorForecastFallback.NO_PRIOR_INPUT
    NullForecastFallback.INSUFFICIENT_SUPPORT -> PredecessorForecastFallback.INSUFFICIENT_SUPPORT
    NullForecastFallback.START_UNOBSERVED -> PredecessorForecastFallback.START_UNOBSERVED
    NullForecastFallback.NONFINITE_INPUT -> PredecessorForecastFallback.NONFINITE_INPUT
    NullForecastFallback.SINGULAR_OR_NONFINITE -> PredecessorForecastFallback.SINGULAR_OR_NONFINITE
    NullForecastFallback.HELD_EXAMPLE_INELIGIBLE -> PredecessorForecastFallback.HELD_EXAMPLE_INELIGIBLE
    NullForecastFallback.NEGATIVE_RECORDED_HORIZON -> PredecessorForecastFallback.NEGATIVE_RECORDED_HORIZON
}

private fun anchorMissing(held: ContextExample): Boolean {
    return held.stateExample.identity.anchorAt == null || held.nullInput == null
}

private fun supervisedRow(prior: ContextExample, axisId: String): ContextRow? {
    val input = prior.nullInput ?: return null
    val view = prior.featureView ?: return null
    val starting = input.axis(axisId).startingRaw ?: return null
    val actual = heldTarget(prior, axisId) ?: return null
    return ContextRow(
        id = prior.stateExample.identity.transitionId,
        nullGate = nullGateObservation(input, axisId),
        featureView = view,
        residual = actual - starting,
    )
}

private fun heldTarget(held: ContextExample, axisId: String): Double? {
    val status = targetStatus(held, axisId)
    val raw = held.stateExample.target.reading(axisId).raw
    return if (status == CausalTargetStatus.OBSERVED) raw?.toDouble() else null
}

private fun targetStatus(held: ContextExample, axisId: String): CausalTargetStatus {
    return encodeCausalAxisTargets(held.stateExample.target, held.stateExample.targetConflicts)
        .axes.single { it.axisId == axisId }
        .status
}

private fun rowIsFinite(row: ContextRow): Boolean {
    if (!row.residual.isFinite()) return false
    val gate = row.nullGate
    if (gate.recentMeanCorrection?.isFinite() == false) return false
    if (gate.incomingDelta?.isFinite() == false) return false
    if (!gate.horizonHours.isFinite() || !gate.horizonEndHourSin.isFinite() || !gate.horizonEndHourCos.isFinite()) {
        return false
    }
    return row.featureView.channels.all { it.raw == null || it.raw.isFinite() }
}

private fun fitContextRidge(rows: List<ContextRow>): ContextFit? {
    val ordered = rows.indices.sortedWith(rowOrder(rows)).map { rows[it] }
    val scales = PredecessorContextColumn.entries.map { column -> columnScale(column, ordered) ?: return null }
    val design = ordered.map { row -> standardizedRow(row, scales) }
    val width = PredecessorContextColumn.entries.size
    val gram = Array(width) { DoubleArray(width) }
    val cross = DoubleArray(width)
    design.forEachIndexed { index, values ->
        val residual = ordered[index].residual
        values.forEachIndexed { left, leftValue ->
            cross[left] += leftValue * residual
            values.forEachIndexed { right, rightValue ->
                gram[left][right] += leftValue * rightValue
            }
        }
    }
    val normal = Array(width) { row ->
        DoubleArray(width) { column ->
            gram[row][column] + if (row == column) PREDECESSOR_CONTEXT_SHRINKAGE_LAMBDA else 0.0
        }
    }
    val coefficients = solveContextSystem(normal, cross) ?: return null
    val fitted = design.map { row -> dotContext(row, coefficients) }
    if (fitted.any { !it.isFinite() }) return null
    val errors = fitted.mapIndexed { index, value -> ordered[index].residual - value }
    val sse = errors.sumOf { it * it } + PREDECESSOR_CONTEXT_SHRINKAGE_LAMBDA * coefficients.sumOf { it * it }
    if (!sse.isFinite() || sse < -1e-8) return null
    val parameters = PredecessorContextColumn.entries.mapIndexed { index, column ->
        val solved = coefficients[index]
        val coefficient = if (column.binary && scales[index].nonvarying) {
            if (solved != 0.0 && abs(solved) > 1e-8) return null
            0.0
        } else {
            solved
        }
        PredecessorGateParameter(
            column = column,
            center = scales[index].center,
            scale = scales[index].scale,
            nonvarying = scales[index].nonvarying,
            coefficient = coefficient,
        )
    }
    return ContextFit(
        parameters = parameters,
        coefficients = coefficients,
        normal = normal,
        trainingResidualSpread = finiteContextPopulation(errors)?.deviation ?: return null,
        sse = maxOf(0.0, sse),
        count = ordered.size,
    )
}

private fun rowOrder(rows: List<ContextRow>): Comparator<Int> {
    return compareBy<Int> { rows[it].nullGate.recentMeanCorrection ?: Double.NEGATIVE_INFINITY }
        .thenBy { rows[it].nullGate.incomingDelta ?: Double.NEGATIVE_INFINITY }
        .thenBy { rows[it].nullGate.horizonHours }
        .thenBy { rows[it].nullGate.horizonEndHourSin }
        .thenBy { rows[it].nullGate.horizonEndHourCos }
        .thenBy { rows[it].featureView.reading(PredecessorFeatureChannel.IMMEDIATE_DURATION_HOURS) ?: Double.NEGATIVE_INFINITY }
        .thenBy { rows[it].featureView.reading(PredecessorFeatureChannel.IMMEDIATE_EXPOSURE_COUNT) ?: Double.NEGATIVE_INFINITY }
        .thenBy { rows[it].featureView.reading(PredecessorFeatureChannel.RESOLVED_PREDECESSOR_COUNT) ?: Double.NEGATIVE_INFINITY }
        .thenBy { rows[it].featureView.reading(PredecessorFeatureChannel.HOURS_SINCE_BOUNDARY) ?: Double.NEGATIVE_INFINITY }
        .thenBy { rows[it].residual }
        .thenBy { it }
}

private fun columnScale(column: PredecessorContextColumn, rows: List<ContextRow>): ContextScale? {
    if (column == PredecessorContextColumn.INTERCEPT) {
        return ContextScale(center = 0.0, scale = 1.0, nonvarying = false)
    }
    if (column.binary) {
        val values = rows.map { rawColumn(it, column) }
        if (values.any { !it.isFinite() }) return null
        return ContextScale(center = 0.0, scale = 1.0, nonvarying = values.distinct().size < 2)
    }
    val observed = rows.mapNotNull { rawColumnOrNull(it, column) }
    val summary = finiteContextPopulation(observed)
    if (observed.isNotEmpty() && summary == null) return null
    val nonvarying = summary == null || summary.count < 2 || !deviationChangesGeometry(summary.deviation, summary.mean)
    return ContextScale(
        center = summary?.mean ?: 0.0,
        scale = if (summary != null && !nonvarying) summary.deviation else 1.0,
        nonvarying = nonvarying,
    )
}

private fun standardizedRow(row: ContextRow, scales: List<ContextScale>): DoubleArray {
    return PredecessorContextColumn.entries.mapIndexed { index, column ->
        val scale = scales[index]
        if (column == PredecessorContextColumn.INTERCEPT) return@mapIndexed 1.0
        if (column.binary) return@mapIndexed if (scale.nonvarying) 0.0 else rawColumn(row, column)
        val raw = rawColumnOrNull(row, column)
        if (raw == null || scale.nonvarying) 0.0 else (raw - scale.center) / scale.scale
    }.toDoubleArray()
}

private fun rawColumn(row: ContextRow, column: PredecessorContextColumn): Double {
    return rawColumnOrNull(row, column) ?: when (column) {
        PredecessorContextColumn.RECENT_MEAN_MISSING -> if (row.nullGate.recentMeanCorrection == null) 1.0 else 0.0
        PredecessorContextColumn.INCOMING_DELTA_MISSING -> if (row.nullGate.incomingDelta == null) 1.0 else 0.0
        PredecessorContextColumn.IMMEDIATE_INTERVAL_UNAVAILABLE,
        PredecessorContextColumn.ACTIVITY_REVIEWED_ENTRIES,
        PredecessorContextColumn.ACTIVITY_UNKNOWN,
        PredecessorContextColumn.WORKOUT_REVIEWED_ENTRIES,
        PredecessorContextColumn.WORKOUT_UNKNOWN,
        PredecessorContextColumn.DIET_REVIEWED_ENTRIES,
        PredecessorContextColumn.DIET_UNKNOWN,
        PredecessorContextColumn.USAGE_REVIEWED_ENTRIES,
        PredecessorContextColumn.USAGE_UNKNOWN,
        PredecessorContextColumn.BOUNDARY_UNAVAILABLE,
        -> row.featureView.reading(column.toFeatureChannel()) ?: 0.0
        else -> 0.0
    }
}

private fun rawColumnOrNull(row: ContextRow, column: PredecessorContextColumn): Double? {
    return when (column) {
        PredecessorContextColumn.RECENT_MEAN_CORRECTION -> row.nullGate.recentMeanCorrection
        PredecessorContextColumn.INCOMING_DELTA -> row.nullGate.incomingDelta
        PredecessorContextColumn.HORIZON_HOURS -> row.nullGate.horizonHours
        PredecessorContextColumn.HORIZON_END_HOUR_SIN -> row.nullGate.horizonEndHourSin
        PredecessorContextColumn.HORIZON_END_HOUR_COS -> row.nullGate.horizonEndHourCos
        PredecessorContextColumn.IMMEDIATE_DURATION_HOURS,
        PredecessorContextColumn.IMMEDIATE_EXPOSURE_COUNT,
        PredecessorContextColumn.RESOLVED_PREDECESSOR_COUNT,
        PredecessorContextColumn.HOURS_SINCE_BOUNDARY,
        -> row.featureView.reading(column.toFeatureChannel())
        else -> null
    }
}

private fun PredecessorContextColumn.toFeatureChannel(): PredecessorFeatureChannel = when (this) {
    PredecessorContextColumn.IMMEDIATE_DURATION_HOURS -> PredecessorFeatureChannel.IMMEDIATE_DURATION_HOURS
    PredecessorContextColumn.IMMEDIATE_INTERVAL_UNAVAILABLE -> PredecessorFeatureChannel.IMMEDIATE_INTERVAL_UNAVAILABLE
    PredecessorContextColumn.IMMEDIATE_EXPOSURE_COUNT -> PredecessorFeatureChannel.IMMEDIATE_EXPOSURE_COUNT
    PredecessorContextColumn.ACTIVITY_REVIEWED_ENTRIES -> PredecessorFeatureChannel.ACTIVITY_REVIEWED_ENTRIES
    PredecessorContextColumn.ACTIVITY_UNKNOWN -> PredecessorFeatureChannel.ACTIVITY_UNKNOWN
    PredecessorContextColumn.WORKOUT_REVIEWED_ENTRIES -> PredecessorFeatureChannel.WORKOUT_REVIEWED_ENTRIES
    PredecessorContextColumn.WORKOUT_UNKNOWN -> PredecessorFeatureChannel.WORKOUT_UNKNOWN
    PredecessorContextColumn.DIET_REVIEWED_ENTRIES -> PredecessorFeatureChannel.DIET_REVIEWED_ENTRIES
    PredecessorContextColumn.DIET_UNKNOWN -> PredecessorFeatureChannel.DIET_UNKNOWN
    PredecessorContextColumn.USAGE_REVIEWED_ENTRIES -> PredecessorFeatureChannel.USAGE_REVIEWED_ENTRIES
    PredecessorContextColumn.USAGE_UNKNOWN -> PredecessorFeatureChannel.USAGE_UNKNOWN
    PredecessorContextColumn.RESOLVED_PREDECESSOR_COUNT -> PredecessorFeatureChannel.RESOLVED_PREDECESSOR_COUNT
    PredecessorContextColumn.HOURS_SINCE_BOUNDARY -> PredecessorFeatureChannel.HOURS_SINCE_BOUNDARY
    PredecessorContextColumn.BOUNDARY_UNAVAILABLE -> PredecessorFeatureChannel.BOUNDARY_UNAVAILABLE
    else -> error("A null-gate column has no predecessor channel")
}

private fun predictResidual(
    input: NullTrajectoryInput,
    view: PredecessorFeatureView,
    axisId: String,
    fit: ContextFit,
): Double? {
    val row = ContextRow("", nullGateObservation(input, axisId), view, 0.0)
    val standardized = standardizedRow(row, fit.parameters.map { ContextScale(it.center, it.scale, it.nonvarying) })
    val value = dotContext(standardized, fit.coefficients)
    return value.takeIf { it.isFinite() }
}

private fun predictiveScale(
    input: NullTrajectoryInput,
    view: PredecessorFeatureView,
    axisId: String,
    fit: ContextFit,
): Double? {
    val row = ContextRow("", nullGateObservation(input, axisId), view, 0.0)
    val standardized = standardizedRow(row, fit.parameters.map { ContextScale(it.center, it.scale, it.nonvarying) })
    val solved = solveContextSystem(fit.normal, standardized) ?: return null
    val quadratic = dotContext(standardized, solved)
    if (!quadratic.isFinite() || quadratic < -1e-8) return null
    val scale = sqrt(fit.sse / fit.count * (1.0 + maxOf(0.0, quadratic)))
    return scale.takeIf { it.isFinite() }
}

private fun solveContextSystem(matrix: Array<DoubleArray>, rightHandSide: DoubleArray): DoubleArray? {
    val size = rightHandSide.size
    val work = Array(size) { row ->
        DoubleArray(size + 1) { column ->
            if (column < size) matrix[row][column] else rightHandSide[row]
        }
    }
    if (work.any { row -> row.any { !it.isFinite() } }) return null
    for (column in 0 until size) {
        var pivot = column
        var best = abs(work[column][column])
        for (row in column + 1 until size) {
            val candidate = abs(work[row][column])
            if (candidate > best) {
                best = candidate
                pivot = row
            }
        }
        val magnitude = work.maxOf { row -> row.maxOf { abs(it) } }
        if (!best.isFinite() || best <= 1e-10 * maxOf(1.0, magnitude)) return null
        if (pivot != column) {
            val swap = work[column]
            work[column] = work[pivot]
            work[pivot] = swap
        }
        val divisor = work[column][column]
        for (index in column..size) work[column][index] /= divisor
        for (row in 0 until size) {
            if (row == column) continue
            val factor = work[row][column]
            if (factor == 0.0) continue
            for (index in column..size) work[row][index] -= factor * work[column][index]
        }
    }
    val solution = DoubleArray(size) { work[it][size] }
    return solution.takeIf { values -> values.all { it.isFinite() } }
}

private fun finiteContextPopulation(values: List<Double>): NumericSummary? {
    if (values.isEmpty() || values.any { !it.isFinite() }) return null
    val mean = values.sum() / values.size
    if (!mean.isFinite()) return null
    val squareSum = values.sumOf { value ->
        val delta = value - mean
        delta * delta
    }
    if (!squareSum.isFinite()) return null
    if (!sqrt(squareSum / values.size).isFinite()) return null
    return populationSummary(values)
}

private fun dotContext(left: DoubleArray, right: DoubleArray): Double {
    var sum = 0.0
    for (index in left.indices) sum += left[index] * right[index]
    return sum
}

private fun commonSupport(
    folds: List<PredecessorContextFold>,
    pairs: List<ContextExample>,
    nullEvaluation: NullTrajectoryEvaluation,
): List<PredecessorCommonSupport> {
    val byId = pairs.associateBy { it.stateExample.identity.transitionId }
    val nullById = nullEvaluation.folds.associateBy { it.identity.transitionId }
    return CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        val shared = folds.mapNotNull { fold ->
            val held = byId.getValue(fold.identity.transitionId)
            val actual = heldTarget(held, axisId) ?: return@mapNotNull null
            val axis = fold.axes.single { it.axisId == axisId }
            val candidate = axis.forecast ?: return@mapNotNull null
            val nullForecast = nullById.getValue(fold.identity.transitionId)
                .axes.single { it.axisId == axisId }
                .forecast ?: return@mapNotNull null
            val state = held.nullInput?.axis(axisId) ?: return@mapNotNull null
            val persistence = state.startingRaw?.let { observableForecast(it.toDouble()) } ?: return@mapNotNull null
            val recent = state.recentMean?.let { observableForecast(it) } ?: return@mapNotNull null
            listOf(
                actual - candidate.boundedForecast,
                actual - nullForecast.boundedForecast,
                actual - persistence.boundedForecast,
                actual - recent.boundedForecast,
            )
        }
        val axes = folds.map { it.axes.single { axis -> axis.axisId == axisId } }
        val candidateErrors = shared.map { it[0] }
        val nullErrors = shared.map { it[1] }
        PredecessorCommonSupport(
            axisId = axisId,
            count = shared.size,
            learnedForecastCount = axes.count { it.fallback == null && it.forecast != null },
            nullFallbackCount = axes.count { it.fallback == PredecessorForecastFallback.NULL_V2 && it.forecast != null },
            persistenceFallbackCount = axes.count { axis ->
                axis.forecast != null && axis.fallback != null && axis.expectedResidual == 0.0
            },
            models = PredecessorComparisonModel.entries.map { model ->
                val errors = shared.map { row ->
                    when (model) {
                        PredecessorComparisonModel.PREDECESSOR_CONTEXT -> row[0]
                        PredecessorComparisonModel.NULL_TRAJECTORY -> row[1]
                        PredecessorComparisonModel.PERSISTENCE -> row[2]
                        PredecessorComparisonModel.RECENT_MEAN -> row[3]
                    }
                }
                PredecessorSupportModel(model, contextMean(errors), contextMean(errors.map { abs(it) }), contextRootMeanSquare(errors))
            },
            nullContrast = contrast(candidateErrors, nullErrors),
        )
    }
}

private fun contrast(candidateErrors: List<Double>, nullErrors: List<Double>): PredecessorNullContrast {
    if (candidateErrors.isEmpty()) {
        return PredecessorNullContrast(null, null, 0, 0, 0)
    }
    var wins = 0
    var ties = 0
    var losses = 0
    candidateErrors.zip(nullErrors).forEach { (candidate, nullError) ->
        val difference = abs(candidate) - abs(nullError)
        when {
            difference < -PAIRED_ERROR_TOLERANCE -> wins += 1
            difference > PAIRED_ERROR_TOLERANCE -> losses += 1
            else -> ties += 1
        }
    }
    val candidateMae = checkNotNull(contextMean(candidateErrors.map { abs(it) }))
    val nullMae = checkNotNull(contextMean(nullErrors.map { abs(it) }))
    val candidateRmse = checkNotNull(contextRootMeanSquare(candidateErrors))
    val nullRmse = checkNotNull(contextRootMeanSquare(nullErrors))
    return PredecessorNullContrast(
        maeDifference = candidateMae - nullMae,
        rmseDifference = candidateRmse - nullRmse,
        wins = wins,
        ties = ties,
        losses = losses,
    )
}

private fun contextMean(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    return values.sum() / values.size
}

private fun contextRootMeanSquare(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    return sqrt(values.sumOf { it * it } / values.size)
}

fun predecessorContextAxisLabel(axis: PredecessorAxisForecast): String {
    val source = when (axis.fallback) {
        null -> "learned"
        PredecessorForecastFallback.NULL_V2 -> {
            val cause = if (axis.nullFallbackCause == PredecessorNullFallbackCause.VIEW_UNAVAILABLE) {
                "view unavailable"
            } else {
                "fit rejected"
            }
            "fell back to null-v2 · $cause"
        }
        else -> axis.fallback.name.lowercase().replace('_', ' ')
    }
    val endpoint = axis.forecast?.let { "bounded ${formatContextNumber(it.boundedForecast)}" } ?: "no endpoint"
    return "$source · $endpoint"
}

fun predecessorContextSupportLabel(support: PredecessorCommonSupport): String {
    val contrast = support.nullContrast
    val difference = contrast.maeDifference?.let { "candidate minus null MAE ${formatContextNumber(it)}" } ?: "candidate minus null MAE none"
    val rmse = contrast.rmseDifference?.let { "RMSE ${formatContextNumber(it)}" } ?: "RMSE none"
    return "Common support ${support.count} · learned ${support.learnedForecastCount} · " +
        "null-v2 fallbacks ${support.nullFallbackCount} · persistence fallbacks ${support.persistenceFallbackCount} · " +
        "$difference · $rmse · wins ${contrast.wins} · ties ${contrast.ties} · losses ${contrast.losses}"
}

fun predecessorContextTechnicalLabel(
    evaluation: PredecessorContextEvaluation,
    axis: PredecessorAxisForecast,
    view: PredecessorFeatureView?,
): String {
    val parameters = axis.parameters?.joinToString(" · ") { parameter ->
        val varying = if (parameter.nonvarying) " nonvarying" else ""
        "${parameter.column.label()} ${formatContextNumber(parameter.coefficient)}$varying"
    } ?: "no candidate coefficients"
    val ids = if (axis.trainingIds.isEmpty()) "none" else axis.trainingIds.joinToString(", ")
    val audit = view?.let { predecessorFeatureAuditLabel(it) } ?: "no raw audit"
    val order = PredecessorContextColumn.entries.joinToString(", ") { it.label() }
    return "${evaluation.assumptions}\n$order\ntraining $ids · $parameters\n$audit"
}

private fun formatContextNumber(value: Double): String {
    val rounded = kotlin.math.round(value * 1000.0) / 1000.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
