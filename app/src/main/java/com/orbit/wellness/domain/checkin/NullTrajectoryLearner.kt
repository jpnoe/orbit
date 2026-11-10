package com.orbit.wellness.domain.checkin

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Horizon-conditioned null forecast. Protocol [NULL_TRAJECTORY_PROTOCOL].
 *
 * The question is what ending state would be expected from the past-only
 * starting state and the supplied horizon if behavior identity were withheld.
 * Category knowledge, behavior identities, exposures, quantities, targets,
 * deltas, residuals, and baseline errors are not inputs.
 *
 * Each core axis has its own model. The fitted target is the ending value
 * minus the starting value, so a zero residual is Persistence. The gate is
 * [NULL_TRAJECTORY_GATE]. It has one unstandardized intercept. Every
 * coefficient, including that intercept, has a fixed Gaussian prior
 * N(0, σ² / [NULL_TRAJECTORY_SHRINKAGE_LAMBDA]). That lambda is not tuned
 * on a held-out row or on the full history.
 *
 * The posterior mean does not depend on σ². With a flat prior on log σ²,
 * a new residual is Student-t with degrees of freedom equal to the supervised
 * count. The reported scale is that Student-t scale. Overlapping transitions
 * are not independent, so the scale is not a calibrated probability.
 * Training residual spread is the population deviation of the fitted
 * training residuals. [NULL_TRAJECTORY_ASSUMPTIONS] states the prior.
 *
 * Official scored residual is actual minus the bounded 0–10 forecast.
 * The learner itself fits the raw residual from the starting value.
 * Recorded horizons come from the completed interval and stay retrospective.
 * A sandbox horizon is the caller's. Membership reuses [causalPriorQualifies].
 */
const val NULL_TRAJECTORY_PROTOCOL = "null-trajectory-learner-v2"

const val NULL_TRAJECTORY_GATE = "null-trajectory-gate-v2"

/**
 * Fixed prior precision on each standardized gate column.
 * Prior variance of a coefficient is σ² / this value.
 */
const val NULL_TRAJECTORY_SHRINKAGE_LAMBDA = 100.0

/**
 * Prior and scoring assumptions carried on every null-trajectory artifact.
 * The shrinkage precision is fixed. It is not selected from a held-out row
 * or from the full history.
 */
const val NULL_TRAJECTORY_ASSUMPTIONS =
    "One model per core axis. The fitted target is the ending value minus the starting value, " +
        "so a zero residual is Persistence. The gate is null-trajectory-gate-v2: one intercept, " +
        "recent-mean correction, a recent-mean missingness bit, incoming delta, an incoming-delta " +
        "missingness bit, horizon hours, and the sine and cosine of the horizon-end local hour. " +
        "The intercept is a column of ones, represented once, and it is not standardized. " +
        "A missingness bit that does not vary is encoded as zero and its coefficient is zero, so constant " +
        "status cannot become another intercept. A missingness bit that varies still separates an " +
        "unavailable number from an observed zero. Numeric columns use the training fold's population mean " +
        "and population deviation. A numeric column that does not vary is standardized to zero and its " +
        "coefficient is zero. A recorded zero stays observed. The intercept coefficient is in residual " +
        "points. The other coefficients are on standardized or zero-one columns. All of them, including " +
        "the intercept, share one fixed precision of 100. Sharing it is intentional: the intercept does " +
        "not receive a looser prior because its units differ, and there is no second precision to tune. " +
        "That precision is not chosen from a held-out row or from the full history. Each coefficient has " +
        "an independent Gaussian prior centered at zero, with variance equal to the noise variance " +
        "divided by 100. Noise is modeled as independent and Gaussian. A flat prior on the log noise " +
        "variance gives a Student-t predictive scale with degrees of freedom equal to the supervised count. " +
        "Below 3 degrees of freedom that variance is not finite, and the scale is still reported. " +
        "Overlapping transitions are not independent, so the scale is not a probability. Training residual " +
        "spread is the population deviation of the fitted training residuals. The official scored residual " +
        "is the actual ending value minus the bounded 0–10 forecast. The learner fits the unbounded residual " +
        "from the starting value. A finite input whose centered square is not finite is treated as a " +
        "nonfinite system and falls back. A held-out example with causal gaps keeps those reasons and " +
        "receives no endpoint and no residual. A negative recorded span stays in the raw projection and is " +
        "not forecast or scored. An ineligible example, or one with a negative recorded span, does not " +
        "train another fold."

private const val MILLIS_PER_HOUR = 3_600_000.0
private const val MINIMUM_SUPERVISED_ROWS = 2

enum class NullGateColumn {
    INTERCEPT,
    RECENT_MEAN_CORRECTION,
    RECENT_MEAN_MISSING,
    INCOMING_DELTA,
    INCOMING_DELTA_MISSING,
    HORIZON_HOURS,
    HORIZON_END_HOUR_SIN,
    HORIZON_END_HOUR_COS,
    ;

    val missingness: Boolean
        get() = this == RECENT_MEAN_MISSING || this == INCOMING_DELTA_MISSING

    fun label(): String = when (this) {
        INTERCEPT -> "intercept"
        RECENT_MEAN_CORRECTION -> "recent-mean correction"
        RECENT_MEAN_MISSING -> "recent-mean missing"
        INCOMING_DELTA -> "incoming delta"
        INCOMING_DELTA_MISSING -> "incoming delta missing"
        HORIZON_HOURS -> "horizon hours"
        HORIZON_END_HOUR_SIN -> "horizon-end hour sine"
        HORIZON_END_HOUR_COS -> "horizon-end hour cosine"
    }
}

enum class NullComparisonModel {
    NULL_TRAJECTORY,
    PERSISTENCE,
    RECENT_MEAN,
}

enum class NullForecastFallback {
    NO_FORECAST_ORIGIN,
    NO_PRIOR_INPUT,
    INSUFFICIENT_SUPPORT,
    START_UNOBSERVED,
    NONFINITE_INPUT,
    SINGULAR_OR_NONFINITE,
    HELD_EXAMPLE_INELIGIBLE,
    NEGATIVE_RECORDED_HORIZON,
}

data class NullAxisState(
    val axisId: String,
    val startingRaw: Int?,
    val incomingDelta: Int?,
    val recentMean: Double?,
    val recentMeanChoice: RecentMeanChoice?,
) {
    init {
        require(startingRaw == null || startingRaw in 0..10)
        require(recentMean == null || recentMean.isFinite())
        require((recentMean == null) == (recentMeanChoice == null))
    }

    val recentMeanCorrection: Double?
        get() = if (startingRaw == null || recentMean == null) null else recentMean - startingRaw
}

/**
 * Timing and past-only axis state. Behavior fields are not representable here.
 * [retrospectiveHorizon] is true when the horizon is the completed interval.
 */
data class NullTrajectoryInput(
    val zoneId: String,
    val clockReconstruction: CausalClockReconstruction = CausalClockReconstruction.READ_TIME_ZONE,
    val retrospectiveHorizon: Boolean,
    val provenance: ScenarioProvenance,
    val anchorAt: Long,
    val horizonMillis: Long,
    val horizonEndAt: Long,
    val axes: List<NullAxisState>,
) {
    init {
        require(clockReconstruction == CausalClockReconstruction.READ_TIME_ZONE)
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(horizonHours().isFinite())
    }

    fun horizonHours(): Double = horizonMillis / MILLIS_PER_HOUR

    fun axis(axisId: String): NullAxisState = axes.single { it.axisId == axisId }
}

data class NullGateObservation(
    val recentMeanCorrection: Double?,
    val incomingDelta: Double?,
    val horizonHours: Double,
    val horizonEndHourSin: Double,
    val horizonEndHourCos: Double,
) {
    init {
        require(recentMeanCorrection == null || recentMeanCorrection.isFinite())
        require(incomingDelta == null || incomingDelta.isFinite())
        require(horizonHours.isFinite() && horizonEndHourSin.isFinite() && horizonEndHourCos.isFinite())
    }
}

data class NullGateParameter(
    val column: NullGateColumn,
    val center: Double,
    val scale: Double,
    val nonvarying: Boolean,
    val coefficient: Double,
    val priorPrecision: Double = NULL_TRAJECTORY_SHRINKAGE_LAMBDA,
) {
    init {
        require(center.isFinite() && scale.isFinite() && coefficient.isFinite())
        require(scale > 0.0)
        require(priorPrecision == NULL_TRAJECTORY_SHRINKAGE_LAMBDA && priorPrecision > 0.0)
        if (column == NullGateColumn.INTERCEPT) {
            require(!nonvarying && center == 0.0 && scale == 1.0)
        }
        if (column.missingness && nonvarying) {
            require(coefficient == 0.0)
        }
    }
}

data class NullAxisForecast(
    val axisId: String,
    val fallback: NullForecastFallback?,
    val observedTargetCount: Int,
    val supervisedCount: Int,
    val trainingIds: List<String>,
    val parameters: List<NullGateParameter>?,
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
        if (fallback == null) {
            require(parameters != null)
        } else if (fallback != NullForecastFallback.START_UNOBSERVED) {
            require(parameters == null)
        }
        if (parameters != null) {
            require(parameters.map { it.column } == NullGateColumn.entries)
        }
        require((expectedResidual == null) == (forecast == null))
        require(predictiveScale == null || (predictiveScale >= 0.0 && predictiveScale.isFinite()))
        require(trainingResidualSpread == null || trainingResidualSpread >= 0.0)
        val persistence = fallback == NullForecastFallback.INSUFFICIENT_SUPPORT ||
            fallback == NullForecastFallback.NO_PRIOR_INPUT ||
            fallback == NullForecastFallback.SINGULAR_OR_NONFINITE ||
            fallback == NullForecastFallback.NONFINITE_INPUT
        if (
            fallback == NullForecastFallback.HELD_EXAMPLE_INELIGIBLE ||
            fallback == NullForecastFallback.NEGATIVE_RECORDED_HORIZON
        ) {
            require(forecast == null && expectedResidual == null)
            require(officialResidual == null && rawResidual == null)
            require(parameters == null)
        }
        if (persistence && startingRaw != null) {
            require(expectedResidual == 0.0)
            require(forecast == observableForecast(startingRaw.toDouble()))
        }
        if (startingRaw == null) {
            require(forecast == null)
        }
    }
}

data class NullTrajectoryFold(
    val identity: ExampleIdentity,
    val retrospectiveHorizon: Boolean,
    val provenance: ScenarioProvenance,
    val horizonHours: Double?,
    val gaps: List<CausalIneligibilityReason>,
    val axes: List<NullAxisForecast>,
) {
    init {
        require(provenance == ScenarioProvenance.RECORDED_REALIZED)
        require(retrospectiveHorizon)
        require(gaps == gaps.distinct())
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(horizonHours == null || horizonHours.isFinite())
        val ineligible = axes.all { it.fallback == NullForecastFallback.HELD_EXAMPLE_INELIGIBLE }
        require(ineligible == gaps.isNotEmpty())
        if (axes.all { it.fallback == NullForecastFallback.NEGATIVE_RECORDED_HORIZON }) {
            require(gaps.isEmpty())
            require(horizonHours != null && horizonHours < 0.0)
        }
    }
}

data class NullCommonSupportModel(
    val model: NullComparisonModel,
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

data class NullCommonSupport(
    val axisId: String,
    val count: Int,
    val learnedForecastCount: Int,
    val persistenceFallbackCount: Int,
    val models: List<NullCommonSupportModel>,
) {
    init {
        require(count >= 0)
        require(learnedForecastCount >= 0 && persistenceFallbackCount >= 0)
        require(models.map { it.model } == NullComparisonModel.entries)
        models.forEach { model ->
            require((model.mae == null) == (count == 0))
        }
    }
}

data class NullTrajectoryEvaluation(
    val protocol: String = NULL_TRAJECTORY_PROTOCOL,
    val gate: String = NULL_TRAJECTORY_GATE,
    val shrinkageLambda: Double = NULL_TRAJECTORY_SHRINKAGE_LAMBDA,
    val cutoff: CausalCutoff = CausalCutoff.ENDING_BEFORE_ORIGIN_OR_EXACT_ANCHOR,
    val availabilityMode: ObservationAvailabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
    val strictAsKnownAvailability: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val updatesModel: Boolean = false,
    val estimatesBehaviorEffect: Boolean = false,
    val assumptions: String = NULL_TRAJECTORY_ASSUMPTIONS,
    val folds: List<NullTrajectoryFold>,
    val commonSupport: List<NullCommonSupport>,
) {
    init {
        require(protocol == NULL_TRAJECTORY_PROTOCOL)
        require(gate == NULL_TRAJECTORY_GATE)
        require(shrinkageLambda == NULL_TRAJECTORY_SHRINKAGE_LAMBDA && shrinkageLambda > 0.0)
        require(assumptions == NULL_TRAJECTORY_ASSUMPTIONS)
        require(cutoff == CausalCutoff.ENDING_BEFORE_ORIGIN_OR_EXACT_ANCHOR)
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION)
        require(!strictAsKnownAvailability && !suitableForRecommendation && !updatesModel && !estimatesBehaviorEffect)
        val order = folds.map { it.identity.endingAt to it.identity.transitionId }
        require(order == order.sortedWith(compareBy({ it.first }, { it.second })))
        require(commonSupport.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
    }
}

fun projectNullTrajectoryInput(
    input: StateForecastInput,
    scenario: TransitionScenario,
    zone: ZoneId,
): NullTrajectoryInput {
    val anchorAt = input.stateSide.focal.recordedAt
    val horizonEndAt = when (scenario.provenance) {
        ScenarioProvenance.RECORDED_REALIZED -> requireNotNull(scenario.clock).endAt
        ScenarioProvenance.SANDBOX_SPECIFIED -> {
            require(scenario.anchorKey == input.stateSide.trajectory.stateKey) {
                "A sandbox horizon uses its anchor"
            }
            Math.addExact(anchorAt, scenario.span.millis)
        }
    }
    val baseline = causalBaselineInput(input.stateSide.trajectory)
    val axes = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        val focal = input.observation.axes.single { it.axisId == axisId }
        val reading = baseline.axes.single { it.axisId == axisId }
        val starting = if (focal.status == FocalAxisStatus.RECORDED) focal.raw else null
        val recent = when {
            reading.dayMean != null -> RecentMeanChoice.TRAILING_DAY to reading.dayMean
            reading.weekMean != null -> RecentMeanChoice.TRAILING_WEEK to reading.weekMean
            else -> null
        }
        NullAxisState(
            axisId = axisId,
            startingRaw = starting,
            incomingDelta = reading.incomingDelta,
            recentMean = recent?.second,
            recentMeanChoice = recent?.first,
        )
    }
    return NullTrajectoryInput(
        zoneId = zone.id,
        retrospectiveHorizon = scenario.provenance == ScenarioProvenance.RECORDED_REALIZED,
        provenance = scenario.provenance,
        anchorAt = anchorAt,
        horizonMillis = scenario.span.millis,
        horizonEndAt = horizonEndAt,
        axes = axes,
    )
}

fun projectNullTrajectoryLearner(
    stateExamples: List<StateForecastExample>,
    conditionalExamples: List<ConditionalTransitionExample>,
    zone: ZoneId,
): NullTrajectoryEvaluation {
    val state = stateExamples.sortedWith(compareBy({ it.identity.endingAt }, { it.identity.transitionId }))
    val conditional = conditionalExamples.sortedWith(compareBy({ it.identity.endingAt }, { it.identity.transitionId }))
    require(state.map { it.identity } == conditional.map { it.identity }) {
        "Null folds share the state and conditional identities"
    }
    require(state.map { it.identity.transitionId }.distinct().size == state.size) {
        "Null folds keep distinct transition ids"
    }
    val pairs = state.zip(conditional).map { (stateExample, conditionalExample) ->
        require(stateExample.identity == conditionalExample.identity)
        require(stateExample.input == conditionalExample.input)
        require(stateExample.target == conditionalExample.target)
        require(stateExample.targetConflicts == conditionalExample.targetConflicts)
        require(stateExample.gaps == conditionalExample.gaps) {
            "Paired state and conditional examples share causal gaps"
        }
        require(conditionalExample.scenario.provenance == ScenarioProvenance.RECORDED_REALIZED)
        HeldExample(
            stateExample = stateExample,
            conditionalExample = conditionalExample,
            nullInput = stateExample.input?.let { projectNullTrajectoryInput(it, conditionalExample.scenario, zone) },
        )
    }
    val folds = pairs.map { held -> fold(held, pairs) }
    return NullTrajectoryEvaluation(
        folds = folds,
        commonSupport = commonSupport(folds, pairs),
    )
}

internal fun nullGateObservation(input: NullTrajectoryInput, axisId: String): NullGateObservation {
    val axis = input.axis(axisId)
    val (sine, cosine) = hourSinCos(input.horizonEndAt, ZoneId.of(input.zoneId))
    return NullGateObservation(
        recentMeanCorrection = axis.recentMeanCorrection,
        incomingDelta = axis.incomingDelta?.toDouble(),
        horizonHours = input.horizonHours(),
        horizonEndHourSin = sine,
        horizonEndHourCos = cosine,
    )
}

private data class HeldExample(
    val stateExample: StateForecastExample,
    val conditionalExample: ConditionalTransitionExample,
    val nullInput: NullTrajectoryInput?,
)

private data class SupervisedRow(
    val id: String,
    val observation: NullGateObservation,
    val residual: Double,
)

private fun fold(
    held: HeldExample,
    pairs: List<HeldExample>,
): NullTrajectoryFold {
    val identity = held.stateExample.identity
    val input = held.nullInput
    val gaps = held.stateExample.gaps
    val negativeHorizon = held.conditionalExample.scenario.span.millis < 0
    val axes = when {
        gaps.isNotEmpty() -> CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            unscoredAxis(axisId, NullForecastFallback.HELD_EXAMPLE_INELIGIBLE, input?.axis(axisId)?.startingRaw)
        }
        negativeHorizon -> CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            unscoredAxis(axisId, NullForecastFallback.NEGATIVE_RECORDED_HORIZON, input?.axis(axisId)?.startingRaw)
        }
        else -> {
            val priors = if (identity.anchorAt == null) {
                emptyList()
            } else {
                pairs.filter { nullPriorQualifies(it, identity) }
            }
            CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
                axisForecast(axisId, held, priors, input)
            }
        }
    }
    return NullTrajectoryFold(
        identity = identity,
        retrospectiveHorizon = true,
        provenance = ScenarioProvenance.RECORDED_REALIZED,
        horizonHours = input?.horizonHours(),
        gaps = gaps,
        axes = axes,
    )
}

private fun nullPriorQualifies(prior: HeldExample, held: ExampleIdentity): Boolean {
    if (!prior.stateExample.available || !prior.conditionalExample.available) return false
    if (prior.stateExample.gaps.isNotEmpty() || prior.conditionalExample.gaps.isNotEmpty()) return false
    if (prior.conditionalExample.scenario.span.millis < 0) return false
    return causalPriorQualifies(prior.stateExample, held)
}

private fun unscoredAxis(
    axisId: String,
    fallback: NullForecastFallback,
    starting: Int?,
): NullAxisForecast {
    return NullAxisForecast(
        axisId = axisId,
        fallback = fallback,
        observedTargetCount = 0,
        supervisedCount = 0,
        trainingIds = emptyList(),
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

private fun axisForecast(
    axisId: String,
    held: HeldExample,
    priors: List<HeldExample>,
    input: NullTrajectoryInput?,
): NullAxisForecast {
    val starting = input?.axis(axisId)?.startingRaw
    val observedTargets = priors.filter { prior ->
        targetStatus(prior, axisId) == CausalTargetStatus.OBSERVED
    }
    val supervised = observedTargets.mapNotNull { prior -> supervisedRow(prior, axisId) }
        .sortedBy { it.id }
    val trainingIds = supervised.map { it.id }
    val actual = heldTarget(held, axisId)
    if (identityUnavailable(held)) {
        return axisResult(axisId, NullForecastFallback.NO_FORECAST_ORIGIN, observedTargets.size, trainingIds, starting, actual)
    }
    if (priors.isEmpty()) {
        return axisResult(axisId, NullForecastFallback.NO_PRIOR_INPUT, observedTargets.size, trainingIds, starting, actual)
    }
    if (supervised.size < MINIMUM_SUPERVISED_ROWS) {
        return axisResult(axisId, NullForecastFallback.INSUFFICIENT_SUPPORT, observedTargets.size, trainingIds, starting, actual)
    }
    if (supervised.any { row -> !rowIsFinite(row) }) {
        return axisResult(axisId, NullForecastFallback.NONFINITE_INPUT, observedTargets.size, trainingIds, starting, actual)
    }
    val fit = fitStandardizedRidge(supervised.map { it.observation }, supervised.map { it.residual })
        ?: return axisResult(axisId, NullForecastFallback.SINGULAR_OR_NONFINITE, observedTargets.size, trainingIds, starting, actual)
    if (starting == null) {
        return axisResult(
            axisId = axisId,
            fallback = NullForecastFallback.START_UNOBSERVED,
            observedTargetCount = observedTargets.size,
            trainingIds = trainingIds,
            starting = null,
            actual = actual,
            parameters = fit.parameters,
            predictiveDegreesOfFreedom = supervised.size,
            trainingResidualSpread = fit.trainingResidualSpread,
        )
    }
    val observation = nullGateObservation(checkNotNull(input), axisId)
    val expected = predictResidual(observation, fit)
        ?: return axisResult(axisId, NullForecastFallback.NONFINITE_INPUT, observedTargets.size, trainingIds, starting, actual)
    val raw = starting.toDouble() + expected
    if (!raw.isFinite()) {
        return axisResult(axisId, NullForecastFallback.NONFINITE_INPUT, observedTargets.size, trainingIds, starting, actual)
    }
    val forecast = observableForecast(raw)
    val scale = predictiveScale(observation, fit)
    return NullAxisForecast(
        axisId = axisId,
        fallback = null,
        observedTargetCount = observedTargets.size,
        supervisedCount = trainingIds.size,
        trainingIds = trainingIds,
        parameters = fit.parameters,
        startingRaw = starting,
        expectedResidual = expected,
        forecast = forecast,
        officialResidual = actual?.let { it - forecast.boundedForecast },
        rawResidual = actual?.let { it - forecast.rawExtrapolation },
        predictiveScale = scale,
        predictiveDegreesOfFreedom = supervised.size,
        trainingResidualSpread = fit.trainingResidualSpread,
    )
}

private fun axisResult(
    axisId: String,
    fallback: NullForecastFallback,
    observedTargetCount: Int,
    trainingIds: List<String>,
    starting: Int?,
    actual: Double?,
    parameters: List<NullGateParameter>? = null,
    predictiveDegreesOfFreedom: Int? = null,
    trainingResidualSpread: Double? = null,
): NullAxisForecast {
    val persist = starting != null && fallback != NullForecastFallback.NO_FORECAST_ORIGIN &&
        fallback != NullForecastFallback.START_UNOBSERVED &&
        fallback != NullForecastFallback.HELD_EXAMPLE_INELIGIBLE &&
        fallback != NullForecastFallback.NEGATIVE_RECORDED_HORIZON
    val forecast = if (persist) observableForecast(starting.toDouble()) else null
    return NullAxisForecast(
        axisId = axisId,
        fallback = if (parameters == null) fallback else fallback,
        observedTargetCount = observedTargetCount,
        supervisedCount = trainingIds.size,
        trainingIds = trainingIds,
        parameters = if (fallback == NullForecastFallback.START_UNOBSERVED) parameters else null,
        startingRaw = starting,
        expectedResidual = if (persist) 0.0 else null,
        forecast = forecast,
        officialResidual = if (forecast != null && actual != null) actual - forecast.boundedForecast else null,
        rawResidual = if (forecast != null && actual != null) actual - forecast.rawExtrapolation else null,
        predictiveScale = null,
        predictiveDegreesOfFreedom = if (fallback == NullForecastFallback.START_UNOBSERVED) predictiveDegreesOfFreedom else null,
        trainingResidualSpread = if (fallback == NullForecastFallback.START_UNOBSERVED) trainingResidualSpread else null,
    )
}

private data class RidgeFit(
    val parameters: List<NullGateParameter>,
    val coefficients: DoubleArray,
    val normal: Array<DoubleArray>,
    val trainingResidualSpread: Double?,
    val sse: Double,
    val count: Int,
)

internal fun fitNullRidge(observations: List<NullGateObservation>, residuals: List<Double>): List<NullGateParameter>? {
    return fitStandardizedRidge(observations, residuals)?.parameters
}

internal fun solveNullLinearSystem(matrix: Array<DoubleArray>, rightHandSide: DoubleArray): DoubleArray? {
    return solveLinearSystem(matrix, rightHandSide)
}

internal fun encodeNullGateRows(observations: List<NullGateObservation>): List<List<Double>>? {
    val scales = NullGateColumn.entries.map { column -> columnScale(column, observations) ?: return null }
    return observations.map { observation -> standardizedRow(observation, scales).toList() }
}

private fun fitStandardizedRidge(observations: List<NullGateObservation>, residuals: List<Double>): RidgeFit? {
    val ordered = observations.indices
        .sortedWith(rowOrder(observations, residuals))
        .map { index -> observations[index] to residuals[index] }
    val count = ordered.size
    val rows = ordered.map { it.first }
    val targets = ordered.map { it.second }
    val scales = NullGateColumn.entries.map { column -> columnScale(column, rows) ?: return null }
    val design = rows.map { observation -> standardizedRow(observation, scales) }
    val gram = Array(NullGateColumn.entries.size) { DoubleArray(NullGateColumn.entries.size) }
    val cross = DoubleArray(NullGateColumn.entries.size)
    design.forEachIndexed { row, values ->
        val residual = targets[row]
        values.forEachIndexed { left, leftValue ->
            cross[left] += leftValue * residual
            values.forEachIndexed { right, rightValue ->
                gram[left][right] += leftValue * rightValue
            }
        }
    }
    val normal = Array(gram.size) { row ->
        DoubleArray(gram.size) { column ->
            gram[row][column] + if (row == column) NULL_TRAJECTORY_SHRINKAGE_LAMBDA else 0.0
        }
    }
    val coefficients = solveLinearSystem(normal, cross) ?: return null
    val fitted = design.map { row -> dot(row, coefficients) }
    if (fitted.any { !it.isFinite() }) return null
    val errors = fitted.mapIndexed { index, value -> targets[index] - value }
    val sse = errors.sumOf { it * it } + NULL_TRAJECTORY_SHRINKAGE_LAMBDA * coefficients.sumOf { it * it }
    if (!sse.isFinite() || sse < -1e-8) return null
    val parameters = NullGateColumn.entries.mapIndexed { index, column ->
        val solved = coefficients[index]
        val coefficient = if (column.missingness && scales[index].nonvarying) {
            if (solved != 0.0 && abs(solved) > 1e-8) return null
            0.0
        } else {
            solved
        }
        NullGateParameter(
            column = column,
            center = scales[index].center,
            scale = scales[index].scale,
            nonvarying = scales[index].nonvarying,
            coefficient = coefficient,
            priorPrecision = NULL_TRAJECTORY_SHRINKAGE_LAMBDA,
        )
    }
    return RidgeFit(
        parameters = parameters,
        coefficients = coefficients,
        normal = normal,
        trainingResidualSpread = finitePopulation(errors)?.deviation ?: return null,
        sse = maxOf(0.0, sse),
        count = count,
    )
}

private data class ColumnScale(val center: Double, val scale: Double, val nonvarying: Boolean)

private fun rowOrder(
    observations: List<NullGateObservation>,
    residuals: List<Double>,
): Comparator<Int> {
    return compareBy(
        { observations[it].recentMeanCorrection ?: Double.NEGATIVE_INFINITY },
        { observations[it].incomingDelta ?: Double.NEGATIVE_INFINITY },
        { observations[it].horizonHours },
        { observations[it].horizonEndHourSin },
        { observations[it].horizonEndHourCos },
        { residuals[it] },
        { it },
    )
}

private fun columnScale(column: NullGateColumn, observations: List<NullGateObservation>): ColumnScale? {
    if (column == NullGateColumn.INTERCEPT) {
        return ColumnScale(center = 0.0, scale = 1.0, nonvarying = false)
    }
    if (column.missingness) {
        val values = observations.map { rawColumn(it, column) }
        if (values.any { !it.isFinite() }) return null
        return ColumnScale(center = 0.0, scale = 1.0, nonvarying = values.distinct().size < 2)
    }
    val observed = observations.mapNotNull { observation ->
        rawColumnOrNull(observation, column)
    }
    val summary = finitePopulation(observed)
    if (observed.isNotEmpty() && summary == null) return null
    val nonvarying = summary == null || summary.count < 2 ||
        !deviationChangesGeometry(summary.deviation, summary.mean)
    return ColumnScale(
        center = summary?.mean ?: 0.0,
        scale = if (summary != null && !nonvarying) summary.deviation else 1.0,
        nonvarying = nonvarying,
    )
}

private fun finitePopulation(values: List<Double>): NumericSummary? {
    if (values.isEmpty() || values.any { !it.isFinite() }) return null
    val mean = values.sum() / values.size
    if (!mean.isFinite()) return null
    val squareSum = values.sumOf { value ->
        val delta = value - mean
        delta * delta
    }
    if (!squareSum.isFinite()) return null
    val deviation = sqrt(squareSum / values.size)
    if (!deviation.isFinite()) return null
    return populationSummary(values)
}

private fun standardizedRow(observation: NullGateObservation, scales: List<ColumnScale>): DoubleArray {
    return NullGateColumn.entries.mapIndexed { index, column ->
        val scale = scales[index]
        if (column == NullGateColumn.INTERCEPT) return@mapIndexed 1.0
        if (column.missingness) {
            return@mapIndexed if (scale.nonvarying) 0.0 else rawColumn(observation, column)
        }
        val raw = rawColumnOrNull(observation, column)
        if (raw == null || scale.nonvarying) 0.0 else (raw - scale.center) / scale.scale
    }.toDoubleArray()
}

private fun rawColumn(observation: NullGateObservation, column: NullGateColumn): Double {
    return when (column) {
        NullGateColumn.RECENT_MEAN_MISSING -> if (observation.recentMeanCorrection == null) 1.0 else 0.0
        NullGateColumn.INCOMING_DELTA_MISSING -> if (observation.incomingDelta == null) 1.0 else 0.0
        NullGateColumn.INTERCEPT -> 1.0
        else -> rawColumnOrNull(observation, column) ?: 0.0
    }
}

private fun rawColumnOrNull(observation: NullGateObservation, column: NullGateColumn): Double? {
    return when (column) {
        NullGateColumn.RECENT_MEAN_CORRECTION -> observation.recentMeanCorrection
        NullGateColumn.INCOMING_DELTA -> observation.incomingDelta
        NullGateColumn.HORIZON_HOURS -> observation.horizonHours
        NullGateColumn.HORIZON_END_HOUR_SIN -> observation.horizonEndHourSin
        NullGateColumn.HORIZON_END_HOUR_COS -> observation.horizonEndHourCos
        NullGateColumn.INTERCEPT,
        NullGateColumn.RECENT_MEAN_MISSING,
        NullGateColumn.INCOMING_DELTA_MISSING,
        -> null
    }
}

private fun predictResidual(observation: NullGateObservation, fit: RidgeFit): Double? {
    val row = standardizedRow(observation, fit.parameters.map { ColumnScale(it.center, it.scale, it.nonvarying) })
    val value = dot(row, fit.coefficients)
    return value.takeIf { it.isFinite() }
}

private fun predictiveScale(observation: NullGateObservation, fit: RidgeFit): Double? {
    val row = standardizedRow(observation, fit.parameters.map { ColumnScale(it.center, it.scale, it.nonvarying) })
    val solved = solveLinearSystem(fit.normal, row) ?: return null
    val quadratic = dot(row, solved)
    if (!quadratic.isFinite() || quadratic < -1e-8) return null
    val scale = sqrt(fit.sse / fit.count * (1.0 + maxOf(0.0, quadratic)))
    return scale.takeIf { it.isFinite() }
}

private fun solveLinearSystem(matrix: Array<DoubleArray>, rightHandSide: DoubleArray): DoubleArray? {
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

private fun supervisedRow(prior: HeldExample, axisId: String): SupervisedRow? {
    val input = prior.nullInput ?: return null
    val starting = input.axis(axisId).startingRaw ?: return null
    val actual = heldTarget(prior, axisId) ?: return null
    return SupervisedRow(
        id = prior.stateExample.identity.transitionId,
        observation = nullGateObservation(input, axisId),
        residual = actual - starting,
    )
}

private fun heldTarget(held: HeldExample, axisId: String): Double? {
    val status = targetStatus(held, axisId)
    val raw = held.stateExample.target.reading(axisId).raw
    return if (status == CausalTargetStatus.OBSERVED) raw?.toDouble() else null
}

private fun targetStatus(held: HeldExample, axisId: String): CausalTargetStatus {
    return encodeCausalAxisTargets(held.stateExample.target, held.stateExample.targetConflicts)
        .axes.single { it.axisId == axisId }
        .status
}

private fun identityUnavailable(held: HeldExample): Boolean {
    return held.stateExample.identity.anchorAt == null || held.nullInput == null
}

private fun rowIsFinite(row: SupervisedRow): Boolean {
    return row.residual.isFinite() &&
        (row.observation.recentMeanCorrection == null || row.observation.recentMeanCorrection.isFinite()) &&
        (row.observation.incomingDelta == null || row.observation.incomingDelta.isFinite()) &&
        row.observation.horizonHours.isFinite() &&
        row.observation.horizonEndHourSin.isFinite() &&
        row.observation.horizonEndHourCos.isFinite()
}

private fun commonSupport(folds: List<NullTrajectoryFold>, pairs: List<HeldExample>): List<NullCommonSupport> {
    val byId = pairs.associateBy { it.stateExample.identity.transitionId }
    return CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        val shared = folds.mapNotNull { fold ->
            val held = byId.getValue(fold.identity.transitionId)
            val actual = heldTarget(held, axisId) ?: return@mapNotNull null
            val axis = fold.axes.single { it.axisId == axisId }
            val nullForecast = axis.forecast ?: return@mapNotNull null
            val input = held.nullInput ?: return@mapNotNull null
            val state = input.axis(axisId)
            val persistence = state.startingRaw?.let { observableForecast(it.toDouble()) } ?: return@mapNotNull null
            val recent = state.recentMean?.let { observableForecast(it) } ?: return@mapNotNull null
            Triple(
                actual - nullForecast.boundedForecast,
                actual - persistence.boundedForecast,
                actual - recent.boundedForecast,
            )
        }
        val axes = folds.map { fold -> fold.axes.single { it.axisId == axisId } }
        NullCommonSupport(
            axisId = axisId,
            count = shared.size,
            learnedForecastCount = axes.count { it.fallback == null && it.forecast != null },
            persistenceFallbackCount = axes.count { axis ->
                axis.forecast != null && axis.fallback != null && axis.expectedResidual == 0.0
            },
            models = NullComparisonModel.entries.map { model ->
                val errors = shared.map { (nullError, persistenceError, recentError) ->
                    when (model) {
                        NullComparisonModel.NULL_TRAJECTORY -> nullError
                        NullComparisonModel.PERSISTENCE -> persistenceError
                        NullComparisonModel.RECENT_MEAN -> recentError
                    }
                }
                NullCommonSupportModel(
                    model = model,
                    bias = mean(errors),
                    mae = mean(errors.map { abs(it) }),
                    rmse = rootMeanSquare(errors),
                )
            },
        )
    }
}

private fun hourSinCos(at: Long, zone: ZoneId): Pair<Double, Double> {
    val zoned = Instant.ofEpochMilli(at).atZone(zone)
    val seconds = zoned.hour * 3600.0 + zoned.minute * 60.0 + zoned.second
    val angle = 2.0 * PI * seconds / 86_400.0
    return sin(angle) to cos(angle)
}

private fun dot(left: DoubleArray, right: DoubleArray): Double {
    var sum = 0.0
    for (index in left.indices) sum += left[index] * right[index]
    return sum
}

private fun mean(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    return values.sum() / values.size
}

private fun rootMeanSquare(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    return sqrt(values.sumOf { it * it } / values.size)
}

fun nullTrajectoryAxisLabel(axis: NullAxisForecast): String {
    val support = "support ${axis.supervisedCount}"
    val start = axis.startingRaw?.toString() ?: "unobserved"
    val fallback = axis.fallback?.let { " · ${fallbackLabel(it)}" }.orEmpty()
    val residual = axis.expectedResidual?.let { " · expected residual ${formatNumber(it)}" }.orEmpty()
    val endpoint = axis.forecast?.let {
        " · raw ${formatNumber(it.rawExtrapolation)} · bounded ${formatNumber(it.boundedForecast)}"
    }.orEmpty()
    val degrees = axis.predictiveDegreesOfFreedom
    val finiteVariance = if (degrees != null && degrees < 3) " · variance not finite below 3 degrees" else ""
    val scale = axis.predictiveScale?.let {
        " · Student-t predictive scale ${formatNumber(it)} · df $degrees$finiteVariance"
    }.orEmpty()
    val spread = axis.trainingResidualSpread?.let { " · training residual spread ${formatNumber(it)}" }.orEmpty()
    return "$support · start $start · persistence $start$fallback$residual$endpoint$scale$spread"
}

fun nullTrajectoryTechnicalLabel(evaluation: NullTrajectoryEvaluation, axis: NullAxisForecast): String {
    val parameters = axis.parameters?.joinToString(" · ") { parameter ->
        val varying = if (parameter.nonvarying) " nonvarying" else ""
        val precision = if (parameter.column == NullGateColumn.INTERCEPT) {
            " prior ${formatNumber(parameter.priorPrecision)}"
        } else {
            ""
        }
        "${parameter.column.label()} ${formatNumber(parameter.coefficient)}$varying$precision"
    } ?: "no fitted parameters"
    val ids = if (axis.trainingIds.isEmpty()) "none" else axis.trainingIds.joinToString(", ")
    return "Gate ${evaluation.gate} · shrinkage ${formatNumber(evaluation.shrinkageLambda)} · " +
        "training $ids · $parameters"
}

fun nullTrajectorySupportLabel(support: NullCommonSupport): String {
    val models = support.models.joinToString(" · ") { model ->
        val name = when (model.model) {
            NullComparisonModel.NULL_TRAJECTORY -> "Null"
            NullComparisonModel.PERSISTENCE -> "Persistence"
            NullComparisonModel.RECENT_MEAN -> "Recent mean"
        }
        "$name bias ${formatMetric(model.bias)} MAE ${formatMetric(model.mae)} RMSE ${formatMetric(model.rmse)}"
    }
    return "Learned forecasts ${support.learnedForecastCount} · " +
        "Persistence fallbacks ${support.persistenceFallbackCount} · " +
        "Common support count ${support.count} · $models"
}

private fun fallbackLabel(fallback: NullForecastFallback): String = when (fallback) {
    NullForecastFallback.NO_FORECAST_ORIGIN -> "no forecast origin"
    NullForecastFallback.NO_PRIOR_INPUT -> "no prior input"
    NullForecastFallback.INSUFFICIENT_SUPPORT -> "insufficient support"
    NullForecastFallback.START_UNOBSERVED -> "start unobserved"
    NullForecastFallback.NONFINITE_INPUT -> "nonfinite input"
    NullForecastFallback.SINGULAR_OR_NONFINITE -> "singular or nonfinite solution"
    NullForecastFallback.HELD_EXAMPLE_INELIGIBLE -> "held example ineligible"
    NullForecastFallback.NEGATIVE_RECORDED_HORIZON -> "negative recorded horizon"
}

fun nullTrajectoryGapLabel(fold: NullTrajectoryFold): String {
    if (fold.gaps.isEmpty()) return "Causal gaps none"
    return "Causal gaps " + fold.gaps.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
}

private fun formatNumber(value: Double): String {
    val rounded = kotlin.math.round(value * 1000.0) / 1000.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private fun formatMetric(value: Double?): String = value?.let { formatNumber(it) } ?: "none"
