package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class CausalWalkForwardPreparationTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun heldOutTargetDoesNotChangeTheSchemaOrPreparedInput() {
        val bundle = bundle()
        val original = prepare(bundle)
        val changed = prepare(bundle, moodOn = "e" to 1)
        val before = original.stateFolds.single { it.identity.endingKey.raw == "e" }
        val after = changed.stateFolds.single { it.identity.endingKey.raw == "e" }
        assertEquals(before.schema, after.schema)
        assertEquals(before.prepared, after.prepared)
        assertNotEquals(before.targets, after.targets)
        assertEquals(1, after.targets.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.raw)
        assertEquals(original.stateFolds.filter { it.identity.endingKey.raw != "e" }, changed.stateFolds.filter { it.identity.endingKey.raw != "e" })
        assertEquals(original.conditionalFolds.filter { it.identity.endingKey.raw != "e" }, changed.conditionalFolds.filter { it.identity.endingKey.raw != "e" })
        assertTrue(before.prepared!!.columns.none { it.name.contains("target") })
        assertTrue(after.conditionalFoldsPrepared(changed).columns.none { it.name.contains("target") })
        assertEquals(before.targets, original.conditionalFolds.single { it.identity.endingKey.raw == "e" }.targets)
        assertNotEquals(before.prepared.columns.map { it.name }, after.conditionalFoldsPrepared(changed).columns.map { it.name })
    }

    @Test
    fun laterExampleAndOverlappingOutcomeStayOutOfEarlierFolds() {
        val bundle = bundle()
        val original = prepare(bundle)
        val earlier = original.stateFolds.single { it.identity.endingKey.raw == "d" }
        val origin = checkNotNull(earlier.forecastOrigin)
        val endingEqual = bundle.stateForecasts.examples.single { it.identity.endingKey.raw == "c" }
        val earlierAnchor = bundle.stateForecasts.examples.single { it.identity.endingKey.raw == "b" }
        assertEquals(origin, endingEqual.identity.endingAt)
        assertTrue(checkNotNull(earlierAnchor.identity.anchorAt) < origin)
        assertTrue(earlierAnchor.identity.endingAt < origin)
        assertEquals(listOf(earlierAnchor.identity.transitionId), earlier.qualifyingPriorInputIds)
        assertFalse(endingEqual.identity.transitionId in earlier.qualifyingPriorInputIds)
        val overlapped = prepare(bundle) { current ->
            if (current.transitionId == earlierAnchor.identity.transitionId) {
                current.copy(endingAt = origin + 1)
            } else {
                current
            }
        }
        val moved = overlapped.stateFolds.single { it.identity.endingKey.raw == "d" }
        assertEquals(CausalFoldGap.NO_PRIOR_INPUT, moved.gap)
        assertFalse(earlierAnchor.identity.transitionId in moved.qualifyingPriorInputIds)
        assertNull(moved.schema)
        val later = prepare(bundle, moodOn = "e" to 9)
        assertEquals(
            original.stateFolds.filter { it.identity.endingKey.raw != "e" },
            later.stateFolds.filter { it.identity.endingKey.raw != "e" },
        )
        assertEquals(
            original.conditionalFolds.filter { it.identity.endingKey.raw != "e" },
            later.conditionalFolds.filter { it.identity.endingKey.raw != "e" },
        )
    }

    @Test
    fun targetMissingnessChangesSupportWithoutChangingTheSchema() {
        val bundle = bundle()
        val original = prepare(bundle)
        val missing = prepare(bundle, moodOn = "b" to null)
        val before = original.stateFolds.single { it.identity.endingKey.raw == "d" }
        val after = missing.stateFolds.single { it.identity.endingKey.raw == "d" }
        assertEquals(before.schema, after.schema)
        assertEquals(before.prepared, after.prepared)
        assertEquals(1, before.axisSupport.single { it.axisId == DefaultCheckInAxes.MOOD }.observedTargetCount)
        assertEquals(0, after.axisSupport.single { it.axisId == DefaultCheckInAxes.MOOD }.observedTargetCount)
        assertEquals(
            before.axisSupport.filter { it.axisId != DefaultCheckInAxes.MOOD },
            after.axisSupport.filter { it.axisId != DefaultCheckInAxes.MOOD },
        )
        assertEquals(after.axisSupport, missing.conditionalFolds.single { it.identity.endingKey.raw == "d" }.axisSupport)
        val zero = prepare(bundle, moodOn = "e" to 0).stateFolds.single { it.identity.endingKey.raw == "e" }
        val mood = zero.targets.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(CausalTargetStatus.OBSERVED, mood.status)
        assertEquals(0, mood.raw)
    }

    @Test
    fun nonObservedStatusesStayDistinctWhenTheirNumbersAreZero() {
        val training = bundle(listOf(4, 5)).conditionalTransitions.examples.single { it.identity.endingKey.raw == "b" }
        val schema = fitCausalConditionalSchema(
            listOf(CausalConditionalTrainingRow("b", checkNotNull(training.input), training.scenario)),
            zone,
        )
        val held = inputFor(
            projectForecastExamples(
                dataset(
                    listOf(point("held", 0, energy = 3, conflicting = listOf(DefaultCheckInAxes.ANXIETY))),
                    emptyList(),
                ),
            ),
            "held",
        )
        val encoded = encodeCausalConditional(
            held,
            training.scenario.copy(activity = CategoryKnowledge.UNKNOWN),
            schema,
            zone,
        )
        val prepared = prepareCausalLearnerVector(encoded, schema)
        assertMasks(prepared, "current.mood.raw", CausalFeatureStatus.MISSING)
        assertMasks(prepared, "current.anxiety.raw", CausalFeatureStatus.CONFLICTING)
        assertMasks(prepared, "path.immediate.mood.slope", CausalFeatureStatus.UNAVAILABLE)
        assertMasks(prepared, "scenario.category.activity", CausalFeatureStatus.UNKNOWN)
        val patterns = listOf(
            "current.mood.raw",
            "current.anxiety.raw",
            "path.immediate.mood.slope",
            "scenario.category.activity",
        ).map { masks(prepared, it) }
        assertEquals(patterns.size, patterns.distinct().size)
        val zeroBundle = projectForecastExamples(dataset(listOf(point("zero", 0, mood = 0)), emptyList()))
        val zeroInput = inputFor(zeroBundle, "zero")
        val zeroSchema = fitCausalStateSchema(listOf(CausalStateTrainingRow("zero", zeroInput)), zone)
        val zeroPrepared = prepareCausalLearnerVector(encodeCausalState(zeroInput, zeroSchema, zone), zeroSchema)
        assertMasks(zeroPrepared, "current.mood.raw", CausalFeatureStatus.OBSERVED)
        val missingPrepared = prepareCausalLearnerVector(
            encodeCausalState(held, zeroSchema, zone),
            zeroSchema,
        )
        assertMasks(missingPrepared, "current.mood.raw", CausalFeatureStatus.MISSING)
        assertEquals(0.0, column(zeroPrepared, "current.mood.raw", CausalPreparedRole.VALUE), 0.0)
        assertEquals(0.0, column(missingPrepared, "current.mood.raw", CausalPreparedRole.VALUE), 0.0)
    }

    @Test
    fun reversedExamplesAndFirstWorkoutKindStayStable() {
        val bundle = bundle()
        val forward = prepare(bundle)
        val backward = projectCausalWalkForwardPreparation(
            bundle.stateForecasts.examples.reversed(),
            bundle.conditionalTransitions.examples.reversed(),
            zone,
        )
        assertEquals(forward, backward)
        assertEquals(CAUSAL_WALK_FORWARD_PREPARATION_PROTOCOL, forward.protocol)
        assertFalse(forward.strictAsKnownAvailability)
        assertEquals(ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION, forward.availabilityMode)
        assertFalse(forward.predictive || forward.updatesModel || forward.suitableForRecommendation)
        val first = forward.stateFolds.first()
        assertEquals(CausalFoldGap.NO_PRIOR_INPUT, first.gap)
        assertTrue(first.forecastOrigin != null)
        val ready = forward.stateFolds.single { it.identity.endingKey.raw == "d" }
        assertNull(ready.gap)
        assertEquals(1, ready.qualifyingPriorInputIds.size)
        assertEquals(ready.schema!!.features.size * CausalPreparedRole.entries.size, ready.prepared!!.columns.size)
        assertFalse(ready.retrospective)
        assertNull(ready.provenance)
        val conditional = forward.conditionalFolds.single { it.identity.endingKey.raw == "d" }
        assertTrue(conditional.retrospective)
        assertEquals(ScenarioProvenance.RECORDED_REALIZED, conditional.provenance)
        assertTrue(ready.prepared.columns.none { it.name.startsWith("scenario.") })
        assertTrue(conditional.prepared!!.columns.any { it.name.startsWith("scenario.") })
        val workout = WorkoutBehavior(
            identity = behaviorIdentity(BehaviorKind.WORKOUT, null, "Run"),
            displayName = "Run",
            minutes = 20,
        )
        val withWorkout = bundle.conditionalTransitions.examples.map { example ->
            if (example.identity.endingKey.raw != "e") {
                example
            } else {
                example.copy(
                    scenario = example.scenario.copy(
                        workout = CategoryKnowledge.REVIEWED_ENTRIES,
                        exposures = listOf(workout),
                    ),
                )
            }
        }
        val prepared = projectCausalWalkForwardPreparation(bundle.stateForecasts.examples, withWorkout, zone)
        val fold = prepared.conditionalFolds.single { it.identity.endingKey.raw == "e" }
        assertTrue(fold.schema!!.features.any { it.identity == CausalFeatureIdentity.BehaviorOov(BehaviorKind.WORKOUT) })
        assertEquals(listOf("workout"), fold.oovActivations)
        assertFalse(prepared.stateFolds.single { it.identity.endingKey.raw == "e" }.oovActivations.contains("workout"))
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("Causal preparation"))
        assertTrue(lab.contains("Event-time reconstruction"))
        listOf("Activity", "Workout", "Diet", "Usage", "Wearable", "recommend", "Slider", "Dao", ".insert").forEach { token ->
            assertFalse(token, lab.contains(token))
        }
    }

    private fun CausalWalkForwardFold.conditionalFoldsPrepared(preparation: CausalWalkForwardPreparation): CausalPreparedInput {
        return checkNotNull(preparation.conditionalFolds.single { it.identity == identity }.prepared)
    }

    private fun assertMasks(prepared: CausalPreparedInput, name: String, status: CausalFeatureStatus) {
        val feature = prepared.schema.features.single { it.name == name }
        assertEquals(status, prepared.statuses[feature.index])
        assertEquals(0.0, column(prepared, name, CausalPreparedRole.VALUE), 0.0)
        assertEquals(if (status == CausalFeatureStatus.MISSING) 1.0 else 0.0, column(prepared, name, CausalPreparedRole.MISSING), 0.0)
        assertEquals(if (status == CausalFeatureStatus.CONFLICTING) 1.0 else 0.0, column(prepared, name, CausalPreparedRole.CONFLICTING), 0.0)
        assertEquals(if (status == CausalFeatureStatus.UNAVAILABLE) 1.0 else 0.0, column(prepared, name, CausalPreparedRole.UNAVAILABLE), 0.0)
        assertEquals(if (status == CausalFeatureStatus.UNKNOWN) 1.0 else 0.0, column(prepared, name, CausalPreparedRole.UNKNOWN), 0.0)
    }

    private fun masks(prepared: CausalPreparedInput, name: String): List<Double> {
        return CausalPreparedRole.entries.filter { it != CausalPreparedRole.VALUE }.map { column(prepared, name, it) }
    }

    private fun column(prepared: CausalPreparedInput, name: String, role: CausalPreparedRole): Double {
        val base = prepared.schema.features.single { it.name == name }.index
        return prepared.columns.single { it.baseIndex == base && it.role == role }.value
    }

    private fun prepare(
        bundle: ForecastExampleBundle,
        moodOn: Pair<String, Int?>? = null,
        identity: (ExampleIdentity) -> ExampleIdentity = { it },
    ): CausalWalkForwardPreparation {
        val states = bundle.stateForecasts.examples.map { example ->
            val next = example.copy(identity = identity(example.identity))
            if (moodOn != null && next.identity.endingKey.raw == moodOn.first) {
                next.copy(target = withMood(next.target, moodOn.second))
            } else {
                next
            }
        }
        val conditional = bundle.conditionalTransitions.examples.map { example ->
            val next = example.copy(identity = identity(example.identity))
            if (moodOn != null && next.identity.endingKey.raw == moodOn.first) {
                next.copy(target = withMood(next.target, moodOn.second))
            } else {
                next
            }
        }
        return projectCausalWalkForwardPreparation(states, conditional, zone)
    }

    private fun withMood(target: ObservedAxisTarget, raw: Int?): ObservedAxisTarget {
        return ObservedAxisTarget(
            target.readings.map { reading ->
                if (reading.axisId == DefaultCheckInAxes.MOOD) reading.copy(raw = raw) else reading
            },
        )
    }

    private fun bundle(moods: List<Int> = listOf(1, 2, 3, 4, 5)) = projectForecastExamples(chain(moods))

    private fun chain(moods: List<Int>): CheckInAnalysisDatasetV2 {
        val ids = listOf("a", "b", "c", "d", "e", "f")
        val states = moods.mapIndexed { index, mood -> point(ids[index], index * HOUR, mood = mood) }
        return dataset(
            states,
            states.zipWithNext().map { (previous, next) ->
                interval(next.checkInId, previous.checkInId, previous.recordedAt, next.recordedAt)
            },
        )
    }

    private fun inputFor(bundle: ForecastExampleBundle, id: String): StateForecastInput {
        return StateForecastInput(
            bundle.walkForward.stateSide.states.single { it.focal.checkInId == id },
            bundle.observationProcess.contexts.single { it.stateKey.raw == id },
        )
    }

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun point(
        id: String,
        at: Long,
        mood: Int? = null,
        energy: Int? = null,
        conflicting: List<String> = emptyList(),
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
        ).mapNotNull { (axis, raw) -> reading(axis, raw) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-11-08",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                null,
                null,
            ),
            conflictingAxisIds = conflicting,
            trigger = "",
            emotion = "",
        )
    }

    private fun interval(end: String, previous: String, start: Long, endAt: Long) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = endAt,
        activity = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    )

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
