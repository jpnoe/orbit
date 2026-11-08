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
    }

    val size: Int
        get() = features.size

    fun definition(index: Int): FeatureDefinition = features[index]

    fun indexOf(name: String): Int = byName.getValue(name).index

    private val byName: Map<String, FeatureDefinition> = features.associateBy { it.name }
}

data class FeatureDefinition(
    val index: Int,
    val name: String,
    val block: FeatureBlock,
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
        features.add("state.axis.$axisId.value", FeatureBlock.STATE_AXIS_VALUE)
    }
    axes.forEach { axisId ->
        features.add("state.axis.$axisId.observed", FeatureBlock.STATE_AXIS_MASK)
        features.add("state.axis.$axisId.conflicting", FeatureBlock.STATE_AXIS_MASK)
    }
    emotions.forEach { emotion ->
        features.add("state.emotion.present.$emotion", FeatureBlock.STATE_EMOTION)
    }
    features.add("state.emotion.observed", FeatureBlock.STATE_EMOTION)
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
    features.add("transition.duration.hours", FeatureBlock.TRANSITION_DURATION)
    activities.forEach { key ->
        features.add("transition.activity.present.$key", FeatureBlock.TRANSITION_ACTIVITY)
    }
    workouts.forEach { key ->
        features.add("transition.workout.present.$key", FeatureBlock.TRANSITION_WORKOUT)
        features.add("transition.workout.minutes.$key", FeatureBlock.TRANSITION_WORKOUT)
    }
    sizes.forEach { size ->
        features.add("transition.diet.size.$size.count", FeatureBlock.TRANSITION_DIET)
    }
    foods.forEach { key ->
        features.add("transition.diet.food.present.$key", FeatureBlock.TRANSITION_DIET)
        features.add("transition.diet.food.count.$key", FeatureBlock.TRANSITION_DIET)
    }
    usage.forEach { slot ->
        val prefix = "transition.usage.${slot.key}.${slot.intent}"
        features.add("$prefix.entries", FeatureBlock.TRANSITION_USAGE)
        features.add("$prefix.parsed", FeatureBlock.TRANSITION_USAGE)
        features.add("$prefix.missing", FeatureBlock.TRANSITION_USAGE)
        features.add("$prefix.unparsed", FeatureBlock.TRANSITION_USAGE)
        slot.units.forEach { unit ->
            features.add("$prefix.amount.$unit", FeatureBlock.TRANSITION_USAGE)
            if (unit == UNITLESS) {
                features.add("$prefix.per_hour.$unit", FeatureBlock.TRANSITION_USAGE)
            }
        }
    }
    WEARABLE_METRICS.forEach { metric ->
        if (metric == WearableMetric.HEART_RATE_SAMPLE) {
            features.add("transition.wearable.$metric.count", FeatureBlock.TRANSITION_WEARABLE)
            features.add("transition.wearable.$metric.mean", FeatureBlock.TRANSITION_WEARABLE)
            features.add("transition.wearable.$metric.min", FeatureBlock.TRANSITION_WEARABLE)
            features.add("transition.wearable.$metric.max", FeatureBlock.TRANSITION_WEARABLE)
        } else {
            features.add("transition.wearable.$metric.value", FeatureBlock.TRANSITION_WEARABLE)
        }
    }
    features.add("protocol.completed", FeatureBlock.PROTOCOL)
    startBasisVocabulary(transitions.map { it.temporal.startBasis }).forEach { basis ->
        features.add("protocol.start_basis.$basis", FeatureBlock.PROTOCOL)
    }
    CATEGORIES.forEach { category ->
        CategoryObservationKindV2.entries.forEach { kind ->
            features.add("protocol.category.$category.${kind.name.lowercase(Locale.ROOT)}", FeatureBlock.PROTOCOL)
        }
    }
    WEARABLE_METRICS.forEach { metric ->
        features.add("protocol.wearable.$metric.ambiguous", FeatureBlock.PROTOCOL)
    }
    return features.build()
}

private fun encodeState(payload: StateEvidencePayload, schema: FeatureSchema): EncodedVector {
    val writer = VectorWriter(schema)
    val readings = payload.axes.associateBy { it.axisId }
    val conflicting = payload.conflictingAxisIds.toSet()
    schema.features.filter { it.block == FeatureBlock.STATE_AXIS_VALUE }.forEach { feature ->
        val axisId = feature.name.removePrefix("state.axis.").removeSuffix(".value")
        val reading = readings[axisId]
        writer.set(feature.name, reading?.normalized ?: 0.0, observed = reading != null)
    }
    schema.features.filter { it.name.endsWith(".observed") && it.block == FeatureBlock.STATE_AXIS_MASK }.forEach { feature ->
        val axisId = feature.name.removePrefix("state.axis.").removeSuffix(".observed")
        writer.set(feature.name, if (readings[axisId] != null) 1.0 else 0.0, observed = true)
    }
    schema.features.filter { it.name.endsWith(".conflicting") }.forEach { feature ->
        val axisId = feature.name.removePrefix("state.axis.").removeSuffix(".conflicting")
        writer.set(feature.name, if (axisId in conflicting) 1.0 else 0.0, observed = true)
    }
    val emotion = payload.emotion?.name
    schema.features.filter { it.name.startsWith("state.emotion.present.") }.forEach { feature ->
        val label = feature.name.removePrefix("state.emotion.present.")
        if (emotion == null) {
            writer.set(feature.name, 0.0, observed = false)
        } else {
            writer.set(feature.name, if (label == emotion) 1.0 else 0.0, observed = true)
        }
    }
    writer.set("state.emotion.observed", if (emotion != null) 1.0 else 0.0, observed = true)
    return writer.build()
}

private fun encodeTransition(
    payload: TransitionEvidencePayload,
    wearablesById: Map<String, WearableObservation>,
    schema: FeatureSchema,
): EncodedVector {
    val writer = VectorWriter(schema)
    val hours = payload.temporal.durationMillis.toDouble() / MILLIS_PER_HOUR
    writer.set("transition.duration.hours", hours, observed = true)
    writePresence(
        writer = writer,
        prefix = "transition.activity.present.",
        block = FeatureBlock.TRANSITION_ACTIVITY,
        schema = schema,
        countable = payload.activity.kind.admitsItemCounts(),
        present = payload.activity.items.map { it.key() }.toSet(),
    )
    writeWorkouts(writer, schema, payload.workout)
    writeDiet(writer, schema, payload.diet)
    writeUsage(writer, schema, payload.usage, hours)
    writeWearables(writer, schema, payload.wearables, wearablesById)
    writer.set("protocol.completed", if (payload.temporal.completed) 1.0 else 0.0, observed = true)
    schema.features.filter { it.name.startsWith("protocol.start_basis.") }.forEach { feature ->
        val basis = feature.name.removePrefix("protocol.start_basis.")
        writer.set(feature.name, if (basis == payload.temporal.startBasis) 1.0 else 0.0, observed = true)
    }
    CATEGORIES.forEach { category ->
        val kind = payload.category(category).kind
        CategoryObservationKindV2.entries.forEach { candidate ->
            val name = "protocol.category.$category.${candidate.name.lowercase(Locale.ROOT)}"
            writer.set(name, if (candidate == kind) 1.0 else 0.0, observed = true)
        }
    }
    return writer.build()
}

private fun writePresence(
    writer: VectorWriter,
    prefix: String,
    block: FeatureBlock,
    schema: FeatureSchema,
    countable: Boolean,
    present: Set<String>,
) {
    schema.features.filter { it.block == block && it.name.startsWith(prefix) }.forEach { feature ->
        val key = feature.name.removePrefix(prefix)
        if (!countable) {
            writer.set(feature.name, 0.0, observed = false)
        } else {
            writer.set(feature.name, if (key in present) 1.0 else 0.0, observed = true)
        }
    }
}

private fun writeWorkouts(
    writer: VectorWriter,
    schema: FeatureSchema,
    category: CategoryEvidence<WorkoutEvidence>,
) {
    val minutes = category.items.groupBy { it.key() }.mapValues { (_, items) ->
        items.sumOf { it.durationMinutes }.toDouble()
    }
    schema.features.filter { it.block == FeatureBlock.TRANSITION_WORKOUT }.forEach { feature ->
        if (!category.kind.admitsItemCounts()) {
            writer.set(feature.name, 0.0, observed = false)
            return@forEach
        }
        val present = feature.name.startsWith("transition.workout.present.")
        val key = if (present) {
            feature.name.removePrefix("transition.workout.present.")
        } else {
            feature.name.removePrefix("transition.workout.minutes.")
        }
        val total = minutes[key]
        if (present) {
            writer.set(feature.name, if (total != null) 1.0 else 0.0, observed = true)
        } else {
            writer.set(feature.name, total ?: 0.0, observed = true)
        }
    }
}

private fun writeDiet(
    writer: VectorWriter,
    schema: FeatureSchema,
    category: CategoryEvidence<MealEvidence>,
) {
    val countable = category.kind.admitsItemCounts()
    val sizeCounts = category.items.groupingBy { it.size }.eachCount()
    val foodCounts = category.items.flatMap { it.foods }.groupingBy { it.key() }.eachCount()
    schema.features.filter { it.block == FeatureBlock.TRANSITION_DIET }.forEach { feature ->
        if (!countable) {
            writer.set(feature.name, 0.0, observed = false)
            return@forEach
        }
        when {
            feature.name.startsWith("transition.diet.size.") -> {
                val size = feature.name.removePrefix("transition.diet.size.").removeSuffix(".count")
                writer.set(feature.name, (sizeCounts[size] ?: 0).toDouble(), observed = true)
            }
            feature.name.startsWith("transition.diet.food.present.") -> {
                val key = feature.name.removePrefix("transition.diet.food.present.")
                writer.set(feature.name, if (key in foodCounts) 1.0 else 0.0, observed = true)
            }
            else -> {
                val key = feature.name.removePrefix("transition.diet.food.count.")
                writer.set(feature.name, (foodCounts[key] ?: 0).toDouble(), observed = true)
            }
        }
    }
}

private fun writeUsage(
    writer: VectorWriter,
    schema: FeatureSchema,
    category: CategoryEvidence<UsageEvidence>,
    durationHours: Double,
) {
    val countable = category.kind.admitsItemCounts()
    val grouped = category.items.groupBy { it.key() to it.intent }
    schema.features.filter { it.block == FeatureBlock.TRANSITION_USAGE }.forEach { feature ->
        val parsed = parseUsageFeature(feature.name)
        val bucket = grouped[parsed.key to parsed.intent].orEmpty()
        if (!countable) {
            writer.set(feature.name, 0.0, observed = false)
            return@forEach
        }
        val amounts = bucket.map { UsageAmountParser.parse(it.amountText) }
        when (parsed.field) {
            "entries" -> writer.set(feature.name, bucket.size.toDouble(), observed = true)
            "parsed" -> writer.set(
                feature.name,
                amounts.count { it is UsageAmountParse.Parsed }.toDouble(),
                observed = true,
            )
            "missing" -> writer.set(
                feature.name,
                amounts.count { it is UsageAmountParse.Missing }.toDouble(),
                observed = true,
            )
            "unparsed" -> writer.set(
                feature.name,
                amounts.count { it is UsageAmountParse.Unparsed }.toDouble(),
                observed = true,
            )
            "amount" -> {
                val total = summedAmount(amounts, parsed.unit)
                writer.set(feature.name, total, observed = true)
            }
            "per_hour" -> {
                if (parsed.unit != UNITLESS || durationHours <= 0.0) {
                    writer.set(feature.name, 0.0, observed = false)
                } else {
                    writer.set(feature.name, summedAmount(amounts, UNITLESS) / durationHours, observed = true)
                }
            }
        }
    }
}

private fun summedAmount(amounts: List<UsageAmountParse>, unit: String): Double {
    return amounts.sumOf { parsed ->
        if (parsed is UsageAmountParse.Parsed && unitToken(parsed.unit) == unit) parsed.number else 0.0
    }
}

private fun writeWearables(
    writer: VectorWriter,
    schema: FeatureSchema,
    wearables: List<WearableMetricEvidence>,
    wearablesById: Map<String, WearableObservation>,
) {
    val byMetric = wearables.associateBy { it.metric }
    WEARABLE_METRICS.forEach { metric ->
        val evidence = byMetric[metric]
        val rows = when (evidence) {
            is WearableMetricEvidence.Observed -> evidence.observationIds.map { wearablesById.getValue(it) }
            else -> emptyList()
        }
        val unitsAgree = rows.isNotEmpty() && rows.all { it.unit == expectedUnit(metric) }
        val ambiguous = evidence is WearableMetricEvidence.Observed &&
            (evidence.competingSpans || !unitsAgree || spanMetric(metric) && rows.size > 1)
        writer.set(
            "protocol.wearable.$metric.ambiguous",
            if (ambiguous) 1.0 else 0.0,
            observed = true,
        )
        if (metric == WearableMetric.HEART_RATE_SAMPLE) {
            writeSamples(writer, metric, rows, unitsAgree, ambiguous)
        } else {
            val measured = if (evidence is WearableMetricEvidence.Observed && !ambiguous) {
                rows.singleOrNull()
            } else {
                null
            }
            writer.set(
                "transition.wearable.$metric.value",
                measured?.value ?: 0.0,
                observed = measured != null,
            )
        }
    }
    schema.features.filter { it.name.startsWith("protocol.wearable.") }.forEach { feature ->
        if (!writer.written(feature.name)) writer.set(feature.name, 0.0, observed = true)
    }
}

private fun writeSamples(
    writer: VectorWriter,
    metric: String,
    rows: List<WearableObservation>,
    unitsAgree: Boolean,
    ambiguous: Boolean,
) {
    val canSummarize = rows.isNotEmpty() && unitsAgree && !ambiguous
    writer.set(
        "transition.wearable.$metric.count",
        if (rows.isEmpty() || ambiguous) 0.0 else rows.size.toDouble(),
        observed = rows.isNotEmpty() && !ambiguous,
    )
    val values = rows.map { it.value }
    writer.set(
        "transition.wearable.$metric.mean",
        if (canSummarize) values.average() else 0.0,
        observed = canSummarize,
    )
    writer.set(
        "transition.wearable.$metric.min",
        if (canSummarize) values.min() else 0.0,
        observed = canSummarize,
    )
    writer.set(
        "transition.wearable.$metric.max",
        if (canSummarize) values.max() else 0.0,
        observed = canSummarize,
    )
}

private data class UsageFeatureName(
    val key: String,
    val intent: String,
    val field: String,
    val unit: String,
)

private fun parseUsageFeature(name: String): UsageFeatureName {
    usageAmountName.matchEntire(name)?.let { match ->
        return UsageFeatureName(
            key = match.groupValues[1],
            intent = match.groupValues[2],
            field = match.groupValues[3],
            unit = match.groupValues[4],
        )
    }
    val count = usageCountName.matchEntire(name)
        ?: error("Usage feature name cannot be read: $name")
    return UsageFeatureName(
        key = count.groupValues[1],
        intent = count.groupValues[2],
        field = count.groupValues[3],
        unit = "",
    )
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

    fun add(name: String, block: FeatureBlock) {
        features += FeatureDefinition(features.size, name, block)
    }

    fun build(): FeatureSchema = FeatureSchema(features.toList())
}

private class VectorWriter(private val schema: FeatureSchema) {
    private val values = DoubleArray(schema.size)
    private val observed = BooleanArray(schema.size)
    private val written = BooleanArray(schema.size)

    fun set(name: String, value: Double, observed: Boolean) {
        val index = schema.indexOf(name)
        values[index] = if (observed) value else 0.0
        this.observed[index] = observed
        written[index] = true
    }

    fun written(name: String): Boolean = written[schema.indexOf(name)]

    fun build(): EncodedVector {
        schema.features.forEach { feature ->
            require(written[feature.index]) { "Feature ${feature.name} was not encoded" }
        }
        return EncodedVector(values.toList(), observed.toList())
    }
}

private const val UNITLESS = "unitless"
private val usageCountName = Regex(
    """^transition\.usage\.(.+)\.([^.]+)\.(entries|parsed|missing|unparsed)$""",
)
private val usageAmountName = Regex(
    """^transition\.usage\.(.+)\.([^.]+)\.(amount|per_hour)\.([^.]+)$""",
)
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
