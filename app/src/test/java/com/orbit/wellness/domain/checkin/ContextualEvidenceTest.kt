package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.CheckInIntervalWearables
import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableMethod
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind
import com.orbit.wellness.data.local.WearableUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualEvidenceTest {
    @Test
    fun stateAndTransitionKeysWithTheSameStringStayDistinctTypes() {
        val stateKey = StateOccurrenceKey("shared")
        val transitionKey = TransitionOccurrenceKey("shared")
        assertEquals(stateKey.raw, transitionKey.raw)
        assertEquals("state", role(stateKey))
        assertEquals("transition", role(transitionKey))
        assertFalse(stateKey.equals(transitionKey))
        assertNotEquals(StateOccurrenceKey::class, TransitionOccurrenceKey::class)
    }

    @Test
    fun identicalEvidenceStaysTwoOccurrenceFrames() {
        val evidence = evidenceFor(
            checkIns = listOf(
                checkIn("second", 2_000, emotion = "calm"),
                checkIn("first", 1_000, emotion = "calm"),
            ),
            axisValues = listOf(
                axis("first", DefaultCheckInAxes.MOOD, 4),
                axis("second", DefaultCheckInAxes.MOOD, 4),
            ),
            windows = listOf(
                window("first", 1_000),
                previousWindow("second", end = 2_000, start = 1_000, previous = "first"),
            ),
        )
        val first = evidence.statesByKey.getValue(StateOccurrenceKey("first"))
        val second = evidence.statesByKey.getValue(StateOccurrenceKey("second"))
        assertEquals(first.evidence, second.evidence)
        assertNotEquals(first.key, second.key)
        assertNotEquals(first, second)
        val earlier = evidence.retrospectiveStates.single { it.frame.key == first.key }
        val later = evidence.retrospectiveStates.single { it.frame.key == second.key }
        assertNotEquals(earlier.outgoingTransitionKeys, later.outgoingTransitionKeys)
        assertNotEquals(earlier.incomingTransitionKeys, later.incomingTransitionKeys)
    }

    @Test
    fun recordedZeroMissingAndConflictingAxesStayDistinct() {
        val frame = evidenceFor(
            checkIns = listOf(checkIn("a", 1_000)),
            axisValues = listOf(
                axis("a", DefaultCheckInAxes.MOOD, 0),
                axis("a", DefaultCheckInAxes.ENERGY, 2),
                axis("a", DefaultCheckInAxes.ENERGY, 9),
                axis("a", "focus", 3),
            ),
            windows = listOf(window("a", 1_000)),
        ).states.single()
        val mood = frame.evidence.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(0, mood.raw)
        assertEquals(0.0, mood.normalized, 0.0)
        assertEquals(3, frame.evidence.axes.single { it.axisId == "focus" }.raw)
        assertFalse(frame.evidence.axes.any { it.axisId == DefaultCheckInAxes.ENERGY })
        assertEquals(listOf(DefaultCheckInAxes.ENERGY), frame.evidence.conflictingAxisIds)
        assertEquals(
            listOf(DefaultCheckInAxes.CONFIDENCE, DefaultCheckInAxes.ANXIETY),
            frame.evidence.coreMissingAxisIds,
        )
        assertFalse(frame.evidence.coreMissingAxisIds.contains(DefaultCheckInAxes.ENERGY))
        assertFalse(frame.evidence.coreMissingAxisIds.contains(DefaultCheckInAxes.MOOD))
    }

    @Test
    fun emotionStaysCategoricalAndAnxietyStaysUninverted() {
        val payload = evidenceFor(
            checkIns = listOf(checkIn("a", 1_000, emotion = "tense")),
            axisValues = listOf(axis("a", DefaultCheckInAxes.ANXIETY, 10)),
            windows = listOf(window("a", 1_000)),
        ).states.single().evidence
        assertEquals(EmotionToken("tense"), payload.emotion)
        assertEquals(String::class.java, EmotionToken::class.java.getDeclaredField("name").type)
        val anxiety = payload.axes.single()
        assertEquals(DefaultCheckInAxes.ANXIETY, anxiety.axisId)
        assertEquals(10, anxiety.raw)
        assertEquals(1.0, anxiety.normalized, 0.0)
        val empty = evidenceFor(
            checkIns = listOf(checkIn("b", 1_000, emotion = "")),
            windows = listOf(window("b", 1_000)),
        ).states.single().evidence
        assertNull(empty.emotion)
    }

    @Test
    fun aNoteStaysMetadataAndStaysOutOfTheCausalInput() {
        val evidence = linkedEvidence(
            startId = "start-check-in",
            endId = "end-check-in",
            startEmotion = "starting-emotion",
            endEmotion = "ending-emotion",
            startNote = "note-only-metadata",
        )
        val starting = evidence.statesByKey.getValue(StateOccurrenceKey("start-check-in"))
        assertEquals("note-only-metadata", starting.metadata.note)
        assertFalse(fieldNames(StateEvidencePayload::class.java).contains("note"))
        val example = evidence.causalExamples.single { it.exampleKey.raw == "end-check-in" }
        assertEquals("starting-emotion", example.input.startingState!!.emotion!!.name)
        assertFalse(example.input.toString().contains("note-only-metadata"))
        assertFalse(example.input.toString().contains("ending-emotion"))
        assertFalse(example.input.toString().contains("end-check-in"))
        assertEquals("ending-emotion", example.target.endingState.emotion!!.name)
    }

    @Test
    fun categoryKindsStayDistinct() {
        val evidence = evidenceFor(
            checkIns = listOf(checkIn("plain", 1_000), checkIn("rich", 2_000)),
            windows = listOf(window("plain", 1_000), window("rich", 2_000)),
            categories = noneCategories("plain") + listOf(
                category("rich", CheckInContextCategories.ACTIVITY, CheckInContextStatus.ENTRIES),
                category("rich", CheckInContextCategories.WORKOUT, CheckInContextStatus.NONE),
                category("rich", CheckInContextCategories.WORKOUT, CheckInContextStatus.ENTRIES),
                category("rich", CheckInContextCategories.USAGE, CheckInContextStatus.UNREVIEWED),
            ),
            activities = listOf(
                CheckInActivityItem(
                    id = "act-1",
                    checkInId = "rich",
                    activityOptionId = null,
                    activityName = "stretching",
                    reportedAt = 1_500L,
                ),
            ),
        )
        val plain = evidence.transitionsByKey.getValue(TransitionOccurrenceKey("plain"))
        val rich = evidence.transitionsByKey.getValue(TransitionOccurrenceKey("rich"))
        assertEquals(CategoryObservationKindV2.NONE, plain.evidence.activity.kind)
        assertEquals(CategoryObservationKindV2.ENTRIES, rich.evidence.activity.kind)
        assertEquals(CategoryObservationKindV2.CONFLICTING, rich.evidence.workout.kind)
        assertEquals(CategoryObservationKindV2.ABSENT, rich.evidence.diet.kind)
        assertEquals(CategoryObservationKindV2.UNREVIEWED, rich.evidence.usage.kind)
        assertNotEquals(plain.evidence.activity.kind, rich.evidence.diet.kind)
        val custom = rich.evidence.activity.items.single()
        assertNull(custom.optionId)
        assertEquals("stretching", custom.name)
    }

    @Test
    fun mealSizeStaysATokenAndUsageAmountStaysRawText() {
        val evidence = evidenceFor(
            checkIns = listOf(checkIn("a", 2_000)),
            windows = listOf(window("a", 2_000)),
            categories = listOf(
                category("a", CheckInContextCategories.ACTIVITY, CheckInContextStatus.NONE),
                category("a", CheckInContextCategories.WORKOUT, CheckInContextStatus.NONE),
                category("a", CheckInContextCategories.DIET, CheckInContextStatus.ENTRIES),
                category("a", CheckInContextCategories.USAGE, CheckInContextStatus.ENTRIES),
            ),
            meals = listOf(
                CheckInMeal(
                    id = "meal-1",
                    checkInId = "a",
                    size = CheckInMeal.LARGE,
                    sortOrder = 1,
                    reportedAt = 1_600L,
                ),
            ),
            foods = listOf(
                CheckInMealFoodItem(
                    id = "food-1",
                    mealId = "meal-1",
                    foodOptionId = "oats",
                    foodName = "oats",
                    sortOrder = 0,
                ),
            ),
            usage = listOf(
                CheckInUsageItem(
                    id = "use-1",
                    checkInId = "a",
                    substanceOptionId = null,
                    name = "coffee",
                    intent = SubstanceIntent.COPING.name,
                    amount = "3",
                    reportedAt = 1_700L,
                ),
            ),
        )
        val meal = evidence.transitions.single().evidence.diet.items.single()
        assertEquals(CheckInMeal.LARGE, meal.size)
        assertEquals(String::class.java, MealEvidence::class.java.getDeclaredField("size").type)
        assertFalse(fieldNames(MealEvidence::class.java).any { it.contains("rank") || it.contains("ordinal") })
        assertEquals(1, meal.ordering.sortOrder)
        assertEquals("oats", meal.foods.single().optionId)
        val use = evidence.transitions.single().evidence.usage.items.single()
        assertEquals("3", use.amountText)
        assertEquals(String::class.java, UsageEvidence::class.java.getDeclaredField("amountText").type)
        assertEquals(SubstanceIntent.COPING.name, use.intent)
        assertNull(use.optionId)
    }

    @Test
    fun exactWearableCaptureReplacesASmallerSpanAndDropsAWiderTotal() {
        val start = 1_000L
        val end = 5_000L
        val exact = steps(start, end, 800.0, WearableMethod.AGGREGATE_TOTAL)
        val partial = steps(1_200L, 2_000L, 100.0, WearableMethod.STEPS_RECORD)
        val wider = steps(0L, 9_000L, 4_000.0, WearableMethod.AGGREGATE_TOTAL)
        val observations = listOf(wider, partial, exact)
        val evidence = intervalEvidence(start, end, observations)
        val stepsEvidence = observed(evidence, WearableMetric.STEPS)
        assertEquals(listOf(exact.id), stepsEvidence.observationIds)
        assertFalse(stepsEvidence.competingSpans)
        assertSame(exact, evidence.wearablesById.getValue(exact.id))
        assertFalse(evidence.wearablesById.containsKey(partial.id))
        assertFalse(evidence.wearablesById.containsKey(wider.id))
        assertEquals(historyIds(start, end, observations), selectedIds(evidence))
    }

    @Test
    fun containedSpansWithoutAnExactCaptureStayUnsummed() {
        val start = 1_000L
        val end = 5_000L
        val first = steps(1_200L, 2_000L, 100.0, WearableMethod.STEPS_RECORD)
        val second = steps(2_100L, 3_000L, 40.0, WearableMethod.STEPS_RECORD)
        val evidence = intervalEvidence(start, end, listOf(second, first))
        val stepsEvidence = observed(evidence, WearableMetric.STEPS)
        assertEquals(listOf(first.id, second.id), stepsEvidence.observationIds)
        assertTrue(stepsEvidence.competingSpans)
        assertFalse(fieldNames(WearableMetricEvidence.Observed::class.java).contains("value"))
    }

    @Test
    fun aStoredZeroStaysObservedAndAMissingMetricStaysUnobserved() {
        val start = 1_000L
        val end = 5_000L
        val zero = steps(start, end, 0.0, WearableMethod.AGGREGATE_TOTAL)
        val evidence = intervalEvidence(start, end, listOf(zero))
        val stepsEvidence = observed(evidence, WearableMetric.STEPS)
        assertEquals(listOf(zero.id), stepsEvidence.observationIds)
        assertEquals(0.0, evidence.wearablesById.getValue(zero.id).value, 0.0)
        assertTrue(
            evidence.transitions.single().evidence.wearables
                .filterIsInstance<WearableMetricEvidence.Unobserved>()
                .any { it.metric == WearableMetric.HEART_RATE_AVERAGE },
        )
        assertTrue(evidence.wearablesById.values.none { it.metric == WearableMetric.HEART_RATE_AVERAGE })
    }

    @Test
    fun restingHeartRateSelectionIsDeterministic() {
        val start = 1_000L
        val end = 5_000L
        val earlier = resting(2_000L, 60.0, "yesterday")
        val later = resting(4_000L, 62.0, "today")
        val sameTime = resting(4_000L, 62.0, "today-again")
        val forward = listOf(earlier, sameTime, later)
        val reversed = forward.reversed()
        val first = intervalEvidence(start, end, forward)
        val second = intervalEvidence(start, end, reversed)
        val chosen = observed(first, WearableMetric.RESTING_HEART_RATE).observationIds
        assertEquals(1, chosen.size)
        assertEquals(chosen, observed(second, WearableMetric.RESTING_HEART_RATE).observationIds)
        assertEquals(historyIds(start, end, forward), selectedIds(first))
        assertEquals(historyIds(start, end, reversed), selectedIds(second))
    }

    @Test
    fun aBoundaryTransitionHasNoStartingStateEvidence() {
        val evidence = evidenceFor(
            checkIns = listOf(checkIn("day", 2_000, emotion = "awake")),
            windows = listOf(window("day", 2_000, basis = CheckInContextStartBasis.DAY_START)),
        )
        val retrospective = evidence.retrospectiveTransitions.single()
        assertNull(retrospective.startStateKey)
        assertEquals(StateOccurrenceKey("day"), retrospective.endStateKey)
        val example = evidence.causalExamples.single()
        assertNull(example.input.startingState)
        assertEquals(listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL), example.eligibility.reasons)
        assertEquals("awake", example.target.endingState.emotion!!.name)
    }

    @Test
    fun aRetrospectiveTransitionNamesBothEndpoints() {
        val evidence = linkedEvidence("a", "b")
        val retrospective = evidence.retrospectiveTransitions.single { it.frame.key.raw == "b" }
        assertEquals(StateOccurrenceKey("a"), retrospective.startStateKey)
        assertEquals(StateOccurrenceKey("b"), retrospective.endStateKey)
        assertEquals(listOf(TransitionOccurrenceKey("b")), evidence.retrospectiveStates.single { it.frame.key.raw == "a" }.outgoingTransitionKeys)
    }

    @Test
    fun causalInputExcludesEndingEvidenceAndNeighborhoodData() {
        val evidence = linkedEvidence(
            startId = "start-check-in",
            endId = "end-check-in",
            startEmotion = "starting-emotion",
            endEmotion = "ending-emotion",
        )
        val example = evidence.causalExamples.single { it.exampleKey.raw == "end-check-in" }
        assertEquals(
            setOf("startingState", "transition"),
            fieldNames(CausalEvidenceInput::class.java),
        )
        val inputTypes = CausalEvidenceInput::class.java.declaredFields
            .filterNot { it.name.startsWith("$") || it.name == "serialVersionUID" }
            .map { it.type }
        assertFalse(inputTypes.contains(StateOccurrenceKey::class.java))
        assertFalse(inputTypes.contains(TransitionOccurrenceKey::class.java))
        assertFalse(inputTypes.contains(StateTransitionObservationV2::class.java))
        assertFalse(inputTypes.contains(AxisDeltaV2::class.java))
        assertFalse(inputTypes.contains(RetrospectiveNeighborhood::class.java))
        assertFalse(inputTypes.contains(RetrospectiveTransitionEvidence::class.java))
        assertFalse(inputTypes.contains(CausalEvidenceTarget::class.java))
        assertEquals("starting-emotion", example.input.startingState!!.emotion!!.name)
        assertFalse(example.input.toString().contains("ending-emotion"))
        assertFalse(example.input.toString().contains("end-check-in"))
        assertSame(
            evidence.statesByKey.getValue(StateOccurrenceKey("start-check-in")).evidence,
            example.input.startingState,
        )
        assertSame(
            evidence.statesByKey.getValue(StateOccurrenceKey("end-check-in")).evidence,
            example.target.endingState,
        )
    }

    @Test
    fun ineligibleStepsKeepTheGraphReasons() {
        val built = built(
            checkIns = listOf(checkIn("b", 2_000), checkIn("a", 1_000)),
            windows = listOf(
                window("a", 1_000),
                previousWindow("b", end = 2_000, start = 1_000, previous = "a", completedAt = null),
            ),
        )
        val graph = projectAlternatingTemporalGraph(built)
        val evidence = projectContextualEvidence(graph)
        assertEquals(
            graph.causalSteps.map { it.eligibility },
            evidence.causalExamples.map { it.eligibility },
        )
        val incomplete = evidence.causalExamples.single { it.exampleKey.raw == "b" }
        assertEquals(listOf(CausalIneligibilityReason.INCOMPLETE_CONTEXT), incomplete.eligibility.reasons)
        assertFalse(incomplete.input.transition.temporal.completed)
        assertEquals("a", evidence.retrospectiveTransitions.single { it.endStateKey.raw == "b" }.startStateKey!!.raw)
    }

    @Test
    fun reversedSourcesKeepTheSameEvidenceOrder() {
        val checks = listOf(checkIn("m", 1_000), checkIn("a", 1_000))
        val windows = listOf(
            window("m", 1_000, basis = CheckInContextStartBasis.DAY_START),
            window("a", 1_000),
        )
        val readings = listOf(
            axis("m", DefaultCheckInAxes.MOOD, 2),
            axis("a", DefaultCheckInAxes.MOOD, 6),
        )
        val observations = listOf(
            steps(0L, 1_000L, 10.0, WearableMethod.AGGREGATE_TOTAL),
            steps(0L, 1_000L, 4.0, WearableMethod.AGGREGATE_TOTAL, sourceRecordId = "other-window"),
        )
        val forward = evidenceFor(
            checkIns = checks,
            windows = windows,
            axisValues = readings,
            wearables = observations,
        )
        val reversed = evidenceFor(
            checkIns = checks.reversed(),
            windows = windows.reversed(),
            axisValues = readings.reversed(),
            wearables = observations.reversed(),
        )
        assertEquals(forward, reversed)
        assertEquals(listOf("a", "m"), forward.states.map { it.key.raw })
    }

    @Test
    fun projectionIndexesAliasTheFramesAndSelectedWearables() {
        val start = 1_000L
        val end = 5_000L
        val exact = steps(start, end, 12.0, WearableMethod.AGGREGATE_TOTAL)
        val evidence = intervalEvidence(start, end, listOf(exact))
        assertEquals(evidence.states.size, evidence.statesByKey.size)
        evidence.states.forEach { frame ->
            assertSame(frame, evidence.statesByKey.getValue(frame.key))
        }
        evidence.transitions.forEach { frame ->
            assertSame(frame, evidence.transitionsByKey.getValue(frame.key))
        }
        assertSame(exact, evidence.wearablesById.getValue(exact.id))
        assertSame(
            evidence.states.single().evidence,
            evidence.causalExamples.single().target.endingState,
        )
    }

    private fun role(key: StateOccurrenceKey) = "state"

    private fun role(key: TransitionOccurrenceKey) = "transition"

    private fun intervalEvidence(
        start: Long,
        end: Long,
        observations: List<WearableObservation>,
    ): ContextualEvidence {
        return evidenceFor(
            checkIns = listOf(checkIn("interval", end)),
            windows = listOf(window("interval", end = end, start = start)),
            wearables = observations,
        )
    }

    private fun linkedEvidence(
        startId: String,
        endId: String,
        startEmotion: String = "",
        endEmotion: String = "",
        startNote: String = "",
    ) = evidenceFor(
        checkIns = listOf(
            checkIn(endId, 2_000, emotion = endEmotion),
            checkIn(startId, 1_000, emotion = startEmotion, note = startNote),
        ),
        windows = listOf(
            window(startId, 1_000),
            previousWindow(endId, end = 2_000, start = 1_000, previous = startId),
        ),
    )

    private fun evidenceFor(
        checkIns: List<CheckInRecord>,
        windows: List<CheckInContextWindow>,
        axisValues: List<CheckInAxisValue> = emptyList(),
        categories: List<CheckInContextCategory>? = null,
        activities: List<CheckInActivityItem> = emptyList(),
        meals: List<CheckInMeal> = emptyList(),
        foods: List<CheckInMealFoodItem> = emptyList(),
        usage: List<CheckInUsageItem> = emptyList(),
        wearables: List<WearableObservation> = emptyList(),
    ): ContextualEvidence {
        return projectContextualEvidence(projectAlternatingTemporalGraph(built(
            checkIns = checkIns,
            windows = windows,
            axisValues = axisValues,
            categories = categories,
            activities = activities,
            meals = meals,
            foods = foods,
            usage = usage,
        )), wearables)
    }

    private fun built(
        checkIns: List<CheckInRecord>,
        windows: List<CheckInContextWindow>,
        axisValues: List<CheckInAxisValue> = emptyList(),
        categories: List<CheckInContextCategory>? = null,
        activities: List<CheckInActivityItem> = emptyList(),
        meals: List<CheckInMeal> = emptyList(),
        foods: List<CheckInMealFoodItem> = emptyList(),
        usage: List<CheckInUsageItem> = emptyList(),
    ): CheckInAnalysisDatasetV2 {
        return CheckInAnalysisDatasetBuilder.build(
            checkIns = checkIns,
            axisValues = axisValues,
            windows = windows,
            categories = categories ?: checkIns.flatMap { noneCategories(it.id) },
            activities = activities,
            meals = meals,
            foods = foods,
            usage = usage,
        )
    }

    private fun observed(evidence: ContextualEvidence, metric: String): WearableMetricEvidence.Observed {
        return evidence.transitions.single().evidence.wearables
            .filterIsInstance<WearableMetricEvidence.Observed>()
            .single { it.metric == metric }
    }

    private fun selectedIds(evidence: ContextualEvidence): List<String> {
        return evidence.transitions.single().evidence.wearables
            .filterIsInstance<WearableMetricEvidence.Observed>()
            .flatMap { it.observationIds }
    }

    private fun historyIds(start: Long, end: Long, observations: List<WearableObservation>): List<String> {
        return CheckInIntervalWearables.forInterval(start, end, observations).map { it.id }
    }

    private fun checkIn(
        id: String,
        at: Long,
        emotion: String = "",
        note: String = "",
    ) = CheckInRecord(
        id = id,
        localDate = DAY,
        recordedAt = at,
        trigger = CheckInRecord.TRIGGER_MANUAL,
        note = note,
        emotion = emotion,
    )

    private fun window(
        id: String,
        end: Long,
        start: Long = 0L,
        basis: String = CheckInContextStartBasis.WAKE_TIME,
        completedAt: Long? = 10L,
    ) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = null,
        windowStartAt = start,
        windowEndAt = end,
        startBasis = basis,
        completedAt = completedAt,
    )

    private fun previousWindow(
        id: String,
        end: Long,
        start: Long,
        previous: String,
        completedAt: Long? = 10L,
    ) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = end,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = completedAt,
    )

    private fun noneCategories(id: String) = CheckInContextCategories.ALL.map { name ->
        category(id, name, CheckInContextStatus.NONE)
    }

    private fun category(checkInId: String, name: String, status: String) = CheckInContextCategory(
        checkInId = checkInId,
        category = name,
        status = status,
    )

    private fun axis(checkInId: String, axisId: String, value: Int) = CheckInAxisValue(checkInId, axisId, value)

    private fun steps(
        start: Long,
        end: Long,
        value: Double,
        method: String,
        sourceRecordId: String? = null,
    ) = WearableObservation.create(
        metric = WearableMetric.STEPS,
        value = value,
        unit = WearableUnit.COUNT,
        observedStartAt = start,
        observedEndAt = end,
        temporalKind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = method,
        sourceRecordId = sourceRecordId,
        importedAt = end,
    )

    private fun resting(at: Long, value: Double, recordId: String) = WearableObservation.create(
        metric = WearableMetric.RESTING_HEART_RATE,
        value = value,
        unit = WearableUnit.BEATS_PER_MINUTE,
        observedStartAt = at,
        observedEndAt = at,
        temporalKind = WearableTemporalKind.POINT,
        method = WearableMethod.RESTING_HEART_RATE_RECORD,
        sourceRecordId = recordId,
        importedAt = at,
    )

    private companion object {
        const val DAY = "2026-11-08"

        fun fieldNames(type: Class<*>): Set<String> = type.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "serialVersionUID" }
            .toSet()
    }
}
