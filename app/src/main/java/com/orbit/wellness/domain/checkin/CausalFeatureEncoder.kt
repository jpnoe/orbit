package com.orbit.wellness.domain.checkin

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Named features for a later forecast gate. Protocol `causal-feature-encoder-v1`.
 *
 * Stage one projects raw channels from a [StateForecastInput] and, optionally,
 * one sanitized [TransitionScenario]. Stage two fits a schema on an explicit
 * training list of those inputs. Stage three transforms inputs with that schema.
 * Nothing here fits every example it can see.
 *
 * The state input is the only state-side source. Targets, residuals, outgoing
 * intervals, ending emotion, and retrospective coordinates are not read.
 * [encodeCausalAxisTargets] is a separate head. Emotion targets, latent targets,
 * and wearable physiology are named in [CausalDeferredHead] and are not encoded.
 * Historical recording zones are not stored, so clock channels are a read-time
 * reconstruction in the caller-supplied [ZoneId]. Time since wake is not invented.
 *
 * A recorded zero stays an observed zero. Missing, conflicting, unavailable,
 * and unknown stay distinct statuses. An unavailable slope is not stored as
 * slope zero. Reviewed absence is observed zero on a category channel; unknown
 * keeps [CausalFeatureStatus.UNKNOWN]. Insufficient support and negligible
 * variation are separate flags. Path and background experts stay in separate
 * families. Scenario provenance is audit metadata on the raw projection and is
 * not a column. Recorded and sandbox scenarios share the scenario columns.
 *
 * Omitted on purpose: trajectory `currentRaw` (focal coverage already keeps
 * conflict), expert minima and maxima, path-break tokens, occurrence keys,
 * notes, raw timestamps, scenario start clock (the anchor clock is already
 * local-clock context; the scenario adds duration and the horizon end),
 * historical emotion tokens (the observation context stores counts only),
 * and any quantity that would add incompatible units together.
 */
const val CAUSAL_FEATURE_ENCODER_PROTOCOL = "causal-feature-encoder-v1"

enum class CausalSchemaMode {
    STATE,
    CONDITIONAL,
}

enum class CausalFeatureFamily {
    CURRENT_STATE,
    TRAJECTORY_CONTEXT,
    CONTINUOUS_PATH_EXPERT,
    SAMPLED_BACKGROUND_EXPERT,
    OBSERVATION_PROCESS,
    LOCAL_CLOCK,
    SCENARIO_CATEGORY,
    SCENARIO_EXPOSURE,
    SCENARIO_HORIZON,
}

enum class CausalInformationBoundary {
    ANCHOR_KNOWN,
    SCENARIO,
}

enum class CausalFeatureStatus {
    OBSERVED,
    MISSING,
    CONFLICTING,
    UNAVAILABLE,
    UNKNOWN,
}

enum class CausalFeatureScaling {
    BINARY,
    STANDARDIZE,
}

enum class CausalClockReconstruction {
    READ_TIME_ZONE,
}

enum class CausalDeferredHead {
    WEARABLE_PHYSIOLOGY,
    EMOTION_TARGET,
    LATENT_TARGET,
}

enum class CausalTargetStatus {
    OBSERVED,
    MISSING,
    CONFLICTING,
}

sealed interface CausalFeatureIdentity {
    data class Structural(val id: String) : CausalFeatureIdentity
    /** Null [token] is the out-of-vocabulary column. */
    data class FocalTrigger(val token: String?) : CausalFeatureIdentity
    data class FocalEmotion(val token: String?) : CausalFeatureIdentity
    data class HistoricalTrigger(
        val sampling: ObservationSamplingId,
        val token: String?,
    ) : CausalFeatureIdentity
    data class BehaviorPresent(val identity: BehaviorIdentity) : CausalFeatureIdentity
    data object BehaviorOov : CausalFeatureIdentity
    data class WorkoutMinutes(val identity: BehaviorIdentity) : CausalFeatureIdentity
    data class UsageMissing(val identity: BehaviorIdentity) : CausalFeatureIdentity
    data class UsageUnparsed(val identity: BehaviorIdentity) : CausalFeatureIdentity
    data class UsageAmount(val identity: BehaviorIdentity, val unit: String?) : CausalFeatureIdentity
    data object UsageAmountOov : CausalFeatureIdentity
}

data class CausalRawFeature(
    val id: String,
    val name: String,
    val family: CausalFeatureFamily,
    val unit: String,
    val interpretation: String,
    val boundary: CausalInformationBoundary,
    val scaling: CausalFeatureScaling,
    val value: Double?,
    val status: CausalFeatureStatus,
) {
    init {
        require((status == CausalFeatureStatus.OBSERVED) == (value != null)) {
            "An observed raw feature carries its value, including zero"
        }
        if (scaling == CausalFeatureScaling.BINARY && value != null) {
            require(value == 0.0 || value == 1.0) { "A binary feature is zero or one" }
        }
    }
}

data class CausalCategoryView(
    val activity: CategoryKnowledge,
    val workout: CategoryKnowledge,
    val diet: CategoryKnowledge,
    val usage: CategoryKnowledge,
)

data class CausalHistoricalTriggers(
    val sampling: ObservationSamplingId,
    val counts: List<TriggerCount>,
)

/**
 * Raw channels plus the categorical material a later fit turns into columns.
 * [scenarioProvenance] is audit only.
 */
data class CausalRawProjection(
    val zoneId: String,
    val clockReconstruction: CausalClockReconstruction = CausalClockReconstruction.READ_TIME_ZONE,
    val scenarioProvenance: ScenarioProvenance?,
    val channels: List<CausalRawFeature>,
    val focalTrigger: String,
    val focalEmotion: String?,
    val exposures: List<BehaviorExposure>,
    val categories: CausalCategoryView?,
    val historicalTriggers: List<CausalHistoricalTriggers>,
) {
    init {
        require(clockReconstruction == CausalClockReconstruction.READ_TIME_ZONE)
        require(channels.map { it.id } == channels.map { it.id }.distinct())
        require((scenarioProvenance == null) == (categories == null))
        val scenarioFamilies = setOf(
            CausalFeatureFamily.SCENARIO_CATEGORY,
            CausalFeatureFamily.SCENARIO_HORIZON,
        )
        val present = channels.map { it.family }.toSet()
        if (scenarioProvenance == null) {
            require(present.none { it == CausalFeatureFamily.SCENARIO_CATEGORY || it == CausalFeatureFamily.SCENARIO_HORIZON || it == CausalFeatureFamily.SCENARIO_EXPOSURE })
        } else {
            require(scenarioFamilies.all { it in present })
            require(CausalFeatureFamily.SCENARIO_EXPOSURE !in present) {
                "Behavior columns are fit from training exposures, not stored as raw structure"
            }
        }
    }
}

data class CausalStateTrainingRow(
    val inputId: String,
    val input: StateForecastInput,
)

data class CausalConditionalTrainingRow(
    val inputId: String,
    val input: StateForecastInput,
    val scenario: TransitionScenario,
)

data class CausalFeatureDefinition(
    val index: Int,
    val name: String,
    val family: CausalFeatureFamily,
    val identity: CausalFeatureIdentity,
    val unit: String,
    val interpretation: String,
    val boundary: CausalInformationBoundary,
    val scaling: CausalFeatureScaling,
)

data class CausalFeatureSupport(
    val index: Int,
    val observedCount: Int,
    val center: Double,
    val scale: Double,
    val nonvarying: Boolean,
) {
    init {
        require(observedCount >= 0)
        require(center.isFinite() && scale.isFinite() && scale >= 0.0)
    }
}

data class CausalFeatureSchema(
    val protocol: String = CAUSAL_FEATURE_ENCODER_PROTOCOL,
    val mode: CausalSchemaMode,
    val zoneId: String,
    val clockReconstruction: CausalClockReconstruction = CausalClockReconstruction.READ_TIME_ZONE,
    val cutoffLabel: String,
    val trainingInputIds: List<String>,
    val features: List<CausalFeatureDefinition>,
    val support: List<CausalFeatureSupport>,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val deferred: List<CausalDeferredHead> = CausalDeferredHead.entries,
) {
    init {
        require(protocol == CAUSAL_FEATURE_ENCODER_PROTOCOL)
        require(clockReconstruction == CausalClockReconstruction.READ_TIME_ZONE)
        require(!predictive && !updatesModel && !suitableForRecommendation) {
            "A causal feature schema is not a fitted forecast"
        }
        require(deferred == CausalDeferredHead.entries)
        require(trainingInputIds.isNotEmpty())
        require(trainingInputIds == trainingInputIds.distinct().sorted())
        features.forEachIndexed { index, feature ->
            require(feature.index == index)
        }
        require(features.map { it.name }.distinct().size == features.size)
        require(features.map { it.identity }.distinct().size == features.size)
        require(support.map { it.index } == features.indices.toList())
        var lastFamily = 0
        features.forEach { feature ->
            val ordinal = feature.family.ordinal
            require(ordinal >= lastFamily) { "Feature families stay in manifest order" }
            lastFamily = ordinal
        }
        val families = features.map { it.family }.toSet()
        val scenario = setOf(
            CausalFeatureFamily.SCENARIO_CATEGORY,
            CausalFeatureFamily.SCENARIO_EXPOSURE,
            CausalFeatureFamily.SCENARIO_HORIZON,
        )
        when (mode) {
            CausalSchemaMode.STATE -> require(families.none { it in scenario })
            CausalSchemaMode.CONDITIONAL -> require(scenario.all { it in families })
        }
    }

    fun definition(identity: CausalFeatureIdentity): CausalFeatureDefinition = features.single { it.identity == identity }
}

data class CausalEncodedVector(
    val values: List<Double>,
    val statuses: List<CausalFeatureStatus>,
) {
    init {
        require(values.size == statuses.size)
        require(values.all { it.isFinite() })
    }
}

data class CausalAxisTarget(
    val axisId: String,
    val raw: Int?,
    val status: CausalTargetStatus,
) {
    init {
        require((status == CausalTargetStatus.OBSERVED) == (raw != null))
        require(raw == null || raw in 0..10)
    }
}

data class CausalAxisTargetEncoding(
    val axes: List<CausalAxisTarget>,
) {
    init {
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
    }
}

fun projectCausalRawFeatures(
    input: StateForecastInput,
    zone: ZoneId,
    scenario: TransitionScenario? = null,
): CausalRawProjection {
    val channels = stateChannels(input, zone)
    val categories = scenario?.let {
        channels += scenarioChannels(input, it, zone)
        CausalCategoryView(it.activity, it.workout, it.diet, it.usage)
    }
    return CausalRawProjection(
        zoneId = zone.id,
        scenarioProvenance = scenario?.provenance,
        channels = channels,
        focalTrigger = input.observation.trigger,
        focalEmotion = input.observation.emotion.takeIf { it.isNotEmpty() },
        exposures = scenario?.exposures.orEmpty(),
        categories = categories,
        historicalTriggers = input.observation.samplings.map { sampling ->
            CausalHistoricalTriggers(sampling.sampling, sampling.triggers)
        },
    )
}

fun fitCausalStateSchema(
    training: List<CausalStateTrainingRow>,
    zone: ZoneId,
    cutoffLabel: String = "explicit-training-rows",
): CausalFeatureSchema {
    require(training.isNotEmpty()) { "State schema fitting needs an explicit training list" }
    val rows = training.map { row ->
        row.inputId to projectCausalRawFeatures(row.input, zone)
    }
    return fitSchema(CausalSchemaMode.STATE, rows, zone, cutoffLabel)
}

fun fitCausalConditionalSchema(
    training: List<CausalConditionalTrainingRow>,
    zone: ZoneId,
    cutoffLabel: String = "explicit-training-rows",
): CausalFeatureSchema {
    require(training.isNotEmpty()) { "Conditional schema fitting needs an explicit training list" }
    val rows = training.map { row ->
        row.inputId to projectCausalRawFeatures(row.input, zone, row.scenario)
    }
    return fitSchema(CausalSchemaMode.CONDITIONAL, rows, zone, cutoffLabel)
}

fun encodeCausalState(
    input: StateForecastInput,
    schema: CausalFeatureSchema,
    zone: ZoneId,
): CausalEncodedVector {
    require(schema.mode == CausalSchemaMode.STATE) { "A state vector uses a state schema" }
    return encode(projectCausalRawFeatures(input, zone), schema, zone)
}

fun encodeCausalConditional(
    input: StateForecastInput,
    scenario: TransitionScenario,
    schema: CausalFeatureSchema,
    zone: ZoneId,
): CausalEncodedVector {
    require(schema.mode == CausalSchemaMode.CONDITIONAL) { "A conditional vector uses a conditional schema" }
    return encode(projectCausalRawFeatures(input, zone, scenario), schema, zone)
}

fun encodeCausalAxisTargets(
    target: ObservedAxisTarget,
    conflicts: List<String>,
): CausalAxisTargetEncoding {
    require(conflicts == conflicts.distinct())
    require(conflicts.all { it in CheckInAnalysisDatasetV2.CORE_AXIS_IDS })
    val axes = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        val raw = target.reading(axisId).raw
        val status = when {
            axisId in conflicts -> CausalTargetStatus.CONFLICTING
            raw == null -> CausalTargetStatus.MISSING
            else -> CausalTargetStatus.OBSERVED
        }
        CausalAxisTarget(axisId, if (status == CausalTargetStatus.OBSERVED) raw else null, status)
    }
    return CausalAxisTargetEncoding(axes)
}

private fun fitSchema(
    mode: CausalSchemaMode,
    rows: List<Pair<String, CausalRawProjection>>,
    zone: ZoneId,
    cutoffLabel: String,
): CausalFeatureSchema {
    val prototype = rows.first().second.channels.map { it.id to it.scaling }
    require(rows.all { it.second.channels.map { channel -> channel.id to channel.scaling } == prototype }) {
        "Training rows do not share one structural manifest"
    }
    require(rows.all { it.second.zoneId == zone.id })
    val ids = rows.map { it.first }
    require(ids.size == ids.toSet().size) { "Training input ids stay distinct" }
    val projections = rows.map { it.second }
    val triggers = triggerVocabulary(projections)
    val emotions = projections.mapNotNull { it.focalEmotion }.toSortedSet().toList()
    val behaviors = projections.flatMap { it.exposures }.map { it.identity }.distinct().sortedWith(identityOrder)
    val quantities = usageQuantityKeys(projections)
    val features = mutableListOf<CausalFeatureDefinition>()
    fun add(
        name: String,
        family: CausalFeatureFamily,
        identity: CausalFeatureIdentity,
        unit: String,
        interpretation: String,
        boundary: CausalInformationBoundary,
        scaling: CausalFeatureScaling,
    ) {
        features += CausalFeatureDefinition(
            index = features.size,
            name = name,
            family = family,
            identity = identity,
            unit = unit,
            interpretation = interpretation,
            boundary = boundary,
            scaling = scaling,
        )
    }
    projections.first().channels.groupBy { it.family }.forEach { (family, channels) ->
        if (family == CausalFeatureFamily.CURRENT_STATE) {
            channels.forEach { channel -> addStructural(features, channel) }
            triggers.forEach { token ->
                add(
                    "current.trigger.token.${diagnosticToken(token)}",
                    family,
                    CausalFeatureIdentity.FocalTrigger(token),
                    "indicator",
                    "vocabulary",
                    CausalInformationBoundary.ANCHOR_KNOWN,
                    CausalFeatureScaling.BINARY,
                )
            }
            add(
                "current.trigger.oov",
                family,
                CausalFeatureIdentity.FocalTrigger(null),
                "indicator",
                "out-of-vocabulary",
                CausalInformationBoundary.ANCHOR_KNOWN,
                CausalFeatureScaling.BINARY,
            )
            emotions.forEach { token ->
                add(
                    "current.emotion.token.${diagnosticToken(token)}",
                    family,
                    CausalFeatureIdentity.FocalEmotion(token),
                    "indicator",
                    "vocabulary",
                    CausalInformationBoundary.ANCHOR_KNOWN,
                    CausalFeatureScaling.BINARY,
                )
            }
            add(
                "current.emotion.oov",
                family,
                CausalFeatureIdentity.FocalEmotion(null),
                "indicator",
                "out-of-vocabulary",
                CausalInformationBoundary.ANCHOR_KNOWN,
                CausalFeatureScaling.BINARY,
            )
        }
    }
    listOf(
        CausalFeatureFamily.TRAJECTORY_CONTEXT,
        CausalFeatureFamily.CONTINUOUS_PATH_EXPERT,
        CausalFeatureFamily.SAMPLED_BACKGROUND_EXPERT,
    ).forEach { family ->
        projections.first().channels.filter { it.family == family }.forEach { addStructural(features, it) }
    }
    projections.first().channels.filter { it.family == CausalFeatureFamily.OBSERVATION_PROCESS }.forEach {
        addStructural(features, it)
    }
    ObservationSamplingId.entries.forEach { sampling ->
        triggers.forEach { token ->
            add(
                "observation.${samplingName(sampling)}.trigger.token.${diagnosticToken(token)}",
                CausalFeatureFamily.OBSERVATION_PROCESS,
                CausalFeatureIdentity.HistoricalTrigger(sampling, token),
                "count",
                "trigger-distribution",
                CausalInformationBoundary.ANCHOR_KNOWN,
                CausalFeatureScaling.STANDARDIZE,
            )
        }
        add(
            "observation.${samplingName(sampling)}.trigger.oov",
            CausalFeatureFamily.OBSERVATION_PROCESS,
            CausalFeatureIdentity.HistoricalTrigger(sampling, null),
            "count",
            "out-of-vocabulary",
            CausalInformationBoundary.ANCHOR_KNOWN,
            CausalFeatureScaling.STANDARDIZE,
        )
    }
    projections.first().channels.filter { it.family == CausalFeatureFamily.LOCAL_CLOCK }.forEach {
        addStructural(features, it)
    }
    if (mode == CausalSchemaMode.CONDITIONAL) {
        projections.first().channels.filter { it.family == CausalFeatureFamily.SCENARIO_CATEGORY }.forEach {
            addStructural(features, it)
        }
        behaviors.forEach { identity ->
            add(
                "scenario.behavior.present.${identityToken(identity)}",
                CausalFeatureFamily.SCENARIO_EXPOSURE,
                CausalFeatureIdentity.BehaviorPresent(identity),
                "indicator",
                "vocabulary",
                CausalInformationBoundary.SCENARIO,
                CausalFeatureScaling.BINARY,
            )
        }
        add(
            "scenario.behavior.oov",
            CausalFeatureFamily.SCENARIO_EXPOSURE,
            CausalFeatureIdentity.BehaviorOov,
            "indicator",
            "out-of-vocabulary",
            CausalInformationBoundary.SCENARIO,
            CausalFeatureScaling.BINARY,
        )
        behaviors.filter { it.kind == BehaviorKind.WORKOUT }.forEach { identity ->
            add(
                "scenario.workout.minutes.${identityToken(identity)}",
                CausalFeatureFamily.SCENARIO_EXPOSURE,
                CausalFeatureIdentity.WorkoutMinutes(identity),
                "minutes",
                "quantity",
                CausalInformationBoundary.SCENARIO,
                CausalFeatureScaling.STANDARDIZE,
            )
        }
        behaviors.filter { it.kind == BehaviorKind.USAGE }.forEach { identity ->
            add(
                "scenario.usage.missing.${identityToken(identity)}",
                CausalFeatureFamily.SCENARIO_EXPOSURE,
                CausalFeatureIdentity.UsageMissing(identity),
                "indicator",
                "quantity-status",
                CausalInformationBoundary.SCENARIO,
                CausalFeatureScaling.BINARY,
            )
            add(
                "scenario.usage.unparsed.${identityToken(identity)}",
                CausalFeatureFamily.SCENARIO_EXPOSURE,
                CausalFeatureIdentity.UsageUnparsed(identity),
                "indicator",
                "quantity-status",
                CausalInformationBoundary.SCENARIO,
                CausalFeatureScaling.BINARY,
            )
        }
        quantities.forEach { key ->
            add(
                "scenario.usage.amount.${identityToken(key.identity)}.${unitToken(key.unit)}",
                CausalFeatureFamily.SCENARIO_EXPOSURE,
                CausalFeatureIdentity.UsageAmount(key.identity, key.unit),
                key.unit ?: "unitless",
                "quantity",
                CausalInformationBoundary.SCENARIO,
                CausalFeatureScaling.STANDARDIZE,
            )
        }
        add(
            "scenario.usage.amount.oov",
            CausalFeatureFamily.SCENARIO_EXPOSURE,
            CausalFeatureIdentity.UsageAmountOov,
            "indicator",
            "out-of-vocabulary",
            CausalInformationBoundary.SCENARIO,
            CausalFeatureScaling.BINARY,
        )
        projections.first().channels.filter { it.family == CausalFeatureFamily.SCENARIO_HORIZON }.forEach {
            addStructural(features, it)
        }
    }
    val support = features.map { feature ->
        val samples = projections.map { projection ->
            resolve(
                feature.identity,
                projection,
                triggers.toSet(),
                emotions.toSet(),
                behaviors.toSet(),
                quantities.toSet(),
            )
        }
        supportOf(feature.index, samples, feature.scaling)
    }
    return CausalFeatureSchema(
        mode = mode,
        zoneId = zone.id,
        cutoffLabel = cutoffLabel,
        trainingInputIds = ids.sorted(),
        features = features,
        support = support,
    )
}

private fun addStructural(features: MutableList<CausalFeatureDefinition>, channel: CausalRawFeature) {
    features += CausalFeatureDefinition(
        index = features.size,
        name = channel.name,
        family = channel.family,
        identity = CausalFeatureIdentity.Structural(channel.id),
        unit = channel.unit,
        interpretation = channel.interpretation,
        boundary = channel.boundary,
        scaling = channel.scaling,
    )
}

private fun encode(
    projection: CausalRawProjection,
    schema: CausalFeatureSchema,
    zone: ZoneId,
): CausalEncodedVector {
    require(schema.zoneId == zone.id) { "Encoding uses the schema zone" }
    require(projection.zoneId == zone.id)
    val triggers = schema.features.mapNotNull { feature ->
        (feature.identity as? CausalFeatureIdentity.FocalTrigger)?.token
    }.toSet()
    val emotions = schema.features.mapNotNull { feature ->
        (feature.identity as? CausalFeatureIdentity.FocalEmotion)?.token
    }.toSet()
    val behaviors = schema.features.mapNotNull { feature ->
        when (val identity = feature.identity) {
            is CausalFeatureIdentity.BehaviorPresent -> identity.identity
            else -> null
        }
    }.toSet()
    val quantities = schema.features.mapNotNull { feature ->
        when (val identity = feature.identity) {
            is CausalFeatureIdentity.UsageAmount -> UsageQuantityKey(identity.identity, identity.unit)
            else -> null
        }
    }.toSet()
    val values = mutableListOf<Double>()
    val statuses = mutableListOf<CausalFeatureStatus>()
    schema.features.forEach { feature ->
        val (raw, status) = resolve(feature.identity, projection, triggers, emotions, behaviors, quantities)
        val support = schema.support[feature.index]
        val transformed = when {
            status != CausalFeatureStatus.OBSERVED || raw == null -> 0.0
            feature.scaling == CausalFeatureScaling.BINARY -> raw
            support.nonvarying -> 0.0
            else -> (raw - support.center) / support.scale
        }
        values += transformed
        statuses += status
    }
    return CausalEncodedVector(values, statuses)
}

private fun resolve(
    identity: CausalFeatureIdentity,
    projection: CausalRawProjection,
    triggers: Set<String>,
    emotions: Set<String>,
    behaviors: Set<BehaviorIdentity>,
    quantities: Set<UsageQuantityKey>,
): Pair<Double?, CausalFeatureStatus> {
    return when (identity) {
        is CausalFeatureIdentity.Structural -> {
            val channel = projection.channels.single { it.id == identity.id }
            channel.value to channel.status
        }
        is CausalFeatureIdentity.FocalTrigger -> indicator(projection.focalTrigger, identity.token, triggers)
        is CausalFeatureIdentity.FocalEmotion -> {
            val token = projection.focalEmotion
            if (token == null) 0.0 to CausalFeatureStatus.OBSERVED else indicator(token, identity.token, emotions)
        }
        is CausalFeatureIdentity.HistoricalTrigger -> historicalTrigger(projection, identity, triggers)
        is CausalFeatureIdentity.BehaviorPresent -> behaviorPresent(projection, identity.identity)
        CausalFeatureIdentity.BehaviorOov -> {
            val unseen = projection.exposures.any { it.identity !in behaviors }
            flag(unseen) to CausalFeatureStatus.OBSERVED
        }
        is CausalFeatureIdentity.WorkoutMinutes -> workoutMinutes(projection, identity.identity)
        is CausalFeatureIdentity.UsageMissing -> usageStatus(projection, identity.identity, UsageQuantityStatus.MISSING)
        is CausalFeatureIdentity.UsageUnparsed -> usageStatus(projection, identity.identity, UsageQuantityStatus.UNPARSED)
        is CausalFeatureIdentity.UsageAmount -> usageAmount(projection, identity.identity, identity.unit)
        CausalFeatureIdentity.UsageAmountOov -> {
            val unseen = projection.exposures.filterIsInstance<UsageBehavior>().any { behavior ->
                behavior.amounts.any { amount ->
                    amount.status == UsageQuantityStatus.PARSED &&
                        UsageQuantityKey(behavior.identity, amount.unit) !in quantities
                }
            }
            flag(unseen) to CausalFeatureStatus.OBSERVED
        }
    }
}

private fun indicator(actual: String, column: String?, vocabulary: Set<String>): Pair<Double?, CausalFeatureStatus> {
    val selected = if (actual in vocabulary) actual else null
    return flag(column == selected) to CausalFeatureStatus.OBSERVED
}

private fun historicalTrigger(
    projection: CausalRawProjection,
    identity: CausalFeatureIdentity.HistoricalTrigger,
    vocabulary: Set<String>,
): Pair<Double?, CausalFeatureStatus> {
    val counts = projection.historicalTriggers.single { it.sampling == identity.sampling }
        .counts
        .associate { it.trigger to it.count }
    val value = if (identity.token == null) {
        counts.filterKeys { it !in vocabulary }.values.sum()
    } else {
        counts[identity.token] ?: 0
    }
    return value.toDouble() to CausalFeatureStatus.OBSERVED
}

private fun behaviorPresent(
    projection: CausalRawProjection,
    identity: BehaviorIdentity,
): Pair<Double?, CausalFeatureStatus> {
    val categories = requireNotNull(projection.categories)
    if (knowledgeFor(identity.kind, categories) == CategoryKnowledge.UNKNOWN) {
        return null to CausalFeatureStatus.UNKNOWN
    }
    val present = projection.exposures.any { it.identity == identity }
    return flag(present) to CausalFeatureStatus.OBSERVED
}

private fun workoutMinutes(
    projection: CausalRawProjection,
    identity: BehaviorIdentity,
): Pair<Double?, CausalFeatureStatus> {
    val categories = requireNotNull(projection.categories)
    if (categories.workout == CategoryKnowledge.UNKNOWN) return null to CausalFeatureStatus.UNKNOWN
    val rows = projection.exposures.filterIsInstance<WorkoutBehavior>().filter { it.identity == identity }
    if (rows.isEmpty()) return null to CausalFeatureStatus.MISSING
    return rows.sumOf { it.minutes }.toDouble() to CausalFeatureStatus.OBSERVED
}

private fun usageStatus(
    projection: CausalRawProjection,
    identity: BehaviorIdentity,
    status: UsageQuantityStatus,
): Pair<Double?, CausalFeatureStatus> {
    val categories = requireNotNull(projection.categories)
    if (categories.usage == CategoryKnowledge.UNKNOWN) return null to CausalFeatureStatus.UNKNOWN
    val rows = projection.exposures.filterIsInstance<UsageBehavior>().filter { it.identity == identity }
    if (rows.isEmpty()) return null to CausalFeatureStatus.MISSING
    val flagged = rows.any { behavior -> behavior.amounts.any { it.status == status } }
    return flag(flagged) to CausalFeatureStatus.OBSERVED
}

private fun usageAmount(
    projection: CausalRawProjection,
    identity: BehaviorIdentity,
    unit: String?,
): Pair<Double?, CausalFeatureStatus> {
    val categories = requireNotNull(projection.categories)
    if (categories.usage == CategoryKnowledge.UNKNOWN) return null to CausalFeatureStatus.UNKNOWN
    val rows = projection.exposures.filterIsInstance<UsageBehavior>().filter { it.identity == identity }
    if (rows.isEmpty()) return null to CausalFeatureStatus.MISSING
    val parsed = rows.flatMap { it.amounts }.filter { it.status == UsageQuantityStatus.PARSED && it.unit == unit }
    if (parsed.isEmpty()) return null to CausalFeatureStatus.MISSING
    return parsed.sumOf { checkNotNull(it.numericAmount) } to CausalFeatureStatus.OBSERVED
}

private fun knowledgeFor(kind: BehaviorKind, categories: CausalCategoryView): CategoryKnowledge {
    return when (kind) {
        BehaviorKind.ACTIVITY -> categories.activity
        BehaviorKind.WORKOUT -> categories.workout
        BehaviorKind.FOOD, BehaviorKind.MEAL_SIZE -> categories.diet
        BehaviorKind.USAGE -> categories.usage
    }
}

private fun supportOf(
    index: Int,
    samples: List<Pair<Double?, CausalFeatureStatus>>,
    scaling: CausalFeatureScaling,
): CausalFeatureSupport {
    val observed = samples.mapNotNull { (value, status) ->
        if (status == CausalFeatureStatus.OBSERVED) value else null
    }
    val summary = populationSummary(observed)
    val center = summary?.mean ?: 0.0
    val scale = summary?.deviation ?: 0.0
    val nonvarying = when (scaling) {
        CausalFeatureScaling.BINARY -> observed.distinct().size < 2
        CausalFeatureScaling.STANDARDIZE -> {
            summary == null || summary.count < 2 || !deviationChangesGeometry(scale, center)
        }
    }
    return CausalFeatureSupport(index, observed.size, center, scale, nonvarying)
}

private fun stateChannels(input: StateForecastInput, zone: ZoneId): MutableList<CausalRawFeature> {
    val channels = mutableListOf<CausalRawFeature>()
    channels += currentChannels(input)
    channels += trajectoryChannels(input.stateSide)
    channels += expertChannels(input.stateSide)
    channels += observationChannels(input.observation)
    channels += clockChannels("clock", CausalFeatureFamily.LOCAL_CLOCK, input.stateSide.focal.recordedAt, zone)
    return channels
}

private fun scenarioChannels(
    input: StateForecastInput,
    scenario: TransitionScenario,
    zone: ZoneId,
): List<CausalRawFeature> {
    val channels = mutableListOf<CausalRawFeature>()
    val boundary = CausalInformationBoundary.SCENARIO
    channels += categoryChannel("scenario.category.activity", scenario.activity)
    channels += categoryChannel("scenario.category.workout", scenario.workout)
    channels += categoryChannel("scenario.category.diet", scenario.diet)
    channels += categoryChannel("scenario.category.usage", scenario.usage)
    channels += raw(
        id = "scenario.horizon.hours",
        family = CausalFeatureFamily.SCENARIO_HORIZON,
        unit = "hours",
        interpretation = "duration-or-horizon",
        boundary = boundary,
        scaling = CausalFeatureScaling.STANDARDIZE,
        value = scenario.span.millis / MILLIS_PER_HOUR,
        status = CausalFeatureStatus.OBSERVED,
    )
    val end = when (scenario.provenance) {
        ScenarioProvenance.RECORDED_REALIZED -> requireNotNull(scenario.clock).endAt
        ScenarioProvenance.SANDBOX_SPECIFIED -> {
            require(scenario.anchorKey == input.stateSide.trajectory.stateKey) {
                "A sandbox scenario uses its anchor clock"
            }
            Math.addExact(input.stateSide.focal.recordedAt, scenario.span.millis)
        }
    }
    channels += clockChannels("scenario.horizon.end", CausalFeatureFamily.SCENARIO_HORIZON, end, zone)
    return channels
}

private fun currentChannels(input: StateForecastInput): List<CausalRawFeature> {
    val channels = mutableListOf<CausalRawFeature>()
    input.observation.axes.forEach { axis ->
        val status = when (axis.status) {
            FocalAxisStatus.RECORDED -> CausalFeatureStatus.OBSERVED
            FocalAxisStatus.MISSING -> CausalFeatureStatus.MISSING
            FocalAxisStatus.CONFLICTING -> CausalFeatureStatus.CONFLICTING
        }
        channels += raw(
            id = "current.${axis.axisId}.raw",
            family = CausalFeatureFamily.CURRENT_STATE,
            unit = "points",
            interpretation = "axis-points",
            boundary = CausalInformationBoundary.ANCHOR_KNOWN,
            scaling = CausalFeatureScaling.STANDARDIZE,
            value = if (status == CausalFeatureStatus.OBSERVED) axis.raw?.toDouble() else null,
            status = status,
        )
    }
    channels += raw(
        id = "current.emotion.present",
        family = CausalFeatureFamily.CURRENT_STATE,
        unit = "indicator",
        interpretation = "binary-flag",
        boundary = CausalInformationBoundary.ANCHOR_KNOWN,
        scaling = CausalFeatureScaling.BINARY,
        value = flag(input.observation.emotionPresent),
        status = CausalFeatureStatus.OBSERVED,
    )
    return channels
}

private fun trajectoryChannels(side: CausalStateSide): List<CausalRawFeature> {
    val channels = mutableListOf<CausalRawFeature>()
    val trajectory = side.trajectory
    channels += count("trajectory.predecessor-count", trajectory.resolvedPredecessorCount.toDouble())
    channels += count("trajectory.day.states", trajectory.trailingDay.stateCount.toDouble())
    channels += count("trajectory.week.states", trajectory.trailingWeek.stateCount.toDouble())
    trajectory.axes.forEach { axis ->
        val id = axis.axisId
        channels += optionalInt("trajectory.$id.incoming-delta", "points", "delta-points", axis.incomingDelta)
        channels += optionalInt("trajectory.$id.preceding-delta", "points", "delta-points", axis.precedingDelta)
        channels += optionalInt("trajectory.$id.third-delta", "points", "delta-points", axis.thirdIncomingDelta)
        channels += optionalInt("trajectory.$id.acceleration", "points", "delta-points", axis.acceleration)
        channels += optionalInt("trajectory.$id.absolute-incoming-delta", "points", "delta-points", axis.absoluteIncomingDelta)
        channels += count("trajectory.$id.usable-predecessors", axis.usablePredecessorCount.toDouble())
        channels += count("trajectory.$id.earlier-delta-count", axis.earlierDeltaCount.toDouble())
        channels += optionalNumber("trajectory.$id.earlier-delta-mean", "points", "baseline-mean", axis.earlierDeltaBaseline?.mean)
        channels += optionalNumber(
            "trajectory.$id.earlier-delta-deviation",
            "points",
            "baseline-deviation",
            axis.earlierDeltaBaseline?.deviation,
        )
        channels += gapNumber("trajectory.$id.incoming-surprise", "standardized-points", axis.incomingDeltaSurprise, axis.surpriseGap)
        channels += gapFlag("trajectory.$id.surprise.insufficient", axis.surpriseGap, StandardizationGap.INSUFFICIENT_SUPPORT)
        channels += gapFlag("trajectory.$id.surprise.negligible", axis.surpriseGap, StandardizationGap.NEGLIGIBLE_VARIATION)
        channels += directionFlag("trajectory.$id.direction.rising", axis.direction, DeltaDirection.RISING)
        channels += directionFlag("trajectory.$id.direction.falling", axis.direction, DeltaDirection.FALLING)
        channels += directionFlag("trajectory.$id.direction.flat", axis.direction, DeltaDirection.FLAT)
        channels += optionalInt("trajectory.$id.direction-run", "count", "run-length", axis.sameDirectionRun)
        channels += optionalInt("trajectory.$id.direction-net", "points", "delta-points", axis.sameDirectionNetChange)
        channels += optionalInt("trajectory.$id.floor-distance", "points", "axis-position", axis.floorDistance)
        channels += optionalInt("trajectory.$id.ceiling-distance", "points", "axis-position", axis.ceilingDistance)
        channels += optionalFlag("trajectory.$id.at-floor", axis.atFloor)
        channels += optionalFlag("trajectory.$id.at-ceiling", axis.atCeiling)
        channels += windowChannels("trajectory.$id.day", axis.trailingDay, axis.deviationFromDay, axis.standardizedDayDeviation, axis.dayStandardizationGap)
        channels += windowChannels("trajectory.$id.week", axis.trailingWeek, axis.deviationFromWeek, axis.standardizedWeekDeviation, axis.weekStandardizationGap)
    }
    return channels
}

private fun windowChannels(
    prefix: String,
    baseline: TemporalAxisBaseline,
    delta: Double?,
    standardized: Double?,
    gap: StandardizationGap?,
): List<CausalRawFeature> {
    return listOf(
        count("$prefix.count", baseline.observedCount.toDouble()),
        optionalNumber("$prefix.mean", "points", "baseline-mean", baseline.summary?.mean),
        optionalNumber("$prefix.deviation", "points", "baseline-deviation", baseline.summary?.deviation),
        optionalNumber("$prefix.delta", "points", "baseline-delta", delta),
        gapNumber("$prefix.standardized", "standardized-points", standardized, gap),
        gapFlag("$prefix.insufficient", gap, StandardizationGap.INSUFFICIENT_SUPPORT),
        gapFlag("$prefix.negligible", gap, StandardizationGap.NEGLIGIBLE_VARIATION),
    )
}

private fun expertChannels(side: CausalStateSide): List<CausalRawFeature> {
    val channels = mutableListOf<CausalRawFeature>()
    side.pathExperts.forEach { expert ->
        val prefix = "path.${scaleName(expert.scale)}.${expert.axisId}"
        channels += expertSupport(prefix, CausalFeatureFamily.CONTINUOUS_PATH_EXPERT, expert)
        if (expert.scale == TrajectoryScaleId.IMMEDIATE_PATH) {
            channels += optionalInt(
                "$prefix.step-delta",
                "points",
                "delta-points",
                expert.stepDelta,
                CausalFeatureFamily.CONTINUOUS_PATH_EXPERT,
            )
        }
    }
    side.backgroundExperts.forEach { expert ->
        val prefix = "background.${scaleName(expert.scale)}.${expert.axisId}"
        channels += expertSupport(prefix, CausalFeatureFamily.SAMPLED_BACKGROUND_EXPERT, expert)
        channels += optionalNumber(
            "$prefix.value-mean",
            "points",
            "baseline-mean",
            expert.valueSummary?.mean,
            CausalFeatureFamily.SAMPLED_BACKGROUND_EXPERT,
        )
        channels += optionalNumber(
            "$prefix.value-deviation",
            "points",
            "baseline-deviation",
            expert.valueSummary?.deviation,
            CausalFeatureFamily.SAMPLED_BACKGROUND_EXPERT,
        )
        val deviation = side.backgroundDeviations.single { it.scale == expert.scale && it.axisId == expert.axisId }
        channels += optionalNumber(
            "$prefix.current-minus-mean",
            "points",
            "baseline-delta",
            deviation.currentMinusBackgroundMean,
            CausalFeatureFamily.SAMPLED_BACKGROUND_EXPERT,
        )
        channels += gapNumber(
            "$prefix.standardized",
            "standardized-points",
            deviation.standardizedDeviation,
            deviation.standardizationGap,
            CausalFeatureFamily.SAMPLED_BACKGROUND_EXPERT,
        )
        channels += gapFlag(
            "$prefix.insufficient",
            deviation.standardizationGap,
            StandardizationGap.INSUFFICIENT_SUPPORT,
            CausalFeatureFamily.SAMPLED_BACKGROUND_EXPERT,
        )
        channels += gapFlag(
            "$prefix.negligible",
            deviation.standardizationGap,
            StandardizationGap.NEGLIGIBLE_VARIATION,
            CausalFeatureFamily.SAMPLED_BACKGROUND_EXPERT,
        )
    }
    return channels
}

private fun expertSupport(
    prefix: String,
    family: CausalFeatureFamily,
    expert: TrajectoryExpert,
): List<CausalRawFeature> {
    val slopeStatus = if (expert.slopePerHour == null) CausalFeatureStatus.UNAVAILABLE else CausalFeatureStatus.OBSERVED
    return listOf(
        raw(prefix + ".slope", family, "points-per-hour", "slope", CausalInformationBoundary.ANCHOR_KNOWN, CausalFeatureScaling.STANDARDIZE, expert.slopePerHour, slopeStatus),
        count("$prefix.observed-count", expert.observedCount.toDouble(), family),
        count("$prefix.distinct-times", expert.distinctTimestampCount.toDouble(), family),
        optionalHours("$prefix.span-hours", expert.elapsedMillis, family),
        raw(
            "$prefix.residual-spread",
            family,
            "points",
            "fit-residual-deviation",
            CausalInformationBoundary.ANCHOR_KNOWN,
            CausalFeatureScaling.STANDARDIZE,
            expert.fitResidualDeviation,
            if (expert.fitResidualDeviation == null) CausalFeatureStatus.UNAVAILABLE else CausalFeatureStatus.OBSERVED,
        ),
        raw(
            "$prefix.focal-included",
            family,
            "indicator",
            "binary-flag",
            CausalInformationBoundary.ANCHOR_KNOWN,
            CausalFeatureScaling.BINARY,
            flag(expert.focalIncluded),
            CausalFeatureStatus.OBSERVED,
        ),
    )
}

private fun observationChannels(observation: ObservationProcessContext): List<CausalRawFeature> {
    val channels = mutableListOf<CausalRawFeature>()
    observation.samplings.forEach { sampling ->
        val prefix = "observation.${samplingName(sampling.sampling)}"
        val family = CausalFeatureFamily.OBSERVATION_PROCESS
        channels += count("$prefix.observations", sampling.observationCount.toDouble(), family)
        channels += count("$prefix.distinct-times", sampling.distinctTimestampCount.toDouble(), family)
        channels += count("$prefix.duplicate-times", sampling.duplicateTimestampCount.toDouble(), family)
        channels += nullableNumber("$prefix.coverage", "fraction", "coverage", sampling.coverageFraction, family)
        channels += nullableNumber("$prefix.rate", "per-hour", "observation-rate", sampling.observationRatePerHour, family)
        channels += optionalHours("$prefix.recency-hours", sampling.timeSinceLatestMillis, family)
        val gaps = sampling.gaps
        channels += nullableNumber("$prefix.gap-count", "count", "gap-summary", gaps?.count?.toDouble(), family)
        channels += nullableHours("$prefix.gap-mean-hours", gaps?.meanMillis, family)
        channels += nullableHours("$prefix.gap-deviation-hours", gaps?.deviationMillis, family)
        channels += nullableHours("$prefix.gap-median-hours", gaps?.medianMillis, family)
        channels += optionalHours("$prefix.gap-min-hours", gaps?.minimumMillis, family)
        channels += optionalHours("$prefix.gap-max-hours", gaps?.maximumMillis, family)
        channels += count("$prefix.emotion-recorded", sampling.emotion.recordedCount.toDouble(), family)
        channels += count("$prefix.emotion-missing", sampling.emotion.missingCount.toDouble(), family)
        sampling.axes.forEach { axis ->
            channels += count("$prefix.${axis.axisId}.recorded", axis.recordedCount.toDouble(), family)
            channels += count("$prefix.${axis.axisId}.missing", axis.missingCount.toDouble(), family)
            channels += count("$prefix.${axis.axisId}.conflicting", axis.conflictingCount.toDouble(), family)
        }
    }
    return channels
}

private fun clockChannels(
    prefix: String,
    family: CausalFeatureFamily,
    at: Long,
    zone: ZoneId,
): List<CausalRawFeature> {
    val zoned = Instant.ofEpochMilli(at).atZone(zone)
    val seconds = zoned.hour * 3600.0 + zoned.minute * 60.0 + zoned.second
    val day = 2.0 * PI * seconds / 86_400.0
    val week = 2.0 * PI * zoned.dayOfWeek.value / 7.0
    val boundary = if (family == CausalFeatureFamily.LOCAL_CLOCK) {
        CausalInformationBoundary.ANCHOR_KNOWN
    } else {
        CausalInformationBoundary.SCENARIO
    }
    return listOf(
        raw("$prefix.hour-sin", family, "sine", "cyclical-local-hour", boundary, CausalFeatureScaling.STANDARDIZE, sin(day), CausalFeatureStatus.OBSERVED),
        raw("$prefix.hour-cos", family, "cosine", "cyclical-local-hour", boundary, CausalFeatureScaling.STANDARDIZE, cos(day), CausalFeatureStatus.OBSERVED),
        raw("$prefix.weekday-sin", family, "sine", "cyclical-weekday", boundary, CausalFeatureScaling.STANDARDIZE, sin(week), CausalFeatureStatus.OBSERVED),
        raw("$prefix.weekday-cos", family, "cosine", "cyclical-weekday", boundary, CausalFeatureScaling.STANDARDIZE, cos(week), CausalFeatureStatus.OBSERVED),
    )
}

private fun categoryChannel(id: String, knowledge: CategoryKnowledge): CausalRawFeature {
    val (value, status) = when (knowledge) {
        CategoryKnowledge.REVIEWED_NONE -> 0.0 to CausalFeatureStatus.OBSERVED
        CategoryKnowledge.REVIEWED_ENTRIES -> 1.0 to CausalFeatureStatus.OBSERVED
        CategoryKnowledge.UNKNOWN -> null to CausalFeatureStatus.UNKNOWN
    }
    return raw(
        id,
        CausalFeatureFamily.SCENARIO_CATEGORY,
        "indicator",
        "category-knowledge",
        CausalInformationBoundary.SCENARIO,
        CausalFeatureScaling.BINARY,
        value,
        status,
    )
}

private data class UsageQuantityKey(val identity: BehaviorIdentity, val unit: String?)

private fun triggerVocabulary(projections: List<CausalRawProjection>): List<String> {
    val tokens = sortedSetOf<String>()
    projections.forEach { projection ->
        tokens += projection.focalTrigger
        projection.historicalTriggers.forEach { historical ->
            tokens += historical.counts.map { it.trigger }
        }
    }
    return tokens.toList()
}

private fun usageQuantityKeys(projections: List<CausalRawProjection>): List<UsageQuantityKey> {
    return projections.flatMap { projection ->
        projection.exposures.filterIsInstance<UsageBehavior>().flatMap { behavior ->
            behavior.amounts.filter { it.status == UsageQuantityStatus.PARSED }.map {
                UsageQuantityKey(behavior.identity, it.unit)
            }
        }
    }.distinct().sortedWith(
        compareBy(
            { it.identity.kind.ordinal },
            { it.identity.key },
            { it.identity.intent ?: "" },
            { it.unit != null },
            { it.unit ?: "" },
        ),
    )
}

private val identityOrder = compareBy<BehaviorIdentity>({ it.kind.ordinal }, { it.key }, { it.intent ?: "" })

private fun identityToken(identity: BehaviorIdentity): String {
    val intent = identity.intent?.let { ".${diagnosticToken(it)}" }.orEmpty()
    return "${identity.kind.name.lowercase()}.${diagnosticToken(identity.key)}$intent"
}

private fun unitToken(unit: String?): String = if (unit == null) "unit.none" else "unit.${diagnosticToken(unit)}"

private fun samplingName(sampling: ObservationSamplingId): String = when (sampling) {
    ObservationSamplingId.RESOLVED_WAKING_PATH -> "waking-path"
    ObservationSamplingId.PRIOR_24_HOURS -> "prior-24-hours"
    ObservationSamplingId.PRIOR_7_DAYS -> "prior-7-days"
}

private fun scaleName(scale: TrajectoryScaleId): String = when (scale) {
    TrajectoryScaleId.IMMEDIATE_PATH -> "immediate"
    TrajectoryScaleId.SIX_HOUR_PATH -> "six-hour"
    TrajectoryScaleId.WAKE_CYCLE_PATH -> "wake-cycle"
    TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND -> "trailing-24-hour"
    TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND -> "trailing-7-day"
}

private fun flag(on: Boolean): Double = if (on) 1.0 else 0.0

private fun count(
    id: String,
    value: Double,
    family: CausalFeatureFamily = CausalFeatureFamily.TRAJECTORY_CONTEXT,
): CausalRawFeature {
    return raw(id, family, "count", "count", CausalInformationBoundary.ANCHOR_KNOWN, CausalFeatureScaling.STANDARDIZE, value, CausalFeatureStatus.OBSERVED)
}

private fun optionalInt(
    id: String,
    unit: String,
    interpretation: String,
    value: Int?,
    family: CausalFeatureFamily = CausalFeatureFamily.TRAJECTORY_CONTEXT,
): CausalRawFeature {
    return optionalNumber(id, unit, interpretation, value?.toDouble(), family)
}

private fun optionalNumber(
    id: String,
    unit: String,
    interpretation: String,
    value: Double?,
    family: CausalFeatureFamily = CausalFeatureFamily.TRAJECTORY_CONTEXT,
): CausalRawFeature {
    return raw(
        id,
        family,
        unit,
        interpretation,
        CausalInformationBoundary.ANCHOR_KNOWN,
        CausalFeatureScaling.STANDARDIZE,
        value,
        if (value == null) CausalFeatureStatus.MISSING else CausalFeatureStatus.OBSERVED,
    )
}

private fun optionalFlag(id: String, on: Boolean?): CausalRawFeature {
    return raw(
        id,
        CausalFeatureFamily.TRAJECTORY_CONTEXT,
        "indicator",
        "binary-flag",
        CausalInformationBoundary.ANCHOR_KNOWN,
        CausalFeatureScaling.BINARY,
        on?.let(::flag),
        if (on == null) CausalFeatureStatus.MISSING else CausalFeatureStatus.OBSERVED,
    )
}

private fun directionFlag(id: String, direction: DeltaDirection?, expected: DeltaDirection): CausalRawFeature {
    return raw(
        id,
        CausalFeatureFamily.TRAJECTORY_CONTEXT,
        "indicator",
        "direction",
        CausalInformationBoundary.ANCHOR_KNOWN,
        CausalFeatureScaling.BINARY,
        direction?.let { flag(it == expected) },
        if (direction == null) CausalFeatureStatus.MISSING else CausalFeatureStatus.OBSERVED,
    )
}

private fun gapNumber(
    id: String,
    unit: String,
    value: Double?,
    gap: StandardizationGap?,
    family: CausalFeatureFamily = CausalFeatureFamily.TRAJECTORY_CONTEXT,
): CausalRawFeature {
    val status = when {
        value != null -> CausalFeatureStatus.OBSERVED
        gap != null -> CausalFeatureStatus.UNAVAILABLE
        else -> CausalFeatureStatus.MISSING
    }
    return raw(
        id,
        family,
        unit,
        "standardized-deviation",
        CausalInformationBoundary.ANCHOR_KNOWN,
        CausalFeatureScaling.STANDARDIZE,
        value,
        status,
    )
}

private fun gapFlag(
    id: String,
    gap: StandardizationGap?,
    expected: StandardizationGap,
    family: CausalFeatureFamily = CausalFeatureFamily.TRAJECTORY_CONTEXT,
): CausalRawFeature {
    return raw(
        id,
        family,
        "indicator",
        "standardization-gap",
        CausalInformationBoundary.ANCHOR_KNOWN,
        CausalFeatureScaling.BINARY,
        flag(gap == expected),
        CausalFeatureStatus.OBSERVED,
    )
}

private fun optionalHours(id: String, millis: Long?, family: CausalFeatureFamily): CausalRawFeature {
    return raw(
        id,
        family,
        "hours",
        "elapsed-hours",
        CausalInformationBoundary.ANCHOR_KNOWN,
        CausalFeatureScaling.STANDARDIZE,
        millis?.div(MILLIS_PER_HOUR),
        if (millis == null) CausalFeatureStatus.UNAVAILABLE else CausalFeatureStatus.OBSERVED,
    )
}

private fun nullableHours(id: String, millis: Double?, family: CausalFeatureFamily): CausalRawFeature {
    return nullableNumber(id, "hours", "gap-summary", millis?.div(MILLIS_PER_HOUR), family)
}

private fun nullableNumber(
    id: String,
    unit: String,
    interpretation: String,
    value: Double?,
    family: CausalFeatureFamily,
): CausalRawFeature {
    return raw(
        id,
        family,
        unit,
        interpretation,
        CausalInformationBoundary.ANCHOR_KNOWN,
        CausalFeatureScaling.STANDARDIZE,
        value,
        if (value == null) CausalFeatureStatus.UNAVAILABLE else CausalFeatureStatus.OBSERVED,
    )
}

private fun raw(
    id: String,
    family: CausalFeatureFamily,
    unit: String,
    interpretation: String,
    boundary: CausalInformationBoundary,
    scaling: CausalFeatureScaling,
    value: Double?,
    status: CausalFeatureStatus,
): CausalRawFeature {
    return CausalRawFeature(
        id = id,
        name = id,
        family = family,
        unit = unit,
        interpretation = interpretation,
        boundary = boundary,
        scaling = scaling,
        value = value,
        status = status,
    )
}

private operator fun Long.div(divisor: Double): Double = this.toDouble() / divisor

private const val MILLIS_PER_HOUR = 3_600_000.0
