package com.orbit.wellness.domain.checkin

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Debug evaluation of three interpretable axis baselines.
 *
 * This is not a complete psychological state and not a latent encoder.
 * [ObservedAxisTarget] records the four core axes for this pass. A later
 * latent, emotion, or uncertainty head can be added beside it; [AxisPredictionRecord]
 * stays the historical axis-forecast record.
 *
 * A causal input is the focal state and information available at or before it,
 * which is what a live forecast may use. A retrospective contextual
 * representation may later use both incoming and outgoing transitions to tell
 * otherwise identical occurrences apart for clustering, regimes, or attractors.
 * Outgoing context is not an input here. Retrospective PCA coordinates are not
 * a target or an input: they are dataset-relative and can move when history changes.
 *
 * A later model may encode this causal history, encode a transition, map the
 * pair to a next latent state, and decode axes, emotion, and uncertainty.
 * That sketch is not implemented in this pass.
 *
 * Step momentum asks whether the last recorded change would repeat once.
 * [rawExtrapolation] keeps that unconstrained result. The official forecast
 * and residual use [boundedForecast], which stays on 0–10. Trajectory experts
 * live beside these baselines and are not blended into the aggregate.
 */
const val WALK_FORWARD_BASELINE_PROTOCOL = "walk-forward-state-baseline-v2"

enum class OverflowDirection {
    LOWER,
    UPPER,
}

/**
 * An unconstrained extrapolation and the observable 0–10 forecast.
 * Official residuals use [boundedForecast]. [rawExtrapolation] stays diagnostic.
 */
data class ObservableForecast(
    val rawExtrapolation: Double,
    val boundedForecast: Double,
    val overflowDirection: OverflowDirection?,
    val overflowMagnitude: Double,
) {
    init {
        require(rawExtrapolation.isFinite())
        require(boundedForecast in FORECAST_FLOOR..FORECAST_CEILING)
        require(overflowMagnitude >= 0.0 && overflowMagnitude.isFinite())
        when {
            rawExtrapolation < FORECAST_FLOOR -> {
                require(overflowDirection == OverflowDirection.LOWER)
                require(boundedForecast == FORECAST_FLOOR)
                require(overflowMagnitude == FORECAST_FLOOR - rawExtrapolation)
            }
            rawExtrapolation > FORECAST_CEILING -> {
                require(overflowDirection == OverflowDirection.UPPER)
                require(boundedForecast == FORECAST_CEILING)
                require(overflowMagnitude == rawExtrapolation - FORECAST_CEILING)
            }
            else -> {
                require(overflowDirection == null)
                require(overflowMagnitude == 0.0)
                require(boundedForecast == rawExtrapolation)
            }
        }
    }
}

fun observableForecast(raw: Double): ObservableForecast = when {
    raw < FORECAST_FLOOR -> ObservableForecast(
        rawExtrapolation = raw,
        boundedForecast = FORECAST_FLOOR,
        overflowDirection = OverflowDirection.LOWER,
        overflowMagnitude = FORECAST_FLOOR - raw,
    )
    raw > FORECAST_CEILING -> ObservableForecast(
        rawExtrapolation = raw,
        boundedForecast = FORECAST_CEILING,
        overflowDirection = OverflowDirection.UPPER,
        overflowMagnitude = raw - FORECAST_CEILING,
    )
    else -> ObservableForecast(
        rawExtrapolation = raw,
        boundedForecast = raw,
        overflowDirection = null,
        overflowMagnitude = 0.0,
    )
}

private const val FORECAST_FLOOR = 0.0
private const val FORECAST_CEILING = 10.0

enum class BaselineKind {
    PERSISTENCE,
    RECENT_MEAN,
    STEP_MOMENTUM,
}

enum class EvidenceOrigin {
    OBSERVED,
    OVERRIDDEN,
    CLEARED,
    UNOBSERVED,
}

enum class RecentMeanChoice {
    TRAILING_DAY,
    TRAILING_WEEK,
}

enum class SandboxField {
    STARTING,
    INCOMING_DELTA,
    DAY_MEAN,
    WEEK_MEAN,
}

/**
 * One recorded axis head. Missing stays null. A recorded zero stays zero.
 * Anxiety is the observed value, not an inverted one.
 */
data class ObservedAxisReading(
    val axisId: String,
    val raw: Int?,
) {
    init {
        require(raw == null || raw in 0..10) { "An observed axis stays on 0–10" }
    }
}

sealed interface EvaluationTarget

data class ObservedAxisTarget(
    val readings: List<ObservedAxisReading>,
) : EvaluationTarget {
    init {
        require(readings.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS) {
            "Observed axis targets stay in core order"
        }
    }

    fun reading(axisId: String): ObservedAxisReading = readings.single { it.axisId == axisId }
}

/**
 * The live forecast input. It has no outgoing transition, no behavior, and no
 * retrospective coordinate. A synthetic mean does not carry an observed count.
 */
data class CausalAxisReading(
    val axisId: String,
    val startingRaw: Int?,
    val startingOrigin: EvidenceOrigin,
    val incomingDelta: Int?,
    val deltaOrigin: EvidenceOrigin,
    val dayMean: Double?,
    val dayOrigin: EvidenceOrigin,
    val weekMean: Double?,
    val weekOrigin: EvidenceOrigin,
) {
    init {
        require(originMatches(startingRaw != null, startingOrigin))
        require(originMatches(incomingDelta != null, deltaOrigin))
        require(originMatches(dayMean != null, dayOrigin))
        require(originMatches(weekMean != null, weekOrigin))
        require(dayMean == null || dayMean.isFinite())
        require(weekMean == null || weekMean.isFinite())
    }
}

data class CausalBaselineInput(
    val anchorKey: StateOccurrenceKey,
    val axes: List<CausalAxisReading>,
) {
    init {
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS) {
            "Causal baseline inputs stay in core order"
        }
    }
}

sealed interface ForecastEvidence {
    data class Persistence(
        val startingRaw: Int?,
        val origin: EvidenceOrigin,
    ) : ForecastEvidence

    data class RecentMean(
        val dayMean: Double?,
        val dayOrigin: EvidenceOrigin,
        val weekMean: Double?,
        val weekOrigin: EvidenceOrigin,
        val used: RecentMeanChoice?,
    ) : ForecastEvidence

    data class StepMomentum(
        val startingRaw: Int?,
        val startingOrigin: EvidenceOrigin,
        val incomingDelta: Int?,
        val deltaOrigin: EvidenceOrigin,
    ) : ForecastEvidence
}

data class AxisPredictionRecord(
    val baseline: BaselineKind,
    val axisId: String,
    val forecast: ObservableForecast?,
    val evidence: ForecastEvidence,
    /** Actual minus [ObservableForecast.boundedForecast]. This enters the aggregate. */
    val residual: Double?,
    /** Actual minus [ObservableForecast.rawExtrapolation]. Diagnostic only. */
    val rawResidual: Double?,
)

data class WalkForwardAttempt(
    val transitionId: String,
    val anchorKey: StateOccurrenceKey,
    val axisTarget: ObservedAxisTarget,
    val predictions: List<AxisPredictionRecord>,
) {
    init {
        val expected = BaselineKind.entries.flatMap { baseline ->
            CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId -> baseline to axisId }
        }
        require(predictions.map { it.baseline to it.axisId } == expected) {
            "Axis predictions stay in baseline and core-axis order"
        }
        predictions.forEach { record ->
            val actual = axisTarget.reading(record.axisId).raw
            val forecast = record.forecast
            val expectedResidual = if (forecast != null && actual != null) {
                actual - forecast.boundedForecast
            } else {
                null
            }
            val expectedRaw = if (forecast != null && actual != null) {
                actual - forecast.rawExtrapolation
            } else {
                null
            }
            require(record.residual == expectedResidual) {
                "The official residual is actual minus the bounded forecast"
            }
            require(record.rawResidual == expectedRaw) {
                "The raw residual stays separate from the official residual"
            }
        }
    }
}

data class AxisBaselineScore(
    val baseline: BaselineKind,
    val axisId: String,
    val eligibleCount: Int,
    val targetObservedCount: Int,
    val predictionAvailableCount: Int,
    val predictionCount: Int,
    val residualDistribution: NumericSummary?,
    val bias: Double?,
    val mae: Double?,
    val rmse: Double?,
)

data class CommonSupportBaseline(
    val baseline: BaselineKind,
    val bias: Double?,
    val mae: Double?,
    val rmse: Double?,
)

/**
 * One axis compared where Persistence, Recent mean, and Step momentum all
 * predicted the same observed target. A step with only one or two of those
 * forecasts is left out of this comparison. Trajectory experts are not part
 * of it.
 */
data class CommonSupportComparison(
    val axisId: String,
    val count: Int,
    val baselines: List<CommonSupportBaseline>,
) {
    init {
        require(baselines.map { it.baseline } == BaselineKind.entries) {
            "Common support includes every baseline"
        }
        require(count >= 0)
        baselines.forEach { baseline ->
            require((baseline.mae == null) == (count == 0)) {
                "An empty common support has no error"
            }
        }
    }
}

data class WalkForwardMetrics(
    val scores: List<AxisBaselineScore>,
    val commonSupport: List<CommonSupportComparison>,
)

data class WalkForwardEvaluation(
    val attempts: List<WalkForwardAttempt>,
    val metrics: WalkForwardMetrics,
    val protocol: String = WALK_FORWARD_BASELINE_PROTOCOL,
    val retrospective: Boolean = true,
    val causalInput: Boolean = true,
    val predictive: Boolean = true,
    val suitableForRecommendation: Boolean = false,
    val updatesModel: Boolean = false,
    val writesHistory: Boolean = false,
) {
    init {
        require(protocol == WALK_FORWARD_BASELINE_PROTOCOL) { "Unexpected walk-forward protocol" }
        require(retrospective && causalInput && predictive) {
            "Walk-forward evaluates causal forecasts against later observations"
        }
        require(!suitableForRecommendation && !updatesModel && !writesHistory) {
            "Walk-forward does not give advice, fit, or write history"
        }
    }
}

data class WalkForwardProjection(
    val evaluation: WalkForwardEvaluation,
    val anchors: List<StateTrajectoryContext>,
    val stateSide: CausalStateSideDataset,
)

sealed interface HypotheticalTransition {
    data object None : HypotheticalTransition
}

sealed interface SandboxChange {
    data object Keep : SandboxChange
    data object Clear : SandboxChange
    data class Reading(val raw: Int) : SandboxChange {
        init {
            require(raw in 0..10) { "A sandbox axis override stays on 0–10" }
        }
    }

    data class Delta(val delta: Int) : SandboxChange
    data class Mean(val mean: Double) : SandboxChange {
        init {
            require(mean.isFinite()) { "A sandbox mean override is finite" }
        }
    }
}

data class SandboxFieldKey(
    val axisId: String,
    val field: SandboxField,
)

data class SandboxAxisEdit(
    val axisId: String,
    val starting: SandboxChange,
    val incomingDelta: SandboxChange,
    val dayMean: SandboxChange,
    val weekMean: SandboxChange,
)

/**
 * In-memory what-if. [hypotheticalTransition] is the extension point for a
 * later activity, workout, diet, usage, duration, clock, wake, or wearable
 * proposal. This pass has no such proposal, and the baselines do not read one.
 */
data class SandboxScenario(
    val edits: List<SandboxAxisEdit>,
    val syntheticTarget: ObservedAxisTarget? = null,
    val hypotheticalTransition: HypotheticalTransition = HypotheticalTransition.None,
)

data class SandboxForecast(
    val anchorKey: StateOccurrenceKey,
    val input: CausalBaselineInput,
    val predictions: List<AxisPredictionRecord>,
    val syntheticTarget: ObservedAxisTarget?,
    val experts: List<TrajectoryExpert> = emptyList(),
    val horizonHours: Double? = null,
    val horizonPreviews: List<TrajectoryHorizonPreview> = emptyList(),
)

data class SandboxSession(
    val anchorIndex: Int = 0,
    val startingText: Map<String, String> = emptyMap(),
    val deltaText: Map<String, String> = emptyMap(),
    val dayText: Map<String, String> = emptyMap(),
    val weekText: Map<String, String> = emptyMap(),
    val cleared: Set<SandboxFieldKey> = emptySet(),
    val targetText: Map<String, String> = emptyMap(),
    val horizonText: String = "",
) {
    fun withAnchor(index: Int): SandboxSession = SandboxSession(anchorIndex = index)

    fun withText(axisId: String, field: SandboxField, text: String): SandboxSession {
        val nextCleared = cleared - SandboxFieldKey(axisId, field)
        return when (field) {
            SandboxField.STARTING -> copy(startingText = startingText + (axisId to text), cleared = nextCleared)
            SandboxField.INCOMING_DELTA -> copy(deltaText = deltaText + (axisId to text), cleared = nextCleared)
            SandboxField.DAY_MEAN -> copy(dayText = dayText + (axisId to text), cleared = nextCleared)
            SandboxField.WEEK_MEAN -> copy(weekText = weekText + (axisId to text), cleared = nextCleared)
        }
    }

    fun clear(axisId: String, field: SandboxField): SandboxSession {
        return withoutText(axisId, field).copy(cleared = cleared + SandboxFieldKey(axisId, field))
    }

    fun useObserved(axisId: String, field: SandboxField): SandboxSession {
        return withoutText(axisId, field).copy(cleared = cleared - SandboxFieldKey(axisId, field))
    }

    fun withTarget(axisId: String, text: String): SandboxSession {
        return copy(targetText = targetText + (axisId to text))
    }

    fun withHorizon(text: String): SandboxSession = copy(horizonText = text)

    /**
     * A blank, non-numeric, non-finite, or non-positive entry supplies no
     * horizon. It does not invent one from a later interval.
     */
    fun horizonHours(): Double? {
        val value = horizonText.trim().toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it > 0.0 }
    }

    fun change(axisId: String, field: SandboxField): SandboxChange {
        if (SandboxFieldKey(axisId, field) in cleared) return SandboxChange.Clear
        val text = text(axisId, field).trim()
        if (text.isEmpty()) return SandboxChange.Keep
        return when (field) {
            SandboxField.STARTING -> text.toIntOrNull()?.takeIf { it in 0..10 }
                ?.let { SandboxChange.Reading(it) }
                ?: SandboxChange.Keep
            SandboxField.INCOMING_DELTA -> text.toIntOrNull()
                ?.let { SandboxChange.Delta(it) }
                ?: SandboxChange.Keep
            SandboxField.DAY_MEAN, SandboxField.WEEK_MEAN -> text.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?.let { SandboxChange.Mean(it) }
                ?: SandboxChange.Keep
        }
    }

    fun toScenario(): SandboxScenario {
        val edits = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            SandboxAxisEdit(
                axisId = axisId,
                starting = change(axisId, SandboxField.STARTING),
                incomingDelta = change(axisId, SandboxField.INCOMING_DELTA),
                dayMean = change(axisId, SandboxField.DAY_MEAN),
                weekMean = change(axisId, SandboxField.WEEK_MEAN),
            )
        }
        return SandboxScenario(edits = edits, syntheticTarget = syntheticTarget())
    }

    fun syntheticTarget(): ObservedAxisTarget? {
        val readings = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            val raw = targetText[axisId].orEmpty().trim().toIntOrNull()?.takeIf { it in 0..10 }
            ObservedAxisReading(axisId, raw)
        }
        return if (readings.all { it.raw == null }) null else ObservedAxisTarget(readings)
    }

    private fun text(axisId: String, field: SandboxField): String = when (field) {
        SandboxField.STARTING -> startingText[axisId]
        SandboxField.INCOMING_DELTA -> deltaText[axisId]
        SandboxField.DAY_MEAN -> dayText[axisId]
        SandboxField.WEEK_MEAN -> weekText[axisId]
    }.orEmpty()

    private fun withoutText(axisId: String, field: SandboxField): SandboxSession = when (field) {
        SandboxField.STARTING -> copy(startingText = startingText - axisId)
        SandboxField.INCOMING_DELTA -> copy(deltaText = deltaText - axisId)
        SandboxField.DAY_MEAN -> copy(dayText = dayText - axisId)
        SandboxField.WEEK_MEAN -> copy(weekText = weekText - axisId)
    }

    companion object {
        fun fresh(): SandboxSession = SandboxSession()
    }
}

fun projectWalkForward(dataset: CheckInAnalysisDatasetV2): WalkForwardProjection {
    val graph = projectAlternatingTemporalGraph(dataset)
    val side = projectCausalStateSide(dataset, projectStateTrajectoryContext(graph))
    return walkForwardFrom(graph, side)
}

internal fun walkForwardFrom(
    graph: AlternatingTemporalGraph,
    side: CausalStateSideDataset,
): WalkForwardProjection {
    val byKey = side.states.associateBy { it.trajectory.stateKey }
    val attempts = graph.causalSteps.filter { it.eligibility.eligible }.map { step ->
        val starting = checkNotNull(step.input.startingState) {
            "An eligible step has a resolved start"
        }
        val anchor = checkNotNull(byKey[StateOccurrenceKey(starting.checkInId)]).trajectory
        val target = observedAxisTarget(step.target.endingState)
        WalkForwardAttempt(
            transitionId = step.input.transitionId,
            anchorKey = anchor.stateKey,
            axisTarget = target,
            predictions = forecastBaselines(causalBaselineInput(anchor), target),
        )
    }
    return WalkForwardProjection(
        evaluation = WalkForwardEvaluation(
            attempts = attempts,
            metrics = aggregate(attempts),
        ),
        anchors = side.states.map { it.trajectory },
        stateSide = side,
    )
}

fun causalBaselineInput(context: StateTrajectoryContext): CausalBaselineInput {
    return CausalBaselineInput(
        anchorKey = context.stateKey,
        axes = context.axes.map { axis ->
            CausalAxisReading(
                axisId = axis.axisId,
                startingRaw = axis.currentRaw,
                startingOrigin = observedOrigin(axis.currentRaw != null),
                incomingDelta = axis.incomingDelta,
                deltaOrigin = observedOrigin(axis.incomingDelta != null),
                dayMean = axis.trailingDay.summary?.mean,
                dayOrigin = observedOrigin(axis.trailingDay.summary != null),
                weekMean = axis.trailingWeek.summary?.mean,
                weekOrigin = observedOrigin(axis.trailingWeek.summary != null),
            )
        },
    )
}

fun applySandboxEdits(
    anchor: StateTrajectoryContext,
    edits: List<SandboxAxisEdit>,
): CausalBaselineInput {
    val byAxis = edits.associateBy { it.axisId }
    require(byAxis.size == edits.size) { "Sandbox edits name each axis once" }
    val base = causalBaselineInput(anchor)
    return base.copy(
        axes = base.axes.map { reading ->
            val edit = byAxis[reading.axisId] ?: return@map reading
            val starting = applyReading(edit.starting, reading.startingRaw, reading.startingOrigin)
            val delta = applyDelta(edit.incomingDelta, reading.incomingDelta, reading.deltaOrigin)
            val day = applyMean(edit.dayMean, reading.dayMean, reading.dayOrigin)
            val week = applyMean(edit.weekMean, reading.weekMean, reading.weekOrigin)
            reading.copy(
                startingRaw = starting.first,
                startingOrigin = starting.second,
                incomingDelta = delta.first,
                deltaOrigin = delta.second,
                dayMean = day.first,
                dayOrigin = day.second,
                weekMean = week.first,
                weekOrigin = week.second,
            )
        },
    )
}

fun runSandbox(
    anchor: StateTrajectoryContext,
    scenario: SandboxScenario,
    experts: List<TrajectoryExpert> = emptyList(),
    horizonHours: Double? = null,
): SandboxForecast {
    require(scenario.hypotheticalTransition == HypotheticalTransition.None) {
        "This baseline pass cannot consume a hypothetical transition"
    }
    if (horizonHours != null) {
        require(horizonHours.isFinite() && horizonHours > 0.0) {
            "A sandbox horizon is finite and positive"
        }
    }
    val input = applySandboxEdits(anchor, scenario.edits)
    val anchorExperts = experts.filter { it.stateKey == anchor.stateKey }
    return SandboxForecast(
        anchorKey = anchor.stateKey,
        input = input,
        predictions = forecastBaselines(input, scenario.syntheticTarget),
        syntheticTarget = scenario.syntheticTarget,
        experts = anchorExperts,
        horizonHours = horizonHours,
        horizonPreviews = anchorExperts.map { expert ->
            val starting = input.axes.single { it.axisId == expert.axisId }.startingRaw
            TrajectoryHorizonPreview(
                expert = expert,
                forecast = projectExpertHorizon(starting, expert, horizonHours),
            )
        },
    )
}

fun forecastBaselines(
    input: CausalBaselineInput,
    target: ObservedAxisTarget?,
): List<AxisPredictionRecord> {
    return BaselineKind.entries.flatMap { baseline ->
        input.axes.map { axis ->
            val forecast = predict(baseline, axis)
            val actual = target?.reading(axis.axisId)?.raw
            AxisPredictionRecord(
                baseline = baseline,
                axisId = axis.axisId,
                forecast = forecast,
                evidence = evidence(baseline, axis),
                residual = if (forecast != null && actual != null) actual - forecast.boundedForecast else null,
                rawResidual = if (forecast != null && actual != null) actual - forecast.rawExtrapolation else null,
            )
        }
    }
}

private fun predict(baseline: BaselineKind, axis: CausalAxisReading): ObservableForecast? {
    val raw = when (baseline) {
        BaselineKind.PERSISTENCE -> axis.startingRaw?.toDouble()
        BaselineKind.RECENT_MEAN -> recentMean(axis)?.second
        BaselineKind.STEP_MOMENTUM -> if (axis.startingRaw != null && axis.incomingDelta != null) {
            (axis.startingRaw + axis.incomingDelta).toDouble()
        } else {
            null
        }
    }
    return raw?.let { observableForecast(it) }
}

private fun evidence(baseline: BaselineKind, axis: CausalAxisReading): ForecastEvidence = when (baseline) {
    BaselineKind.PERSISTENCE -> ForecastEvidence.Persistence(axis.startingRaw, axis.startingOrigin)
    BaselineKind.RECENT_MEAN -> ForecastEvidence.RecentMean(
        dayMean = axis.dayMean,
        dayOrigin = axis.dayOrigin,
        weekMean = axis.weekMean,
        weekOrigin = axis.weekOrigin,
        used = recentMean(axis)?.first,
    )
    BaselineKind.STEP_MOMENTUM -> ForecastEvidence.StepMomentum(
        startingRaw = axis.startingRaw,
        startingOrigin = axis.startingOrigin,
        incomingDelta = axis.incomingDelta,
        deltaOrigin = axis.deltaOrigin,
    )
}

/**
 * Day mean when one was supplied, otherwise week mean. The two windows are not
 * averaged. A cleared day mean does not fall through to a value that is no
 * longer on the input.
 */
private fun recentMean(axis: CausalAxisReading): Pair<RecentMeanChoice, Double>? {
    axis.dayMean?.let { return RecentMeanChoice.TRAILING_DAY to it }
    axis.weekMean?.let { return RecentMeanChoice.TRAILING_WEEK to it }
    return null
}

private fun aggregate(attempts: List<WalkForwardAttempt>): WalkForwardMetrics {
    val axes = CheckInAnalysisDatasetV2.CORE_AXIS_IDS
    val scores = BaselineKind.entries.flatMap { baseline ->
        axes.map { axisId -> score(attempts, baseline, axisId) }
    }
    val support = axes.map { axisId -> commonSupport(attempts, axisId) }
    return WalkForwardMetrics(scores, support)
}

private fun score(
    attempts: List<WalkForwardAttempt>,
    baseline: BaselineKind,
    axisId: String,
): AxisBaselineScore {
    val records = attempts.map { attempt -> attempt.prediction(baseline, axisId) }
    val errors = records.mapNotNull { it.residual }
    val distribution = populationSummary(errors)
    return AxisBaselineScore(
        baseline = baseline,
        axisId = axisId,
        eligibleCount = attempts.size,
        targetObservedCount = attempts.count { it.axisTarget.reading(axisId).raw != null },
        predictionAvailableCount = records.count { it.forecast != null },
        predictionCount = errors.size,
        residualDistribution = distribution,
        bias = distribution?.mean,
        mae = meanOf(errors) { abs(it) },
        rmse = rootMeanSquare(errors),
    )
}

private fun commonSupport(
    attempts: List<WalkForwardAttempt>,
    axisId: String,
): CommonSupportComparison {
    val shared = attempts.filter { attempt ->
        attempt.axisTarget.reading(axisId).raw != null &&
            BaselineKind.entries.all { baseline ->
                attempt.prediction(baseline, axisId).forecast != null
            }
    }
    val baselines = BaselineKind.entries.map { baseline ->
        val errors = shared.map { attempt ->
            checkNotNull(attempt.prediction(baseline, axisId).residual)
        }
        CommonSupportBaseline(
            baseline = baseline,
            bias = meanOf(errors) { it },
            mae = meanOf(errors) { abs(it) },
            rmse = rootMeanSquare(errors),
        )
    }
    return CommonSupportComparison(
        axisId = axisId,
        count = shared.size,
        baselines = baselines,
    )
}

private fun WalkForwardAttempt.prediction(baseline: BaselineKind, axisId: String): AxisPredictionRecord {
    return predictions.single { it.baseline == baseline && it.axisId == axisId }
}

internal fun observedAxisTarget(state: StateObservationV2): ObservedAxisTarget {
    return ObservedAxisTarget(
        state.core.inOrder().map { (axisId, reading) -> ObservedAxisReading(axisId, reading?.raw) },
    )
}

private fun observedOrigin(present: Boolean): EvidenceOrigin {
    return if (present) EvidenceOrigin.OBSERVED else EvidenceOrigin.UNOBSERVED
}

private fun originMatches(present: Boolean, origin: EvidenceOrigin): Boolean = when (origin) {
    EvidenceOrigin.OBSERVED, EvidenceOrigin.OVERRIDDEN -> present
    EvidenceOrigin.UNOBSERVED, EvidenceOrigin.CLEARED -> !present
}

private fun applyReading(
    change: SandboxChange,
    value: Int?,
    origin: EvidenceOrigin,
): Pair<Int?, EvidenceOrigin> = when (change) {
    SandboxChange.Keep -> value to origin
    SandboxChange.Clear -> null to EvidenceOrigin.CLEARED
    is SandboxChange.Reading -> change.raw to EvidenceOrigin.OVERRIDDEN
    is SandboxChange.Delta, is SandboxChange.Mean ->
        throw IllegalArgumentException("A starting axis takes a 0–10 reading")
}

private fun applyDelta(
    change: SandboxChange,
    value: Int?,
    origin: EvidenceOrigin,
): Pair<Int?, EvidenceOrigin> = when (change) {
    SandboxChange.Keep -> value to origin
    SandboxChange.Clear -> null to EvidenceOrigin.CLEARED
    is SandboxChange.Delta -> change.delta to EvidenceOrigin.OVERRIDDEN
    is SandboxChange.Reading, is SandboxChange.Mean ->
        throw IllegalArgumentException("An incoming delta takes a delta override")
}

private fun applyMean(
    change: SandboxChange,
    value: Double?,
    origin: EvidenceOrigin,
): Pair<Double?, EvidenceOrigin> = when (change) {
    SandboxChange.Keep -> value to origin
    SandboxChange.Clear -> null to EvidenceOrigin.CLEARED
    is SandboxChange.Mean -> change.mean to EvidenceOrigin.OVERRIDDEN
    is SandboxChange.Reading, is SandboxChange.Delta ->
        throw IllegalArgumentException("A trailing mean takes a mean override")
}

private fun meanOf(values: List<Double>, transform: (Double) -> Double): Double? {
    if (values.isEmpty()) return null
    return values.sumOf(transform) / values.size
}

private fun rootMeanSquare(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    return sqrt(values.sumOf { it * it } / values.size)
}
