package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class NullTrajectoryLearnerTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun behaviorChangesLeaveTheNullInputAndForecastUnchanged() {
        val bundle = bundle()
        val original = learn(bundle)
        val exposure = listOf(
            ReportedBehavior(behaviorIdentity(BehaviorKind.ACTIVITY, "run", "Run"), "Run"),
            WorkoutBehavior(behaviorIdentity(BehaviorKind.WORKOUT, "lift", "Lift"), "Lift", 40),
            ReportedBehavior(behaviorIdentity(BehaviorKind.FOOD, "soup", "Soup"), "Soup"),
            ReportedBehavior(behaviorIdentity(BehaviorKind.MEAL_SIZE, "bowl", "Bowl"), "Bowl"),
            UsageBehavior(
                behaviorIdentity(BehaviorKind.USAGE, "phone", "Phone", "coping"),
                listOf("Phone"),
                listOf(UsageQuantityEvidence("3", UsageQuantityStatus.PARSED, 3.0, "hour", null)),
                listOf(ComparableUsageTotal("hour", 3.0, 1.0)),
            ),
        )
        val changed = learn(bundle) { scenario ->
            scenario.copy(
                activity = CategoryKnowledge.REVIEWED_ENTRIES,
                workout = CategoryKnowledge.REVIEWED_ENTRIES,
                diet = CategoryKnowledge.REVIEWED_NONE,
                usage = CategoryKnowledge.UNKNOWN,
                exposures = exposure,
            )
        }
        assertEquals(original, changed)
        val state = bundle.stateForecasts.examples.single { it.identity.endingKey.raw == "c" }
        val recorded = bundle.conditionalTransitions.examples.single { it.identity == state.identity }.scenario
        val input = checkNotNull(state.input)
        assertEquals(
            projectNullTrajectoryInput(input, recorded, zone),
            projectNullTrajectoryInput(input, recorded.copy(exposures = exposure, activity = CategoryKnowledge.REVIEWED_ENTRIES), zone),
        )
    }

    @Test
    fun heldOutAndLaterTargetsDoNotChangeTheFittedFold() {
        val bundle = bundle()
        val original = learn(bundle)
        val heldOut = learn(bundle, moodOn = "d" to 9)
        val later = learn(bundle, moodOn = "e" to 1)
        val before = mood(original, "d")
        val after = mood(heldOut, "d")
        assertEquals(before.parameters, after.parameters)
        assertEquals(before.trainingIds, after.trainingIds)
        assertEquals(before.forecast, after.forecast)
        assertNotEquals(before.officialResidual, after.officialResidual)
        assertEquals(
            original.folds.single { it.identity.endingKey.raw == "d" },
            later.folds.single { it.identity.endingKey.raw == "d" },
        )
        assertEquals(
            original.folds.single { it.identity.endingKey.raw == "c" },
            heldOut.folds.single { it.identity.endingKey.raw == "c" },
        )
        val actual = 4.0
        assertEquals(actual - checkNotNull(before.forecast).boundedForecast, checkNotNull(before.officialResidual), 1e-9)
        assertEquals(actual - before.forecast.rawExtrapolation, checkNotNull(before.rawResidual), 1e-9)
    }

    @Test
    fun equalTimeUnrelatedExampleStaysOutAndTheExactAnchorStaysIn() {
        val bundle = bundle()
        val original = learn(bundle)
        val held = original.folds.single { it.identity.endingKey.raw == "d" }
        val predecessor = original.folds.single { it.identity.endingKey.raw == "c" }
        val earlier = original.folds.single { it.identity.endingKey.raw == "b" }
        assertEquals(checkNotNull(held.identity.anchorAt), predecessor.identity.endingAt)
        assertEquals(held.identity.anchorKey, predecessor.identity.endingKey)
        assertEquals(
            listOf(earlier.identity.transitionId, predecessor.identity.transitionId).sorted(),
            mood(original, "d").trainingIds,
        )
        assertFalse(held.identity.transitionId in mood(original, "d").trainingIds)
        val moved = learn(bundle, identity = { current ->
            if (current.transitionId == earlier.identity.transitionId) {
                current.copy(
                    endingAt = checkNotNull(held.identity.anchorAt),
                    endingKey = StateOccurrenceKey("other"),
                )
            } else {
                current
            }
        })
        assertEquals(listOf(predecessor.identity.transitionId), mood(moved, "d").trainingIds)
        assertEquals(NullForecastFallback.INSUFFICIENT_SUPPORT, mood(moved, "d").fallback)
    }

    @Test
    fun missingIncomingDeltaStaysDistinctFromRecordedZero() {
        val missing = nullInput(bundle(), "b")
        assertNull(missing.axis(DefaultCheckInAxes.MOOD).incomingDelta)
        assertNull(nullGateObservation(missing, DefaultCheckInAxes.MOOD).incomingDelta)
        val recordedZero = nullInput(bundle(listOf(4, 4, 4, 4, 4)), "c")
        assertEquals(0, recordedZero.axis(DefaultCheckInAxes.MOOD).incomingDelta)
        assertEquals(0.0, checkNotNull(nullGateObservation(recordedZero, DefaultCheckInAxes.MOOD).incomingDelta), 0.0)
        val absent = NullGateObservation(null, null, 1.0, 0.0, 1.0)
        val zero = NullGateObservation(null, 0.0, 1.0, 0.0, 1.0)
        val other = NullGateObservation(null, 2.0, 3.0, 0.5, 0.5)
        assertNotEquals(
            fitNullRidge(listOf(absent, other), listOf(0.0, 4.0)),
            fitNullRidge(listOf(zero, other), listOf(0.0, 4.0)),
        )
    }

    @Test
    fun recordedZeroTargetIsTrainingEvidenceAndHeldOutZeroDoesNotRefit() {
        val bundle = bundle(listOf(4, 0, 6, 8, 2))
        val original = learn(bundle)
        assertTrue(mood(original, "b").identityTransition(original, "b") in mood(original, "d").trainingIds)
        assertEquals(0, checkNotNull(endingRaw(bundle, "b")))
        val used = learn(bundle, moodOn = "b" to 5)
        assertNotEquals(mood(original, "d").parameters, mood(used, "d").parameters)
        val held = learn(bundle, moodOn = "d" to 0)
        assertEquals(mood(original, "d").parameters, mood(held, "d").parameters)
        assertNotEquals(mood(original, "d").officialResidual, mood(held, "d").officialResidual)
    }

    @Test
    fun nonvaryingClockAndHorizonKeepAFitWithZeroCoefficients() {
        val evaluation = learn(bundle(listOf(1, 4, 8, 3, 6), step = 24 * HOUR))
        val parameters = checkNotNull(mood(evaluation, "d").parameters)
        assertNull(mood(evaluation, "d").fallback)
        listOf(
            NullGateColumn.HORIZON_HOURS,
            NullGateColumn.HORIZON_END_HOUR_SIN,
            NullGateColumn.HORIZON_END_HOUR_COS,
        ).forEach { column ->
            val parameter = parameters.single { it.column == column }
            assertTrue(parameter.nonvarying)
            assertEquals(0.0, parameter.coefficient, 0.0)
        }
    }

    @Test
    fun tinySupportFallsBackToPersistence() {
        val evaluation = learn(bundle())
        val first = mood(evaluation, "b")
        val second = mood(evaluation, "c")
        assertEquals(NullForecastFallback.NO_PRIOR_INPUT, first.fallback)
        assertEquals(0.0, checkNotNull(first.expectedResidual), 0.0)
        assertEquals(1.0, checkNotNull(first.forecast).boundedForecast, 0.0)
        assertEquals(1, second.supervisedCount)
        assertEquals(NullForecastFallback.INSUFFICIENT_SUPPORT, second.fallback)
        assertEquals(0.0, checkNotNull(second.expectedResidual), 0.0)
        assertEquals(2.0, checkNotNull(second.forecast).rawExtrapolation, 0.0)
        assertEquals(2.0, second.forecast.boundedForecast, 0.0)
        assertEquals(1.0, checkNotNull(second.officialResidual), 0.0)
        assertNull(second.parameters)
    }

    @Test
    fun reversedExamplesProduceTheSameOrderedResult() {
        val bundle = bundle()
        val forward = learn(bundle)
        val reversed = projectNullTrajectoryLearner(
            bundle.stateForecasts.examples.reversed(),
            bundle.conditionalTransitions.examples.reversed(),
            zone,
        )
        assertEquals(forward, reversed)
        assertEquals(
            forward.folds.map { it.identity.transitionId },
            reversed.folds.map { it.identity.transitionId },
        )
    }

    @Test
    fun unstableInputFallsBackInsteadOfReturningANonfiniteCoefficient() {
        val row = NullGateObservation(null, null, 1.0, 0.0, 1.0)
        assertNull(fitNullRidge(listOf(row, row), listOf(Double.NaN, 1.0)))
        assertNull(fitNullRidge(listOf(row, row), listOf(Double.POSITIVE_INFINITY, 1.0)))
        assertNull(solveNullLinearSystem(arrayOf(doubleArrayOf(0.0)), doubleArrayOf(1.0)))
        assertNull(solveNullLinearSystem(arrayOf(doubleArrayOf(Double.NaN)), doubleArrayOf(1.0)))
        val solved = checkNotNull(
            solveNullLinearSystem(
                arrayOf(doubleArrayOf(0.0, 2.0), doubleArrayOf(3.0, 0.0)),
                doubleArrayOf(4.0, 6.0),
            ),
        )
        assertEquals(2.0, solved[0], 1e-9)
        assertEquals(2.0, solved[1], 1e-9)
        assertNull(
            fitNullRidge(
                listOf(
                    NullGateObservation(null, null, 1e308, 0.0, 1.0),
                    NullGateObservation(null, null, -1e308, 0.0, 1.0),
                ),
                listOf(1.0, -1.0),
            ),
        )
    }

    @Test
    fun shrinkagePullsAOneColumnSignalTowardZero() {
        val fitted = checkNotNull(
            fitNullRidge(
                listOf(
                    NullGateObservation(null, null, 0.0, 0.0, 1.0),
                    NullGateObservation(null, null, 2.0, 0.0, 1.0),
                ),
                listOf(-2.0, 2.0),
            ),
        )
        val horizon = fitted.single { it.column == NullGateColumn.HORIZON_HOURS }
        assertEquals(1.0, horizon.center, 0.0)
        assertEquals(1.0, horizon.scale, 1e-12)
        assertEquals(4.0 / 102.0, horizon.coefficient, 1e-9)
        assertTrue(abs(horizon.coefficient) < 0.1)
        fitted.filter { it.column != NullGateColumn.HORIZON_HOURS }.forEach { parameter ->
            assertEquals(0.0, parameter.coefficient, 1e-12)
        }
    }

    @Test
    fun gateClockHorizonAndCommonSupportStayExplicit() {
        val bundle = bundle()
        val input = nullInput(bundle, "d")
        val gate = nullGateObservation(input, DefaultCheckInAxes.MOOD)
        val zoned = Instant.ofEpochMilli(input.horizonEndAt).atZone(zone)
        val seconds = zoned.hour * 3600.0 + zoned.minute * 60.0 + zoned.second
        val angle = 2.0 * PI * seconds / 86_400.0
        assertEquals(sin(angle), gate.horizonEndHourSin, 0.0)
        assertEquals(cos(angle), gate.horizonEndHourCos, 0.0)
        assertEquals(1.0, gate.horizonHours, 0.0)
        assertTrue(input.retrospectiveHorizon)
        val negative = bundle.conditionalTransitions.examples.single { it.identity.endingKey.raw == "d" }.scenario
        val preserved = projectNullTrajectoryInput(
            checkNotNull(bundle.stateForecasts.examples.single { it.identity.endingKey.raw == "d" }.input),
            negative.copy(span = negative.span.copy(millis = -HOUR)),
            zone,
        )
        assertEquals(-1.0, preserved.horizonHours(), 0.0)
        assertEquals(checkNotNull(negative.clock).endAt, preserved.horizonEndAt)
        val anchor = checkNotNull(bundle.stateForecasts.examples.single { it.identity.endingKey.raw == "c" }.input)
        val sandbox = sandboxTransitionScenario(
            SandboxScenarioRequest(anchor.stateSide.trajectory.stateKey, 2 * HOUR),
        )
        val otherSandbox = sandboxTransitionScenario(
            SandboxScenarioRequest(
                anchor.stateSide.trajectory.stateKey,
                2 * HOUR,
                activity = CategoryKnowledge.REVIEWED_ENTRIES,
                exposures = listOf(ReportedBehavior(behaviorIdentity(BehaviorKind.ACTIVITY, "walk", "Walk"), "Walk")),
            ),
        )
        val projected = projectNullTrajectoryInput(anchor, sandbox, zone)
        assertEquals(projected, projectNullTrajectoryInput(anchor, otherSandbox, zone))
        assertFalse(projected.retrospectiveHorizon)
        assertEquals(ScenarioProvenance.SANDBOX_SPECIFIED, projected.provenance)
        assertEquals(2.0, projected.horizonHours(), 0.0)
        assertEquals(anchor.stateSide.focal.recordedAt + 2 * HOUR, projected.horizonEndAt)
        val anxious = point("a", 0, mood = 4, anxiety = 8)
        val next = point("b", HOUR, mood = 4, anxiety = 2)
        val anxiousInput = projectNullTrajectoryInput(
            checkNotNull(projectForecastExamples(dataset(listOf(anxious, next), listOf(interval("b", "a", 0, HOUR)))).stateForecasts.examples.single().input),
            projectForecastExamples(dataset(listOf(anxious, next), listOf(interval("b", "a", 0, HOUR)))).conditionalTransitions.examples.single().scenario,
            zone,
        )
        assertEquals(8, anxiousInput.axis(DefaultCheckInAxes.ANXIETY).startingRaw)
        val evaluation = learn(bundle)
        assertEquals(NULL_TRAJECTORY_PROTOCOL, evaluation.protocol)
        assertEquals(NULL_TRAJECTORY_GATE, evaluation.gate)
        assertEquals(100.0, evaluation.shrinkageLambda, 0.0)
        assertEquals(NULL_TRAJECTORY_ASSUMPTIONS, evaluation.assumptions)
        assertFalse(evaluation.suitableForRecommendation)
        assertFalse(evaluation.updatesModel)
        assertFalse(evaluation.estimatesBehaviorEffect)
        assertFalse(evaluation.strictAsKnownAvailability)
        assertEquals(CausalCutoff.ENDING_BEFORE_ORIGIN_OR_EXACT_ANCHOR, evaluation.cutoff)
        assertFalse(evaluation.assumptions.contains("percent"))
        val moodSupport = evaluation.commonSupport.single { it.axisId == DefaultCheckInAxes.MOOD }
        val energySupport = evaluation.commonSupport.single { it.axisId == DefaultCheckInAxes.ENERGY }
        assertEquals(3, moodSupport.count)
        assertEquals(NullComparisonModel.entries, moodSupport.models.map { it.model })
        assertTrue(moodSupport.models.all { it.bias != null && it.mae != null && it.rmse != null })
        assertEquals(0, energySupport.count)
        assertTrue(energySupport.models.all { it.bias == null && it.mae == null && it.rmse == null })
        assertEquals(
            listOf(
                NullGateColumn.RECENT_MEAN_CORRECTION,
                NullGateColumn.RECENT_MEAN_OBSERVED,
                NullGateColumn.INCOMING_DELTA,
                NullGateColumn.INCOMING_DELTA_OBSERVED,
                NullGateColumn.HORIZON_HOURS,
                NullGateColumn.HORIZON_END_HOUR_SIN,
                NullGateColumn.HORIZON_END_HOUR_COS,
            ),
            NullGateColumn.entries,
        )
        val learner = source(
            "src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
        ).readText()
        listOf(
            ".exposures",
            ".activity",
            ".workout",
            ".diet",
            ".usage",
            "BehaviorKind",
            "Dao",
            ".insert",
            "LatentProjection",
            "weekday-sin",
            "encodeCausalState",
            "encodeCausalConditional",
            "projectCausalRawFeatures",
            "fitCausal",
            "neural",
        ).forEach { token -> assertFalse(token, learner.contains(token)) }
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("Null trajectory learner"))
        assertTrue(lab.contains("behavior identity withheld"))
        listOf("Activity", "Workout", "Diet", "Usage", "Wearable", "recommend", "Slider", "Dao", ".insert").forEach { token ->
            assertFalse(token, lab.contains(token))
        }
        val replay = lab.substringAfter("fun RecordedReplayPane").substringBefore("fun PredictionSandboxPane")
        assertFalse(replay.contains("horizonHours"))
        assertFalse(replay.contains("projectExpertHorizon"))
    }

    private fun mood(evaluation: NullTrajectoryEvaluation, ending: String): NullAxisForecast {
        return evaluation.folds.single { it.identity.endingKey.raw == ending }
            .axes.single { it.axisId == DefaultCheckInAxes.MOOD }
    }

    private fun NullAxisForecast.identityTransition(evaluation: NullTrajectoryEvaluation, ending: String): String {
        return evaluation.folds.single { it.identity.endingKey.raw == ending }.identity.transitionId
    }

    private fun endingRaw(bundle: ForecastExampleBundle, ending: String): Int? {
        return bundle.stateForecasts.examples.single { it.identity.endingKey.raw == ending }
            .target.reading(DefaultCheckInAxes.MOOD).raw
    }

    private fun nullInput(bundle: ForecastExampleBundle, ending: String): NullTrajectoryInput {
        val state = bundle.stateForecasts.examples.single { it.identity.endingKey.raw == ending }
        val scenario = bundle.conditionalTransitions.examples.single { it.identity == state.identity }.scenario
        return projectNullTrajectoryInput(checkNotNull(state.input), scenario, zone)
    }

    private fun learn(
        bundle: ForecastExampleBundle,
        moodOn: Pair<String, Int?>? = null,
        identity: (ExampleIdentity) -> ExampleIdentity = { it },
        scenario: (TransitionScenario) -> TransitionScenario = { it },
    ): NullTrajectoryEvaluation {
        val states = bundle.stateForecasts.examples.map { example ->
            val next = example.copy(identity = identity(example.identity))
            if (moodOn != null && next.identity.endingKey.raw == moodOn.first) {
                next.copy(target = withMood(next.target, moodOn.second))
            } else {
                next
            }
        }
        val conditional = bundle.conditionalTransitions.examples.map { example ->
            val next = example.copy(identity = identity(example.identity), scenario = scenario(example.scenario))
            if (moodOn != null && next.identity.endingKey.raw == moodOn.first) {
                next.copy(target = withMood(next.target, moodOn.second))
            } else {
                next
            }
        }
        return projectNullTrajectoryLearner(states, conditional, zone)
    }

    private fun withMood(target: ObservedAxisTarget, raw: Int?): ObservedAxisTarget {
        return ObservedAxisTarget(
            target.readings.map { reading ->
                if (reading.axisId == DefaultCheckInAxes.MOOD) reading.copy(raw = raw) else reading
            },
        )
    }

    private fun bundle(moods: List<Int> = listOf(1, 2, 3, 4, 5), step: Long = HOUR) = projectForecastExamples(chain(moods, step))

    private fun chain(moods: List<Int>, step: Long): CheckInAnalysisDatasetV2 {
        val ids = listOf("a", "b", "c", "d", "e", "f")
        val states = moods.mapIndexed { index, mood -> point(ids[index], index * step, mood = mood) }
        return dataset(
            states,
            states.zipWithNext().map { (previous, next) ->
                interval(next.checkInId, previous.checkInId, previous.recordedAt, next.recordedAt)
            },
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
        anxiety: Int? = null,
        conflicting: List<String> = emptyList(),
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
            DefaultCheckInAxes.ANXIETY to anxiety,
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
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ANXIETY },
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
