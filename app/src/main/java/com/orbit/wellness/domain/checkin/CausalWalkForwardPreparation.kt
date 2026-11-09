package com.orbit.wellness.domain.checkin

import java.time.ZoneId

/**
 * Honest fold inputs for a later learner. Protocol `causal-walk-forward-preparation-v1`.
 *
 * Each held-out example may learn its feature schema only from earlier examples
 * whose outcome ended strictly before its forecast origin. The origin is the
 * example's anchor time. An ending time equal to that origin is excluded, and
 * an earlier anchor is not enough when the outcome ends at or after the origin.
 * A fold never trains on itself. No coefficient is fit.
 *
 * Membership follows stored event time. Edit and availability timestamps are
 * not recorded, so strict as-known availability stays false.
 *
 * One schema serves every axis in the fold. Target missingness changes only
 * the per-axis supervised support. State folds encode the state input.
 * Conditional folds encode that input plus the recorded scenario and stay
 * retrospective. Sleep, wearable, and other physiology inputs are not read.
 */
const val CAUSAL_WALK_FORWARD_PREPARATION_PROTOCOL = "causal-walk-forward-preparation-v1"

enum class CausalPreparationKind {
    STATE,
    CONDITIONAL,
}

enum class CausalCutoff {
    ENDING_STRICTLY_BEFORE_ORIGIN,
}

enum class CausalFoldGap {
    NO_FORECAST_ORIGIN,
    NO_PRIOR_INPUT,
}

enum class CausalPreparedRole {
    VALUE,
    MISSING,
    CONFLICTING,
    UNAVAILABLE,
    UNKNOWN,
}

data class CausalAxisSupervision(
    val axisId: String,
    val observedTargetCount: Int,
) {
    init {
        require(observedTargetCount >= 0)
    }
}

data class CausalPreparedColumn(
    val index: Int,
    val baseIndex: Int,
    val name: String,
    val role: CausalPreparedRole,
    val value: Double,
) {
    init {
        require(index >= 0 && baseIndex >= 0)
        require(value.isFinite())
        if (role != CausalPreparedRole.VALUE) {
            require(value == 0.0 || value == 1.0) { "A status mask stays binary" }
        }
    }
}

/**
 * The encoded base vector with a fixed mask beside every feature.
 * [statuses] stays attached for audit. Nonvarying columns are kept.
 */
data class CausalPreparedInput(
    val schema: CausalFeatureSchema,
    val statuses: List<CausalFeatureStatus>,
    val columns: List<CausalPreparedColumn>,
) {
    init {
        require(statuses.size == schema.features.size)
        require(columns.size == schema.features.size * CausalPreparedRole.entries.size)
        schema.features.indices.forEach { base ->
            val group = columns.drop(base * CausalPreparedRole.entries.size).take(CausalPreparedRole.entries.size)
            require(group.map { it.role } == CausalPreparedRole.entries)
            require(group.all { it.baseIndex == base })
            val masks = group.drop(1).map { it.value }
            val status = statuses[base]
            require(masks.all { it == 0.0 } == (status == CausalFeatureStatus.OBSERVED))
            if (status != CausalFeatureStatus.OBSERVED) {
                require(group.first().value == 0.0) { "A non-observed feature keeps the centered zero" }
                val active = when (status) {
                    CausalFeatureStatus.MISSING -> 0
                    CausalFeatureStatus.CONFLICTING -> 1
                    CausalFeatureStatus.UNAVAILABLE -> 2
                    CausalFeatureStatus.UNKNOWN -> 3
                    CausalFeatureStatus.OBSERVED -> error("An observed status has no mask")
                }
                require(masks[active] == 1.0)
                require(masks.count { it == 1.0 } == 1)
            }
        }
    }
}

data class CausalWalkForwardFold(
    val identity: ExampleIdentity,
    val kind: CausalPreparationKind,
    val cutoff: CausalCutoff,
    val forecastOrigin: Long?,
    val qualifyingPriorInputIds: List<String>,
    val gap: CausalFoldGap?,
    val schema: CausalFeatureSchema?,
    val prepared: CausalPreparedInput?,
    val targets: CausalAxisTargetEncoding,
    val axisSupport: List<CausalAxisSupervision>,
    val oovActivations: List<String>,
    val provenance: ScenarioProvenance?,
    val retrospective: Boolean,
    val availabilityMode: ObservationAvailabilityMode,
    val strictAsKnownAvailability: Boolean,
    val predictive: Boolean,
    val updatesModel: Boolean,
    val suitableForRecommendation: Boolean,
) {
    init {
        require(cutoff == CausalCutoff.ENDING_STRICTLY_BEFORE_ORIGIN)
        require(forecastOrigin == identity.anchorAt)
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION)
        require(!strictAsKnownAvailability)
        require(!predictive && !updatesModel && !suitableForRecommendation)
        require(qualifyingPriorInputIds == qualifyingPriorInputIds.distinct().sorted())
        require(axisSupport.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require((gap == null) == (schema != null && prepared != null))
        require(gap != CausalFoldGap.NO_PRIOR_INPUT || forecastOrigin != null)
        require(gap != CausalFoldGap.NO_FORECAST_ORIGIN || forecastOrigin == null)
        require(gap == null || qualifyingPriorInputIds.isEmpty())
        require(prepared == null || prepared.schema == schema)
        when (kind) {
            CausalPreparationKind.STATE -> require(provenance == null && !retrospective)
            CausalPreparationKind.CONDITIONAL -> {
                require(provenance == ScenarioProvenance.RECORDED_REALIZED && retrospective)
            }
        }
    }
}

data class CausalWalkForwardPreparation(
    val protocol: String = CAUSAL_WALK_FORWARD_PREPARATION_PROTOCOL,
    val cutoff: CausalCutoff = CausalCutoff.ENDING_STRICTLY_BEFORE_ORIGIN,
    val availabilityMode: ObservationAvailabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
    val strictAsKnownAvailability: Boolean = false,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val stateFolds: List<CausalWalkForwardFold>,
    val conditionalFolds: List<CausalWalkForwardFold>,
) {
    init {
        require(protocol == CAUSAL_WALK_FORWARD_PREPARATION_PROTOCOL)
        require(cutoff == CausalCutoff.ENDING_STRICTLY_BEFORE_ORIGIN)
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION)
        require(!strictAsKnownAvailability)
        require(!predictive && !updatesModel && !suitableForRecommendation)
        require(stateFolds.map { it.identity } == conditionalFolds.map { it.identity })
        val order = stateFolds.map { it.identity.endingAt to it.identity.transitionId }
        require(order == order.sortedWith(compareBy({ it.first }, { it.second })))
        stateFolds.zip(conditionalFolds).forEach { (state, conditional) ->
            require(state.kind == CausalPreparationKind.STATE)
            require(conditional.kind == CausalPreparationKind.CONDITIONAL)
            require(state.qualifyingPriorInputIds == conditional.qualifyingPriorInputIds)
            require(state.gap == conditional.gap)
            require(state.axisSupport == conditional.axisSupport)
            require(state.targets == conditional.targets)
            require(state.forecastOrigin == conditional.forecastOrigin)
        }
    }
}

fun projectCausalWalkForwardPreparation(
    stateExamples: List<StateForecastExample>,
    conditionalExamples: List<ConditionalTransitionExample>,
    zone: ZoneId,
): CausalWalkForwardPreparation {
    val state = stateExamples.sortedWith(compareBy({ it.identity.endingAt }, { it.identity.transitionId }))
    val conditional = conditionalExamples.sortedWith(compareBy({ it.identity.endingAt }, { it.identity.transitionId }))
    require(state.map { it.identity } == conditional.map { it.identity }) {
        "State and conditional folds share one example identity"
    }
    require(state.map { it.identity.transitionId }.distinct().size == state.size) {
        "Walk-forward folds keep distinct transition ids"
    }
    val pairs = state.zip(conditional)
    pairs.forEach { (stateExample, conditionalExample) ->
        require(stateExample.target == conditionalExample.target)
        require(stateExample.targetConflicts == conditionalExample.targetConflicts)
        require(stateExample.input == conditionalExample.input)
        require(conditionalExample.scenario.provenance == ScenarioProvenance.RECORDED_REALIZED)
    }
    val stateFolds = mutableListOf<CausalWalkForwardFold>()
    val conditionalFolds = mutableListOf<CausalWalkForwardFold>()
    pairs.forEach { held ->
        val origin = held.first.identity.anchorAt
        val priors = if (origin == null) {
            emptyList()
        } else {
            pairs.filter { prior ->
                prior.first.identity.transitionId != held.first.identity.transitionId &&
                    prior.first.available &&
                    prior.first.input != null &&
                    prior.first.identity.endingAt < origin
            }
        }
        val priorIds = priors.map { it.first.identity.transitionId }.sorted()
        val gap = when {
            origin == null -> CausalFoldGap.NO_FORECAST_ORIGIN
            priors.isEmpty() -> CausalFoldGap.NO_PRIOR_INPUT
            else -> null
        }
        val targets = encodeCausalAxisTargets(held.first.target, held.first.targetConflicts)
        val support = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            val count = priors.count { prior ->
                encodeCausalAxisTargets(prior.first.target, prior.first.targetConflicts)
                    .axes.single { it.axisId == axisId }
                    .status == CausalTargetStatus.OBSERVED
            }
            CausalAxisSupervision(axisId, count)
        }
        val statePrepared = if (gap == null) {
            prepareFoldInput(priors, held, zone, checkNotNull(origin), conditional = false)
        } else {
            null
        }
        val conditionalPrepared = if (gap == null) {
            prepareFoldInput(priors, held, zone, checkNotNull(origin), conditional = true)
        } else {
            null
        }
        stateFolds += fold(
            identity = held.first.identity,
            kind = CausalPreparationKind.STATE,
            origin = origin,
            priorIds = priorIds,
            gap = gap,
            prepared = statePrepared,
            targets = targets,
            support = support,
            provenance = null,
            retrospective = false,
        )
        conditionalFolds += fold(
            identity = held.second.identity,
            kind = CausalPreparationKind.CONDITIONAL,
            origin = origin,
            priorIds = priorIds,
            gap = gap,
            prepared = conditionalPrepared,
            targets = targets,
            support = support,
            provenance = ScenarioProvenance.RECORDED_REALIZED,
            retrospective = true,
        )
    }
    return CausalWalkForwardPreparation(
        stateFolds = stateFolds,
        conditionalFolds = conditionalFolds,
    )
}

fun prepareCausalLearnerVector(
    encoded: CausalEncodedVector,
    schema: CausalFeatureSchema,
): CausalPreparedInput {
    require(encoded.values.size == schema.features.size)
    require(encoded.statuses.size == schema.features.size)
    val columns = mutableListOf<CausalPreparedColumn>()
    schema.features.forEachIndexed { base, feature ->
        val status = encoded.statuses[base]
        columns += CausalPreparedColumn(
            index = columns.size,
            baseIndex = base,
            name = feature.name,
            role = CausalPreparedRole.VALUE,
            value = encoded.values[base],
        )
        listOf(
            CausalPreparedRole.MISSING to CausalFeatureStatus.MISSING,
            CausalPreparedRole.CONFLICTING to CausalFeatureStatus.CONFLICTING,
            CausalPreparedRole.UNAVAILABLE to CausalFeatureStatus.UNAVAILABLE,
            CausalPreparedRole.UNKNOWN to CausalFeatureStatus.UNKNOWN,
        ).forEach { (role, matched) ->
            columns += CausalPreparedColumn(
                index = columns.size,
                baseIndex = base,
                name = "${feature.name}.${role.name.lowercase()}",
                role = role,
                value = if (status == matched) 1.0 else 0.0,
            )
        }
    }
    return CausalPreparedInput(schema, encoded.statuses, columns)
}

private fun prepareFoldInput(
    priors: List<Pair<StateForecastExample, ConditionalTransitionExample>>,
    held: Pair<StateForecastExample, ConditionalTransitionExample>,
    zone: ZoneId,
    origin: Long,
    conditional: Boolean,
): CausalPreparedInput {
    val cutoff = "ending-strictly-before-$origin"
    val heldInput = checkNotNull(held.first.input)
    val encoded = if (conditional) {
        val rows = priors.map { prior ->
            CausalConditionalTrainingRow(
                prior.first.identity.transitionId,
                checkNotNull(prior.first.input),
                prior.second.scenario,
            )
        }
        val schema = fitCausalConditionalSchema(rows, zone, cutoff)
        encodeCausalConditional(heldInput, held.second.scenario, schema, zone)
            .let { prepareCausalLearnerVector(it, schema) }
    } else {
        val rows = priors.map { prior ->
            CausalStateTrainingRow(prior.first.identity.transitionId, checkNotNull(prior.first.input))
        }
        val schema = fitCausalStateSchema(rows, zone, cutoff)
        prepareCausalLearnerVector(encodeCausalState(heldInput, schema, zone), schema)
    }
    return encoded
}

private fun fold(
    identity: ExampleIdentity,
    kind: CausalPreparationKind,
    origin: Long?,
    priorIds: List<String>,
    gap: CausalFoldGap?,
    prepared: CausalPreparedInput?,
    targets: CausalAxisTargetEncoding,
    support: List<CausalAxisSupervision>,
    provenance: ScenarioProvenance?,
    retrospective: Boolean,
): CausalWalkForwardFold {
    return CausalWalkForwardFold(
        identity = identity,
        kind = kind,
        cutoff = CausalCutoff.ENDING_STRICTLY_BEFORE_ORIGIN,
        forecastOrigin = origin,
        qualifyingPriorInputIds = priorIds,
        gap = gap,
        schema = prepared?.schema,
        prepared = prepared,
        targets = targets,
        axisSupport = support,
        oovActivations = prepared?.let(::oovActivations).orEmpty(),
        provenance = provenance,
        retrospective = retrospective,
        availabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
        strictAsKnownAvailability = false,
        predictive = false,
        updatesModel = false,
        suitableForRecommendation = false,
    )
}

private fun oovActivations(prepared: CausalPreparedInput): List<String> {
    return prepared.schema.features.mapIndexedNotNull { index, feature ->
        val value = prepared.columns.single { it.baseIndex == index && it.role == CausalPreparedRole.VALUE }
        if (prepared.statuses[index] != CausalFeatureStatus.OBSERVED || value.value != 1.0) {
            return@mapIndexedNotNull null
        }
        when (val identity = feature.identity) {
            is CausalFeatureIdentity.BehaviorOov -> identity.kind.name.lowercase()
            CausalFeatureIdentity.UsageAmountOov -> "usage-amount"
            is CausalFeatureIdentity.FocalTrigger -> if (identity.token == null) "trigger" else null
            is CausalFeatureIdentity.FocalEmotion -> if (identity.token == null) "emotion" else null
            else -> null
        }
    }
}
