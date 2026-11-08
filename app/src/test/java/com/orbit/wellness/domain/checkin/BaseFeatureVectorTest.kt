package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableMethod
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind
import com.orbit.wellness.data.local.WearableUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseFeatureVectorTest {
    @Test
    fun schemaAndVectorsStayPutWhenEvidenceOrderIsReversed() {
        val forward = sampleEvidence()
        val reversed = forward.copy(
            states = forward.states.reversed(),
            transitions = forward.transitions.reversed().map { frame ->
                frame.copy(
                    evidence = frame.evidence.copy(
                        activity = frame.evidence.activity.copy(items = frame.evidence.activity.items.reversed()),
                        usage = frame.evidence.usage.copy(items = frame.evidence.usage.items.reversed()),
                    ),
                )
            },
            causalExamples = forward.causalExamples.reversed(),
        )
        val first = encodeFeatures(forward)
        val second = encodeFeatures(reversed)
        assertEquals(first.schemas, second.schemas)
        assertEquals(featureNames(first.schemas.state), featureNames(second.schemas.state))
        forward.states.forEach { frame ->
            assertEquals(vector(first, frame.key), vector(second, frame.key))
        }
        forward.transitions.forEach { frame ->
            assertEquals(vector(first, frame.key), vector(second, frame.key))
        }
    }

    @Test
    fun everyIndexNamesAFeatureAndBlock() {
        val encoded = encodeFeatures(sampleEvidence())
        listOf(encoded.schemas.state, encoded.schemas.transition).forEach { schema ->
            schema.features.forEachIndexed { index, feature ->
                assertEquals(index, feature.index)
                assertEquals(feature, schema.definition(index))
                assertEquals(index, schema.indexOf(feature.name))
                assertTrue(feature.name.isNotBlank())
            }
        }
        val state = encoded.states.first().vector
        assertEquals(encoded.schemas.state.size, state.values.size)
        assertEquals(state.values.size, state.observed.size)
    }

    @Test
    fun recordedAxisZeroIsObservedAndAMissingAxisZeroIsNot() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.state
        val recorded = vector(encoded, StateOccurrenceKey("recorded-zero"))
        val missing = vector(encoded, StateOccurrenceKey("missing-mood"))
        assertEquals(0.0, recorded.value(schema, "state.axis.mood.value"), 0.0)
        assertTrue(recorded.observed(schema, "state.axis.mood.value"))
        assertEquals(0.0, missing.value(schema, "state.axis.mood.value"), 0.0)
        assertFalse(missing.observed(schema, "state.axis.mood.value"))
        assertEquals(0.0, missing.value(schema, "state.axis.focus.value"), 0.0)
        assertFalse(missing.observed(schema, "state.axis.focus.value"))
        assertTrue(recorded.observed(schema, "state.axis.focus.value"))
    }

    @Test
    fun conflictingAxesAreNotOrdinaryMissingness() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.state
        val conflict = vector(encoded, StateOccurrenceKey("recorded-zero"))
        val calm = vector(encoded, StateOccurrenceKey("missing-mood"))
        assertFalse(conflict.observed(schema, "state.axis.energy.value"))
        assertEquals(1.0, conflict.value(schema, "state.axis.energy.conflicting"), 0.0)
        assertEquals(0.0, conflict.value(schema, "state.axis.mood.conflicting"), 0.0)
        assertTrue(calm.observed(schema, "state.axis.energy.value"))
        assertEquals(0.0, calm.value(schema, "state.axis.energy.conflicting"), 0.0)
    }

    @Test
    fun anxietyIsNotInverted() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.state
        val high = vector(encoded, StateOccurrenceKey("recorded-zero"))
        assertEquals(1.0, high.value(schema, "state.axis.anxiety.value"), 0.0)
        assertTrue(high.observed(schema, "state.axis.anxiety.value"))
    }

    @Test
    fun emotionLabelsArePresenceBits() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.state
        val names = featureNames(schema).filter { it.startsWith("state.emotion.present.") }
        assertEquals(listOf("state.emotion.present.calm", "state.emotion.present.tense"), names)
        val tense = vector(encoded, StateOccurrenceKey("recorded-zero"))
        val empty = vector(encoded, StateOccurrenceKey("boundary-day"))
        assertEquals(1.0, tense.value(schema, "state.emotion.present.tense"), 0.0)
        assertEquals(0.0, tense.value(schema, "state.emotion.present.calm"), 0.0)
        assertTrue(tense.observed(schema, "state.emotion.present.calm"))
        assertEquals(1.0, tense.value(schema, "state.emotion.observed"), 0.0)
        assertFalse(empty.observed(schema, "state.emotion.present.tense"))
        assertEquals(0.0, empty.value(schema, "state.emotion.observed"), 0.0)
        assertTrue(tense.values.none { it > 1.0 })
    }

    @Test
    fun notesTimestampsAndOccurrenceIdsAreNotFeatures() {
        val evidence = sampleEvidence()
        val encoded = encodeFeatures(evidence)
        val names = featureNames(encoded.schemas.state) + featureNames(encoded.schemas.transition)
        val banned = listOf(
            "note-not-a-feature",
            "trigger-not-a-feature",
            "2099-01-02",
            "recorded-zero",
            "occurrence",
            "secret-row",
        )
        banned.forEach { token -> assertTrue(names.none { it.contains(token) }) }
        val values = encoded.states.flatMap { it.vector.values } + encoded.transitions.flatMap { it.vector.values }
        assertFalse(values.contains(RECORDED_AT.toDouble()))
        assertFalse(values.contains(REPORT_AT.toDouble()))
        assertFalse(values.contains(SORT_ORDER.toDouble()))
    }

    @Test
    fun activityPresenceIgnoresReportTime() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.transition
        val vector = vector(encoded, TransitionOccurrenceKey("reviewed"))
        assertEquals(1.0, vector.value(schema, FeatureSemantic.ActivityPresent(categoricalKey(null, "Stretching"))), 0.0)
        assertTrue(vector.values.none { it == REPORT_AT.toDouble() })
    }

    @Test
    fun workoutPresenceAndMinutesAreSeparate() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.transition
        val vector = vector(encoded, TransitionOccurrenceKey("reviewed"))
        val run = categoricalKey("run", "Run")
        assertEquals(1.0, vector.value(schema, FeatureSemantic.WorkoutPresent(run)), 0.0)
        assertEquals(30.0, vector.value(schema, FeatureSemantic.WorkoutMinutes(run)), 0.0)
    }

    @Test
    fun mealSizeStaysCategoricalAndFoodCountsAreStable() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.transition
        val vector = vector(encoded, TransitionOccurrenceKey("reviewed"))
        assertEquals(2.0, vector.value(schema, "transition.diet.size.large.count"), 0.0)
        assertEquals(1.0, vector.value(schema, "transition.diet.size.snack.count"), 0.0)
        assertEquals(0.0, vector.value(schema, "transition.diet.size.small.count"), 0.0)
        assertTrue(vector.observed(schema, "transition.diet.size.small.count"))
        val oats = categoricalKey("oats", "oats")
        assertEquals(1.0, vector.value(schema, FeatureSemantic.FoodPresent(oats)), 0.0)
        assertEquals(2.0, vector.value(schema, FeatureSemantic.FoodCount(oats)), 0.0)
        assertTrue(featureNames(schema).none { it == "transition.diet.size.value" })
    }

    @Test
    fun usageAggregatesOnlyMatchingIdentityIntentAndUnit() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.transition
        val vector = vector(encoded, TransitionOccurrenceKey("reviewed"))
        val coffee = categoricalKey("coffee", "coffee")
        val cigarette = categoricalKey(null, "cigarette")
        assertEquals(7.0, vector.value(schema, usage(coffee, "COPING", UsageMeasurement.AMOUNT, "unitless")), 0.0)
        assertEquals(3.0, vector.value(schema, usage(coffee, "COPING", UsageMeasurement.PARSED)), 0.0)
        assertEquals(10.0, vector.value(schema, usage(coffee, "COPING", UsageMeasurement.AMOUNT, "mg")), 0.0)
        assertEquals(4.0, vector.value(schema, usage(coffee, "SUPPORTIVE", UsageMeasurement.AMOUNT, "unitless")), 0.0)
        assertEquals(3.0, vector.value(schema, usage(cigarette, "COPING", UsageMeasurement.AMOUNT, "unitless")), 0.0)
        assertEquals("10 mg", sampleEvidence().transitions.first { it.key.raw == "reviewed" }
            .evidence.usage.items.single { it.amountText == "10 mg" }.amountText)
        assertTrue(featureNames(schema).none { it.contains("per_hour.mg") })
    }

    @Test
    fun unitlessAmountsRateOnlyWhenDurationIsPositive() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.transition
        val reviewed = vector(encoded, TransitionOccurrenceKey("reviewed"))
        val stopped = vector(encoded, TransitionOccurrenceKey("zero-duration"))
        val coffee = usage(categoricalKey("coffee", "coffee"), "COPING", UsageMeasurement.PER_HOUR, "unitless")
        val amount = usage(categoricalKey("coffee", "coffee"), "COPING", UsageMeasurement.AMOUNT, "unitless")
        assertEquals(3.5, reviewed.value(schema, coffee), 0.0)
        assertTrue(reviewed.observed(schema, coffee))
        assertEquals(2.0, reviewed.value(schema, "transition.duration.hours"), 0.0)
        assertFalse(stopped.observed(schema, coffee))
        assertTrue(stopped.observed(schema, amount))
        assertFalse(featureNames(schema).any { it.contains("per_hour.mg") })
    }

    @Test
    fun wearableZeroMissingAndCompetingSpansStayDistinct() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.transition
        val vector = vector(encoded, TransitionOccurrenceKey("wearables"))
        assertEquals(0.0, vector.value(schema, "transition.wearable.steps.value"), 0.0)
        assertTrue(vector.observed(schema, "transition.wearable.steps.value"))
        assertEquals(0.0, vector.value(schema, "protocol.wearable.steps.ambiguous"), 0.0)
        assertFalse(vector.observed(schema, "transition.wearable.resting_heart_rate.value"))
        assertEquals(0.0, vector.value(schema, "protocol.wearable.resting_heart_rate.ambiguous"), 0.0)
        assertFalse(vector.observed(schema, "transition.wearable.heart_rate_average.value"))
        assertEquals(1.0, vector.value(schema, "protocol.wearable.heart_rate_average.ambiguous"), 0.0)
        assertFalse(vector.values.contains(140.0))
        assertTrue(featureNames(schema).none { it.contains("sleep") })
    }

    @Test
    fun heartRateSampleSummariesDoNotDependOnRowOrder() {
        val forward = encodeFeatures(sampleEvidence())
        val reversed = encodeFeatures(sampleEvidence(reverseSamples = true))
        val schema = forward.schemas.transition
        val name = "transition.wearable.heart_rate_sample."
        listOf("count", "mean", "min", "max").forEach { stat ->
            assertEquals(
                vector(forward, TransitionOccurrenceKey("wearables")).value(schema, name + stat),
                vector(reversed, TransitionOccurrenceKey("wearables")).value(schema, name + stat),
                0.0,
            )
        }
        val vector = vector(forward, TransitionOccurrenceKey("wearables"))
        assertEquals(2.0, vector.value(schema, name + "count"), 0.0)
        assertEquals(70.0, vector.value(schema, name + "mean"), 0.0)
        assertEquals(60.0, vector.value(schema, name + "min"), 0.0)
        assertEquals(80.0, vector.value(schema, name + "max"), 0.0)
    }

    @Test
    fun categoryKindsStayDistinctWhenItemFeaturesAreZero() {
        val encoded = encodeFeatures(sampleEvidence())
        val schema = encoded.schemas.transition
        val absent = vector(encoded, TransitionOccurrenceKey("unreviewed"))
        val none = vector(encoded, TransitionOccurrenceKey("reviewed"))
        assertEquals(1.0, absent.value(schema, "protocol.category.activity.absent"), 0.0)
        assertEquals(1.0, absent.value(schema, "protocol.category.workout.unreviewed"), 0.0)
        assertEquals(1.0, absent.value(schema, "protocol.category.diet.conflicting"), 0.0)
        assertEquals(1.0, absent.value(schema, "protocol.category.usage.none"), 0.0)
        assertFalse(absent.observed(schema, FeatureSemantic.ActivityPresent(categoricalKey(null, "Stretching"))))
        assertEquals(0.0, none.value(schema, "transition.diet.size.medium.count"), 0.0)
        assertTrue(none.observed(schema, "transition.diet.size.medium.count"))
        assertEquals(1.0, none.value(schema, "protocol.category.diet.entries"), 0.0)
        assertEquals(0.0, none.value(schema, "protocol.category.diet.none"), 0.0)
    }

    @Test
    fun causalInputOmitsTheTargetAndEligibleExamplesDropIneligibleSteps() {
        val encoded = encodeFeatures(sampleEvidence())
        val eligible = encoded.eligibleCausalExamples.single()
        val held = encoded.causalExamples.single { !it.eligibility.eligible }
        assertEquals(TransitionOccurrenceKey("reviewed"), eligible.exampleKey)
        assertEquals(TransitionOccurrenceKey("boundary-day"), held.exampleKey)
        assertEquals(listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL), held.eligibility.reasons)
        assertNull(held.startingState)
        assertTrue(encoded.eligibleCausalExamples.none { it.exampleKey == held.exampleKey })
        val schema = encoded.schemas.state
        val start = checkNotNull(eligible.startingState)
        assertEquals(0.2, start.value(schema, "state.axis.anxiety.value"), 0.0)
        assertEquals(1.0, eligible.target.value(schema, "state.axis.anxiety.value"), 0.0)
        val transitionNames = featureNames(encoded.schemas.transition)
        assertTrue(encoded.schemas.transition.features.none {
            it.block == FeatureBlock.STATE_AXIS_VALUE || it.block == FeatureBlock.STATE_EMOTION
        })
        assertTrue(transitionNames.none { it.contains("delta") || it.contains("retrospective") })
        assertTrue(transitionNames.none { it.replace("unreviewed", "").contains(eligible.exampleKey.raw) })
        assertTrue(featureNames(encoded.schemas.state).none { it.contains("reviewed") || it.contains("delta") })
    }

    private fun sampleEvidence(reverseSamples: Boolean = false): ContextualEvidence {
        val recorded = state(
            id = "recorded-zero",
            axes = listOf(
                axis("mood", 0, 0.0),
                axis("anxiety", 10, 1.0),
                axis("focus", 3, 0.3),
            ),
            conflicting = listOf("energy"),
            emotion = "tense",
            note = "note-not-a-feature",
        )
        val other = state(
            id = "missing-mood",
            axes = listOf(axis("energy", 5, 0.5), axis("anxiety", 2, 0.2)),
            emotion = "calm",
        )
        val boundary = state(id = "boundary-day")
        val reviewed = transition(
            id = "reviewed",
            durationMillis = 7_200_000L,
            activity = CategoryEvidence(
                CategoryObservationKindV2.ENTRIES,
                listOf(ActivityEvidence(optionId = null, name = "Stretching", reportedAt = REPORT_AT)),
            ),
            workout = CategoryEvidence(
                CategoryObservationKindV2.ENTRIES,
                listOf(
                    WorkoutEvidence("run", "Run", 10, REPORT_AT),
                    WorkoutEvidence("run", "Run", 20, REPORT_AT),
                ),
            ),
            diet = CategoryEvidence(
                CategoryObservationKindV2.ENTRIES,
                listOf(
                    meal(CheckInMealSize.LARGE, listOf(FoodEvidence("oats", "oats"))),
                    meal(CheckInMealSize.LARGE, listOf(FoodEvidence("oats", "oats"))),
                    meal(CheckInMealSize.SNACK, emptyList()),
                ),
            ),
            usage = CategoryEvidence(
                CategoryObservationKindV2.ENTRIES,
                listOf(
                    usage("coffee", "COPING", "3"),
                    usage("coffee", "COPING", "4"),
                    usage("coffee", "COPING", "10 mg"),
                    usage("coffee", "SUPPORTIVE", "4"),
                    usage(null, "cigarette", "COPING", "3"),
                ),
            ),
        )
        val stopped = transition(
            id = "zero-duration",
            durationMillis = 0L,
            usage = CategoryEvidence(
                CategoryObservationKindV2.ENTRIES,
                listOf(usage("coffee", "COPING", "3")),
            ),
        )
        val kinds = transition(
            id = "unreviewed",
            activity = CategoryEvidence(CategoryObservationKindV2.ABSENT, emptyList()),
            workout = CategoryEvidence(CategoryObservationKindV2.UNREVIEWED, emptyList()),
            diet = CategoryEvidence(CategoryObservationKindV2.CONFLICTING, emptyList()),
            usage = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        )
        val samples = listOf(sample(60.0, "low"), sample(80.0, "high"))
        val wearableRows = listOf(steps(0.0), average(40.0, "one"), average(100.0, "two")) +
            if (reverseSamples) samples.reversed() else samples
        val wearableFrame = transition(
            id = "wearables",
            wearables = listOf(
                WearableMetricEvidence.Observed(WearableMetric.STEPS, listOf(steps(0.0).id), false),
                WearableMetricEvidence.Unobserved(WearableMetric.RESTING_HEART_RATE),
                WearableMetricEvidence.Observed(
                    WearableMetric.HEART_RATE_AVERAGE,
                    listOf(average(40.0, "one").id, average(100.0, "two").id),
                    competingSpans = true,
                ),
                WearableMetricEvidence.Observed(
                    WearableMetric.HEART_RATE_SAMPLE,
                    (if (reverseSamples) samples.reversed() else samples).map { it.id },
                    competingSpans = false,
                ),
            ),
        )
        val eligible = CausalEvidenceExample(
            exampleKey = TransitionOccurrenceKey("reviewed"),
            input = CausalEvidenceInput(other.evidence, reviewed.evidence),
            target = CausalEvidenceTarget(recorded.evidence),
            eligibility = CausalEligibility(true, emptyList()),
        )
        val ineligible = CausalEvidenceExample(
            exampleKey = TransitionOccurrenceKey("boundary-day"),
            input = CausalEvidenceInput(null, kinds.evidence),
            target = CausalEvidenceTarget(boundary.evidence),
            eligibility = CausalEligibility(false, listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL)),
        )
        return ContextualEvidence(
            states = listOf(recorded, other, boundary),
            transitions = listOf(reviewed, stopped, kinds, wearableFrame),
            statesByKey = listOf(recorded, other, boundary).associateBy { it.key },
            transitionsByKey = listOf(reviewed, stopped, kinds, wearableFrame).associateBy { it.key },
            wearablesById = wearableRows.associateBy { it.id },
            retrospectiveStates = emptyList(),
            retrospectiveTransitions = emptyList(),
            causalExamples = listOf(eligible, ineligible),
            issues = emptyList(),
        )
    }

    private fun vector(encoded: EncodedFeatures, key: StateOccurrenceKey): EncodedVector {
        return encoded.states.single { it.key == key }.vector
    }

    private fun vector(encoded: EncodedFeatures, key: TransitionOccurrenceKey): EncodedVector {
        return encoded.transitions.single { it.key == key }.vector
    }

    private fun featureNames(schema: FeatureSchema): List<String> = schema.features.map { it.name }

    private fun state(
        id: String,
        axes: List<AxisEvidence> = emptyList(),
        conflicting: List<String> = emptyList(),
        emotion: String? = null,
        note: String = "",
    ) = StateEvidenceFrame(
        key = StateOccurrenceKey(id),
        evidence = StateEvidencePayload(
            axes = axes,
            coreMissingAxisIds = listOf("mood", "energy", "confidence", "anxiety").filter { core ->
                axes.none { it.axisId == core } && core !in conflicting
            },
            conflictingAxisIds = conflicting,
            emotion = emotion?.let(::EmotionToken),
        ),
        metadata = StateEvidenceMetadata(
            recordedAt = RECORDED_AT,
            localDate = "2099-01-02",
            trigger = "trigger-not-a-feature",
            note = note,
        ),
    )

    private fun axis(id: String, raw: Int, normalized: Double) = AxisEvidence(id, raw, normalized)

    private fun transition(
        id: String,
        durationMillis: Long = 3_600_000L,
        activity: CategoryEvidence<ActivityEvidence> = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        workout: CategoryEvidence<WorkoutEvidence> = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        diet: CategoryEvidence<MealEvidence> = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        usage: CategoryEvidence<UsageEvidence> = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        wearables: List<WearableMetricEvidence> = IntervalWearableSelection.METRICS.map {
            WearableMetricEvidence.Unobserved(it)
        },
        basis: String = "previous_check_in",
    ) = TransitionEvidenceFrame(
        key = TransitionOccurrenceKey(id),
        evidence = TransitionEvidencePayload(
            temporal = TemporalEvidence(
                startAt = 10L,
                endAt = 10L + durationMillis,
                durationMillis = durationMillis,
                startBasis = basis,
                completed = true,
            ),
            activity = activity,
            workout = workout,
            diet = diet,
            usage = usage,
            wearables = wearables,
        ),
        metadata = TransitionEvidenceMetadata(completedAt = 50L),
    )

    private fun meal(size: String, foods: List<FoodEvidence>) = MealEvidence(
        size = size,
        foods = foods,
        ordering = MealOrder(sortOrder = SORT_ORDER, reportedAt = REPORT_AT),
    )

    private fun usage(optionId: String, intent: String, amount: String) = usage(optionId, optionId, intent, amount)

    private fun usage(optionId: String?, name: String, intent: String, amount: String) = UsageEvidence(
        optionId = optionId,
        name = name,
        intent = intent,
        amountText = amount,
        reportedAt = REPORT_AT,
    )

    private fun steps(value: Double) = wearable(
        metric = WearableMetric.STEPS,
        value = value,
        unit = WearableUnit.COUNT,
        start = 0L,
        end = 1_000L,
        kind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = WearableMethod.AGGREGATE_TOTAL,
        recordId = "steps-$value",
    )

    private fun average(value: Double, recordId: String) = wearable(
        metric = WearableMetric.HEART_RATE_AVERAGE,
        value = value,
        unit = WearableUnit.BEATS_PER_MINUTE,
        start = 0L,
        end = 1_000L,
        kind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = WearableMethod.HEART_RATE_AVERAGE,
        recordId = recordId,
    )

    private fun sample(value: Double, recordId: String) = wearable(
        metric = WearableMetric.HEART_RATE_SAMPLE,
        value = value,
        unit = WearableUnit.BEATS_PER_MINUTE,
        start = value.toLong(),
        end = value.toLong(),
        kind = WearableTemporalKind.POINT,
        method = WearableMethod.HEART_RATE_SAMPLE,
        recordId = recordId,
    )

    private fun wearable(
        metric: String,
        value: Double,
        unit: String,
        start: Long,
        end: Long,
        kind: String,
        method: String,
        recordId: String,
    ) = WearableObservation.create(
        metric = metric,
        value = value,
        unit = unit,
        observedStartAt = start,
        observedEndAt = end,
        temporalKind = kind,
        method = method,
        sourceRecordId = recordId,
        importedAt = 1L,
    )

    private fun usage(
        identity: String,
        intent: String,
        measurement: UsageMeasurement,
        unit: String? = null,
    ) = FeatureSemantic.Usage(identity, intent, measurement, unit)

    private fun EncodedVector.value(schema: FeatureSchema, name: String): Double = values[schema.indexOf(name)]

    private fun EncodedVector.value(schema: FeatureSchema, semantic: FeatureSemantic): Double =
        values[schema.indexOf(semantic)]

    private fun EncodedVector.observed(schema: FeatureSchema, name: String): Boolean = observed[schema.indexOf(name)]

    private fun EncodedVector.observed(schema: FeatureSchema, semantic: FeatureSemantic): Boolean =
        observed[schema.indexOf(semantic)]

    private object CheckInMealSize {
        const val SNACK = "snack"
        const val LARGE = "large"
    }

    private companion object {
        const val RECORDED_AT = 1_700_000_000_123L
        const val REPORT_AT = 424_242L
        const val SORT_ORDER = 17
    }
}
