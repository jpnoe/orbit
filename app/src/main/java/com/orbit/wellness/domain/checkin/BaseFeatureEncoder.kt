package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableUnit
import java.util.Locale

/**
 * Named base vectors for one [ContextualEvidence] dataset.
 *
 * Fitting uses every state and transition in the supplied dataset. That is a
 * descriptive schema for this dataset. A later held-out evaluation must fit on
 * its training evidence only, then call [encodeFeatures] with that schema.
 * Fitting here is not that evaluation.
 *
 * Unobserved slots store `0.0` and an observed mask of false. That zero is not
 * a measurement. A recorded zero stores `0.0` with the mask true.
 * Occurrence ids, notes, and join timestamps are not features.
 *
 * Usage presence is an identity and an intent. A blank or unparsed amount still
 * counts as present. Totals and the unitless per-hour rate are written only
 * when every matching amount parsed. Encoding reads [FeatureSemantic], not the
 * diagnostic name. [diagnosticToken] percent-encodes characters outside
 * letters, digits, `_`, and `-` so a token such as `fl.oz` cannot be split on
 * a dot, and so `%` itself cannot collide with that encoding.
 */
data class FeatureSchema(
    val features: List<FeatureDefinition>,
) {
    init {
        features.forEachIndexed { index, feature ->
            require(feature.index == index) { "Feature ${feature.name} is not at index $index" }
        }
        require(features.map { it.name }.distinct().size == features.size) {
            "Feature names must be unique"
        }
        require(features.map { it.semantic }.distinct().size == features.size) {
            "Feature semantics must be unique"
        }
    }

    val size: Int
        get() = features.size

    fun definition(index: Int): FeatureDefinition = features[index]

    fun indexOf(name: String): Int = byName.getValue(name).index

    fun indexOf(semantic: FeatureSemantic): Int = bySemantic.getValue(semantic).index

    private val byName: Map<String, FeatureDefinition> = features.associateBy { it.name }
    private val bySemantic: Map<FeatureSemantic, FeatureDefinition> = features.associateBy { it.semantic }
}

/**
 * What a feature measures. Encoding reads this value. [FeatureDefinition.name]
 * is only the diagnostic label.
 *
 * A user-derived token is percent-encoded in that label by [diagnosticToken].
 * The encoding is reversible: `%` + hex code point + `;`. `.` inside a name or
 * unit therefore cannot be mistaken for the separator between tokens.
 */
sealed interface FeatureSemantic {
    data class AxisValue(val axisId: String) : FeatureSemantic
    data class AxisObserved(val axisId: String) : FeatureSemantic
    data class AxisConflicting(val axisId: String) : FeatureSemantic
    data class EmotionPresent(val label: String) : FeatureSemantic
    data object EmotionObserved : FeatureSemantic
    data object DurationHours : FeatureSemantic
    data class ActivityPresent(val identity: String) : FeatureSemantic
    data class WorkoutPresent(val identity: String) : FeatureSemantic
    data class WorkoutMinutes(val identity: String) : FeatureSemantic
    data class MealSizeCount(val size: String) : FeatureSemantic
    data class FoodPresent(val identity: String) : FeatureSemantic
    data class FoodCount(val identity: String) : FeatureSemantic
    data class Usage(
        val identity: String,
        val intent: String,
        val measurement: UsageMeasurement,
        val unit: String? = null,
    ) : FeatureSemantic {
        init {
            val needsUnit = measurement == UsageMeasurement.AMOUNT || measurement == UsageMeasurement.PER_HOUR
            require(needsUnit == (unit != null)) {
                "$measurement must carry a unit exactly when it is an amount or a rate"
            }
        }
    }

    data class WearableValue(val metric: String) : FeatureSemantic
    data class WearableSample(val metric: String, val statistic: SampleStatistic) : FeatureSemantic
    data object ProtocolCompleted : FeatureSemantic
    data class StartBasis(val basis: String) : FeatureSemantic
    data class CategoryStatus(val category: String, val kind: CategoryObservationKindV2) : FeatureSemantic
    data class WearableAmbiguous(val metric: String) : FeatureSemantic
}

enum class UsageMeasurement {
    PRESENT,
    ENTRIES,
    PARSED,
    MISSING,
    UNPARSED,
    AMOUNT_COMPLETE,
    AMOUNT,
    PER_HOUR,
}

enum class SampleStatistic {
    COUNT,
    MEAN,
    MIN,
    MAX,
}

/**
 * Diagnostic spelling of one categorical token. Letters, digits, `_`, and `-`
 * stay as written. Every other code point becomes `%` + lowercase hex + `;`.
 */
fun diagnosticToken(raw: String): String = buildString {
    var index = 0
    while (index < raw.length) {
        val code = raw.codePointAt(index)
        val safe = code < 128 && (
            raw[index].isLetterOrDigit() || raw[index] == '_' || raw[index] == '-'
            )
        if (safe) append(raw[index]) else append('%').append(code.toString(16)).append(';')
        index += Character.charCount(code)
    }
}

data class FeatureDefinition(
    val index: Int,
    val name: String,
    val block: FeatureBlock,
    val semantic: FeatureSemantic,
)

enum class FeatureBlock {
    STATE_AXIS_VALUE,
    STATE_AXIS_MASK,
    STATE_EMOTION,
    TRANSITION_DURATION,
    TRANSITION_ACTIVITY,
    TRANSITION_WORKOUT,
    TRANSITION_DIET,
    TRANSITION_USAGE,
    TRANSITION_WEARABLE,
    PROTOCOL,
}

data class FeatureSchemas(
    val state: FeatureSchema,
    val transition: FeatureSchema,
)

/** Values and a separate observed mask. Indexes match the schema that produced them. */
data class EncodedVector(
    val values: List<Double>,
    val observed: List<Boolean>,
) {
    init {
        require(values.size == observed.size) { "A vector needs one mask bit per value" }
    }
}

data class EncodedState(
    val key: StateOccurrenceKey,
    val vector: EncodedVector,
)

data class EncodedTransition(
    val key: TransitionOccurrenceKey,
    val vector: EncodedVector,
)

data class EncodedCausalExample(
    /** Diagnostic identity. It is not a column in [startingState] or [transition]. */
    val exampleKey: TransitionOccurrenceKey,
    val startingState: EncodedVector?,
    val transition: EncodedVector,
    val target: EncodedVector,
    val eligibility: CausalEligibility,
)

data class EncodedFeatures(
    val schemas: FeatureSchemas,
    val states: List<EncodedState>,
    val transitions: List<EncodedTransition>,
    val causalExamples: List<EncodedCausalExample>,
    val eligibleCausalExamples: List<EncodedCausalExample>,
)

fun fitFeatureSchemas(evidence: ContextualEvidence): FeatureSchemas {
    return FeatureSchemas(
        state = fitStateSchema(evidence.states.map { it.evidence }),
        transition = fitTransitionSchema(evidence.transitions.map { it.evidence }),
    )
}

fun encodeFeatures(
    evidence: ContextualEvidence,
    schemas: FeatureSchemas = fitFeatureSchemas(evidence),
): EncodedFeatures {
    val states = evidence.states.map { frame ->
        EncodedState(frame.key, encodeState(frame.evidence, schemas.state))
    }
    val transitions = evidence.transitions.map { frame ->
        EncodedTransition(
            frame.key,
            encodeTransition(frame.evidence, evidence.wearablesById, schemas.transition),
        )
    }
    val causal = evidence.causalExamples.map { example ->
        EncodedCausalExample(
            exampleKey = example.exampleKey,
            startingState = example.input.startingState?.let { encodeState(it, schemas.state) },
            transition = encodeTransition(example.input.transition, evidence.wearablesById, schemas.transition),
            target = encodeState(example.target.endingState, schemas.state),
            eligibility = example.eligibility,
        )
    }
    return EncodedFeatures(
        schemas = schemas,
        states = states,
        transitions = transitions,
        causalExamples = causal,
        eligibleCausalExamples = causal.filter { it.eligibility.eligible },
    )
}

private fun fitStateSchema(states: List<StateEvidencePayload>): FeatureSchema {
    val custom = states.flatMap { state ->
        state.axes.map { it.axisId } + state.conflictingAxisIds
    }.filter { it !in CheckInAnalysisDatasetV2.CORE_AXIS_IDS }.toSortedSet()
    val axes = CheckInAnalysisDatasetV2.CORE_AXIS_IDS + custom
    val emotions = states.mapNotNull { it.emotion?.name }.toSortedSet()
    val features = FeatureList()
    axes.forEach { axisId ->
        features.add(
            "state.axis.${diagnosticToken(axisId)}.value",
            FeatureBlock.STATE_AXIS_VALUE,
            FeatureSemantic.AxisValue(axisId),
        )
    }
    axes.forEach { axisId ->
        features.add(
            "state.axis.${diagnosticToken(axisId)}.observed",
            FeatureBlock.STATE_AXIS_MASK,
            FeatureSemantic.AxisObserved(axisId),
        )
        features.add(
            "state.axis.${diagnosticToken(axisId)}.conflicting",
            FeatureBlock.STATE_AXIS_MASK,
            FeatureSemantic.AxisConflicting(axisId),
        )
    }
    emotions.forEach { emotion ->
        features.add(
            "state.emotion.present.${diagnosticToken(emotion)}",
            FeatureBlock.STATE_EMOTION,
            FeatureSemantic.EmotionPresent(emotion),
        )
    }
    features.add("state.emotion.observed", FeatureBlock.STATE_EMOTION, FeatureSemantic.EmotionObserved)
    return features.build()
}

private fun fitTransitionSchema(transitions: List<TransitionEvidencePayload>): FeatureSchema {
    val activities = sortedKeys(transitions.flatMap { it.activity.items.map { item -> item.key() } })
    val workouts = sortedKeys(transitions.flatMap { it.workout.items.map { item -> item.key() } })
    val foods = sortedKeys(transitions.flatMap { transition ->
        transition.diet.items.flatMap { meal -> meal.foods.map { it.key() } }
    })
    val sizes = mealSizeVocabulary(transitions.flatMap { it.diet.items.map { meal -> meal.size } })
    val usage = usageVocabulary(transitions.flatMap { it.usage.items })
    val features = FeatureList()
    features.add("transition.duration.hours", FeatureBlock.TRANSITION_DURATION, FeatureSemantic.DurationHours)
    activities.forEach { key ->
        features.add(
            "transition.activity.present.${diagnosticToken(key)}",
            FeatureBlock.TRANSITION_ACTIVITY,
            FeatureSemantic.ActivityPresent(key),
        )
    }
    workouts.forEach { key ->
        features.add(
            "transition.workout.present.${diagnosticToken(key)}",
            FeatureBlock.TRANSITION_WORKOUT,
            FeatureSemantic.WorkoutPresent(key),
        )
        features.add(
            "transition.workout.minutes.${diagnosticToken(key)}",
            FeatureBlock.TRANSITION_WORKOUT,
            FeatureSemantic.WorkoutMinutes(key),
        )
    }
    sizes.forEach { size ->
        features.add(
            "transition.diet.size.${diagnosticToken(size)}.count",
            FeatureBlock.TRANSITION_DIET,
            FeatureSemantic.MealSizeCount(size),
        )
    }
    foods.forEach { key ->
        features.add(
            "transition.diet.food.present.${diagnosticToken(key)}",
            FeatureBlock.TRANSITION_DIET,
            FeatureSemantic.FoodPresent(key),
        )
        features.add(
            "transition.diet.food.count.${diagnosticToken(key)}",
            FeatureBlock.TRANSITION_DIET,
            FeatureSemantic.FoodCount(key),
        )
    }
    usage.forEach { slot ->
        val prefix = "transition.usage.${diagnosticToken(slot.key)}.${diagnosticToken(slot.intent)}"
        UsageMeasurement.entries
            .filter { it != UsageMeasurement.AMOUNT && it != UsageMeasurement.PER_HOUR }
            .forEach { measurement ->
                features.add(
                    "$prefix.${measurement.token()}",
                    FeatureBlock.TRANSITION_USAGE,
                    FeatureSemantic.Usage(slot.key, slot.intent, measurement),
                )
            }
        slot.units.forEach { unit ->
            features.add(
                "$prefix.amount.${diagnosticToken(unit)}",
                FeatureBlock.TRANSITION_USAGE,
                FeatureSemantic.Usage(slot.key, slot.intent, UsageMeasurement.AMOUNT, unit),
            )
            if (unit == UNITLESS) {
                features.add(
                    "$prefix.per_hour.${diagnosticToken(unit)}",
                    FeatureBlock.TRANSITION_USAGE,
                    FeatureSemantic.Usage(slot.key, slot.intent, UsageMeasurement.PER_HOUR, unit),
                )
            }
        }
    }
    WEARABLE_METRICS.forEach { metric ->
        if (metric == WearableMetric.HEART_RATE_SAMPLE) {
            SampleStatistic.entries.forEach { statistic ->
                features.add(
                    "transition.wearable.$metric.${statistic.name.lowercase(Locale.ROOT)}",
                    FeatureBlock.TRANSITION_WEARABLE,
                    FeatureSemantic.WearableSample(metric, statistic),
                )
            }
        } else {
            features.add(
                "transition.wearable.$metric.value",
                FeatureBlock.TRANSITION_WEARABLE,
                FeatureSemantic.WearableValue(metric),
            )
        }
    }
    features.add("protocol.completed", FeatureBlock.PROTOCOL, FeatureSemantic.ProtocolCompleted)
    startBasisVocabulary(transitions.map { it.temporal.startBasis }).forEach { basis ->
        features.add(
            "protocol.start_basis.${diagnosticToken(basis)}",
            FeatureBlock.PROTOCOL,
            FeatureSemantic.StartBasis(basis),
        )
    }
    CATEGORIES.forEach { category ->
        CategoryObservationKindV2.entries.forEach { kind ->
            features.add(
                "protocol.category.$category.${kind.name.lowercase(Locale.ROOT)}",
                FeatureBlock.PROTOCOL,
                FeatureSemantic.CategoryStatus(category, kind),
            )
        }
    }
    WEARABLE_METRICS.forEach { metric ->
        features.add(
            "protocol.wearable.$metric.ambiguous",
            FeatureBlock.PROTOCOL,
            FeatureSemantic.WearableAmbiguous(metric),
        )
    }
    return features.build()
}

private fun encodeState(payload: StateEvidencePayload, schema: FeatureSchema): EncodedVector {
    val writer = VectorWriter(schema)
    val readings = payload.axes.associateBy { it.axisId }
    val conflicting = payload.conflictingAxisIds.toSet()
    val emotion = payload.emotion?.name
    schema.features.forEach { feature ->
        when (val semantic = feature.semantic) {
            is FeatureSemantic.AxisValue -> {
                val reading = readings[semantic.axisId]
                writer.set(feature, reading?.normalized ?: 0.0, observed = reading != null)
            }
            is FeatureSemantic.AxisObserved -> {
                writer.set(feature, if (readings[semantic.axisId] != null) 1.0 else 0.0, observed = true)
            }
            is FeatureSemantic.AxisConflicting -> {
                writer.set(feature, if (semantic.axisId in conflicting) 1.0 else 0.0, observed = true)
            }
            is FeatureSemantic.EmotionPresent -> {
                if (emotion == null) {
                    writer.set(feature, 0.0, observed = false)
                } else {
                    writer.set(feature, if (semantic.label == emotion) 1.0 else 0.0, observed = true)
                }
            }
            FeatureSemantic.EmotionObserved -> {
                writer.set(feature, if (emotion != null) 1.0 else 0.0, observed = true)
            }
            else -> error("State schema contains ${feature.name}")
        }
    }
    return writer.build()
}

private fun encodeTransition(
    payload: TransitionEvidencePayload,
    wearablesById: Map<String, WearableObservation>,
    schema: FeatureSchema,
): EncodedVector {
    val writer = VectorWriter(schema)
    val hours = payload.temporal.durationMillis.toDouble() / MILLIS_PER_HOUR
    val activityPresent = payload.activity.items.map { it.key() }.toSet()
    val workoutMinutes = payload.workout.items.groupBy { it.key() }.mapValues { (_, items) ->
        items.sumOf { it.durationMinutes }.toDouble()
    }
    val mealCounts = payload.diet.items.groupingBy { it.size }.eachCount()
    val foodCounts = payload.diet.items.flatMap { it.foods }.groupingBy { it.key() }.eachCount()
    val usageBySlot = payload.usage.items.groupBy { it.key() to it.intent }
    schema.features.forEach { feature ->
        when (val semantic = feature.semantic) {
            FeatureSemantic.DurationHours -> writer.set(feature, hours, observed = true)
            is FeatureSemantic.ActivityPresent -> writePresence(
                writer, feature, payload.activity.kind, semantic.identity in activityPresent,
            )
            is FeatureSemantic.WorkoutPresent -> writeWorkout(
                writer, feature, payload.workout.kind, workoutMinutes[semantic.identity], minutesFeature = false,
            )
            is FeatureSemantic.WorkoutMinutes -> writeWorkout(
                writer, feature, payload.workout.kind, workoutMinutes[semantic.identity], minutesFeature = true,
            )
            is FeatureSemantic.MealSizeCount -> writeCount(
                writer, feature, payload.diet.kind, mealCounts[semantic.size] ?: 0,
            )
            is FeatureSemantic.FoodPresent -> writePresence(
                writer, feature, payload.diet.kind, semantic.identity in foodCounts,
            )
            is FeatureSemantic.FoodCount -> writeCount(
                writer, feature, payload.diet.kind, foodCounts[semantic.identity] ?: 0,
            )
            is FeatureSemantic.Usage -> writeUsage(
                writer, feature, semantic, payload.usage.kind, usageBySlot, hours,
            )
            is FeatureSemantic.WearableValue -> writeWearableValue(
                writer, feature, semantic.metric, payload.wearables, wearablesById,
            )
            is FeatureSemantic.WearableSample -> writeWearableSample(
                writer, feature, semantic, payload.wearables, wearablesById,
            )
            FeatureSemantic.ProtocolCompleted -> {
                writer.set(feature, if (payload.temporal.completed) 1.0 else 0.0, observed = true)
            }
            is FeatureSemantic.StartBasis -> {
                writer.set(feature, if (semantic.basis == payload.temporal.startBasis) 1.0 else 0.0, observed = true)
            }
            is FeatureSemantic.CategoryStatus -> {
                val kind = payload.category(semantic.category).kind
                writer.set(feature, if (semantic.kind == kind) 1.0 else 0.0, observed = true)
            }
            is FeatureSemantic.WearableAmbiguous -> {
                writer.set(feature, if (wearableAmbiguous(semantic.metric, payload.wearables, wearablesById)) 1.0 else 0.0, observed = true)
            }
            else -> error("Transition schema contains ${feature.name}")
        }
    }
    return writer.build()
}

private fun writePresence(
    writer: VectorWriter,
    feature: FeatureDefinition,
    kind: CategoryObservationKindV2,
    present: Boolean,
) {
    if (!kind.admitsItemCounts()) {
        writer.set(feature, 0.0, observed = false)
    } else {
        writer.set(feature, if (present) 1.0 else 0.0, observed = true)
    }
}

private fun writeCount(
    writer: VectorWriter,
    feature: FeatureDefinition,
    kind: CategoryObservationKindV2,
    count: Int,
) {
    if (!kind.admitsItemCounts()) {
        writer.set(feature, 0.0, observed = false)
    } else {
        writer.set(feature, count.toDouble(), observed = true)
    }
}

private fun writeWorkout(
    writer: VectorWriter,
    feature: FeatureDefinition,
    kind: CategoryObservationKindV2,
    minutes: Double?,
    minutesFeature: Boolean,
) {
    if (!kind.admitsItemCounts()) {
        writer.set(feature, 0.0, observed = false)
    } else if (minutesFeature) {
        writer.set(feature, minutes ?: 0.0, observed = true)
    } else {
        writer.set(feature, if (minutes != null) 1.0 else 0.0, observed = true)
    }
}

private fun writeUsage(
    writer: VectorWriter,
    feature: FeatureDefinition,
    semantic: FeatureSemantic.Usage,
    kind: CategoryObservationKindV2,
    grouped: Map<Pair<String, String>, List<UsageEvidence>>,
    durationHours: Double,
) {
    if (!kind.admitsItemCounts()) {
        writer.set(feature, 0.0, observed = false)
        return
    }
    val amounts = grouped[semantic.identity to semantic.intent].orEmpty()
        .map { UsageAmountParser.parse(it.amountText) }
    val complete = amounts.all { it is UsageAmountParse.Parsed }
    when (semantic.measurement) {
        UsageMeasurement.PRESENT -> writer.set(feature, if (amounts.isNotEmpty()) 1.0 else 0.0, observed = true)
        UsageMeasurement.ENTRIES -> writer.set(feature, amounts.size.toDouble(), observed = true)
        UsageMeasurement.PARSED -> {
            writer.set(feature, amounts.count { it is UsageAmountParse.Parsed }.toDouble(), observed = true)
        }
        UsageMeasurement.MISSING -> {
            writer.set(feature, amounts.count { it is UsageAmountParse.Missing }.toDouble(), observed = true)
        }
        UsageMeasurement.UNPARSED -> {
            writer.set(feature, amounts.count { it is UsageAmountParse.Unparsed }.toDouble(), observed = true)
        }
        UsageMeasurement.AMOUNT_COMPLETE -> writer.set(feature, if (complete) 1.0 else 0.0, observed = true)
        UsageMeasurement.AMOUNT -> {
            if (!complete) {
                writer.set(feature, 0.0, observed = false)
            } else {
                writer.set(feature, summedAmount(amounts, checkNotNull(semantic.unit)), observed = true)
            }
        }
        UsageMeasurement.PER_HOUR -> {
            val unitless = semantic.unit == UNITLESS && durationHours > 0.0 && complete
            if (!unitless) {
                writer.set(feature, 0.0, observed = false)
            } else {
                writer.set(feature, summedAmount(amounts, UNITLESS) / durationHours, observed = true)
            }
        }
    }
}

private fun summedAmount(amounts: List<UsageAmountParse>, unit: String): Double {
    return amounts.sumOf { parsed ->
        if (parsed is UsageAmountParse.Parsed && unitToken(parsed.unit) == unit) parsed.number else 0.0
    }
}

private fun writeWearableValue(
    writer: VectorWriter,
    feature: FeatureDefinition,
    metric: String,
    wearables: List<WearableMetricEvidence>,
    wearablesById: Map<String, WearableObservation>,
) {
    val reading = wearableReading(metric, wearables, wearablesById)
    val measured = if (reading.ambiguous) null else reading.rows.singleOrNull()
    writer.set(feature, measured?.value ?: 0.0, observed = measured != null)
}

private fun writeWearableSample(
    writer: VectorWriter,
    feature: FeatureDefinition,
    semantic: FeatureSemantic.WearableSample,
    wearables: List<WearableMetricEvidence>,
    wearablesById: Map<String, WearableObservation>,
) {
    val reading = wearableReading(semantic.metric, wearables, wearablesById)
    val rows = reading.rows
    val canSummarize = rows.isNotEmpty() && !reading.ambiguous
    val values = rows.map { it.value }
    val (value, observed) = when (semantic.statistic) {
        SampleStatistic.COUNT -> {
            if (rows.isEmpty() || reading.ambiguous) 0.0 to false else rows.size.toDouble() to true
        }
        SampleStatistic.MEAN -> if (canSummarize) values.average() to true else 0.0 to false
        SampleStatistic.MIN -> if (canSummarize) values.min() to true else 0.0 to false
        SampleStatistic.MAX -> if (canSummarize) values.max() to true else 0.0 to false
    }
    writer.set(feature, value, observed)
}

private data class WearableReading(
    val rows: List<WearableObservation>,
    val ambiguous: Boolean,
)

private fun wearableAmbiguous(
    metric: String,
    wearables: List<WearableMetricEvidence>,
    wearablesById: Map<String, WearableObservation>,
): Boolean = wearableReading(metric, wearables, wearablesById).ambiguous

private fun wearableReading(
    metric: String,
    wearables: List<WearableMetricEvidence>,
    wearablesById: Map<String, WearableObservation>,
): WearableReading {
    val evidence = wearables.firstOrNull { it.metric == metric }
    val rows = when (evidence) {
        is WearableMetricEvidence.Observed -> evidence.observationIds.map { wearablesById.getValue(it) }
        else -> emptyList()
    }
    val unitsAgree = rows.isNotEmpty() && rows.all { it.unit == expectedUnit(metric) }
    val ambiguous = evidence is WearableMetricEvidence.Observed &&
        (evidence.competingSpans || !unitsAgree || spanMetric(metric) && rows.size > 1)
    return WearableReading(rows, ambiguous)
}

private data class UsageSlot(
    val key: String,
    val intent: String,
    val units: List<String>,
)

private fun usageVocabulary(items: List<UsageEvidence>): List<UsageSlot> {
    val unitsBySlot = linkedMapOf<Pair<String, String>, MutableSet<String>>()
    items.forEach { item ->
        val slot = item.key() to item.intent
        val units = unitsBySlot.getOrPut(slot) { linkedSetOf() }
        val parsed = UsageAmountParser.parse(item.amountText)
        if (parsed is UsageAmountParse.Parsed) units += unitToken(parsed.unit)
    }
    return unitsBySlot.keys
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { (key, intent) ->
            val units = unitsBySlot.getValue(key to intent).sortedWith(
                compareBy<String> { if (it == UNITLESS) 0 else 1 }.thenBy { it },
            )
            UsageSlot(key, intent, units)
        }
}

private fun unitToken(unit: String?): String = unit ?: UNITLESS

private fun mealSizeVocabulary(seen: List<String>): List<String> {
    val extras = seen.filter { it !in KNOWN_MEAL_SIZES }.toSortedSet()
    return KNOWN_MEAL_SIZES + extras
}

private fun startBasisVocabulary(seen: List<String>): List<String> {
    val extras = seen.filter { it !in KNOWN_START_BASES }.toSortedSet()
    return KNOWN_START_BASES + extras
}

private fun sortedKeys(keys: List<String>): List<String> = keys.toSortedSet().toList()

private fun CategoryObservationKindV2.admitsItemCounts(): Boolean {
    return this == CategoryObservationKindV2.NONE || this == CategoryObservationKindV2.ENTRIES
}

private fun TransitionEvidencePayload.category(name: String): CategoryEvidence<*> {
    return when (name) {
        "activity" -> activity
        "workout" -> workout
        "diet" -> diet
        "usage" -> usage
        else -> error("Unknown category $name")
    }
}

internal fun categoricalKey(optionId: String?, name: String): String {
    val option = optionId?.trim()?.takeIf { it.isNotEmpty() }
    if (option != null) return "option:$option"
    return "name:" + name.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")
}

private fun ActivityEvidence.key(): String = categoricalKey(optionId, name)

private fun WorkoutEvidence.key(): String = categoricalKey(optionId, name)

private fun FoodEvidence.key(): String = categoricalKey(optionId, name)

private fun UsageEvidence.key(): String = categoricalKey(optionId, name)

private fun spanMetric(metric: String): Boolean {
    return metric == WearableMetric.STEPS || metric == WearableMetric.HEART_RATE_AVERAGE
}

private fun expectedUnit(metric: String): String {
    return if (metric == WearableMetric.STEPS) WearableUnit.COUNT else WearableUnit.BEATS_PER_MINUTE
}

private class FeatureList {
    private val features = mutableListOf<FeatureDefinition>()

    fun add(name: String, block: FeatureBlock, semantic: FeatureSemantic) {
        features += FeatureDefinition(features.size, name, block, semantic)
    }

    fun build(): FeatureSchema = FeatureSchema(features.toList())
}

private class VectorWriter(private val schema: FeatureSchema) {
    private val values = DoubleArray(schema.size)
    private val observed = BooleanArray(schema.size)
    private val written = BooleanArray(schema.size)

    fun set(feature: FeatureDefinition, value: Double, observed: Boolean) {
        values[feature.index] = if (observed) value else 0.0
        this.observed[feature.index] = observed
        written[feature.index] = true
    }

    fun build(): EncodedVector {
        schema.features.forEach { feature ->
            require(written[feature.index]) { "Feature ${feature.name} was not encoded" }
        }
        return EncodedVector(values.toList(), observed.toList())
    }
}

private const val UNITLESS = "unitless"

private fun UsageMeasurement.token(): String = when (this) {
    UsageMeasurement.PRESENT -> "present"
    UsageMeasurement.ENTRIES -> "entries"
    UsageMeasurement.PARSED -> "parsed"
    UsageMeasurement.MISSING -> "missing"
    UsageMeasurement.UNPARSED -> "unparsed"
    UsageMeasurement.AMOUNT_COMPLETE -> "amount_complete"
    UsageMeasurement.AMOUNT -> "amount"
    UsageMeasurement.PER_HOUR -> "per_hour"
}
private const val MILLIS_PER_HOUR = 3_600_000.0
private val WHITESPACE = Regex("\\s+")
private val KNOWN_MEAL_SIZES = listOf(
    CheckInMeal.SNACK,
    CheckInMeal.SMALL,
    CheckInMeal.MEDIUM,
    CheckInMeal.LARGE,
)
private val KNOWN_START_BASES = listOf(
    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
    CheckInContextStartBasis.WAKE_TIME,
    CheckInContextStartBasis.DAY_START,
)
private val CATEGORIES = listOf("activity", "workout", "diet", "usage")
private val WEARABLE_METRICS = IntervalWearableSelection.METRICS
