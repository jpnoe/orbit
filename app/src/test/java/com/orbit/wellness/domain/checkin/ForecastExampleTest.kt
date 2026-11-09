package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.ui.insights.expertCompactLabel
import com.orbit.wellness.ui.insights.forecastExampleSummaryLabel
import com.orbit.wellness.ui.navigation.OrbitNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class ForecastExampleTest {
    @Test
    fun recordedExamplesKeepTheStartSeparateFromTheEndingTarget() {
        val bundle = projectForecastExamples(history())
        val boundary = bundle.conditionalTransitions.examples.single { it.identity.transitionId == "wake" }
        val recorded = bundle.conditionalTransitions.examples.single { it.identity.transitionId == "end" }
        val stateBoundary = bundle.stateForecasts.examples.single { it.identity.transitionId == "wake" }
        val stateRecorded = bundle.stateForecasts.examples.single { it.identity.transitionId == "end" }

        assertEquals(STATE_FORECAST_EXAMPLE_PROTOCOL, bundle.stateForecasts.protocol)
        assertEquals(CONDITIONAL_TRANSITION_EXAMPLE_PROTOCOL, bundle.conditionalTransitions.protocol)
        assertEquals(projectWalkForward(history()), bundle.walkForward)
        assertEquals(projectObservationProcess(history()), bundle.observationProcess)
        assertSame(stateRecorded.input, recorded.input)
        assertSame(stateRecorded.target, recorded.target)
        val wake = bundle.walkForward.stateSide.states.single { it.focal.checkInId == "wake" }
        assertSame(wake, recorded.input!!.stateSide)
        assertSame(
            bundle.observationProcess.contexts.single { it.stateKey.raw == "wake" },
            recorded.input.observation,
        )
        assertEquals(listOf("stateSide", "observation"), fields(StateForecastInput::class.java))
        assertTrue(recorded.available)
        assertFalse(boundary.available)
        assertEquals(listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL), boundary.gaps)
        assertEquals(StateOccurrenceKey("origin"), boundary.identity.anchorKey)
        assertNull(boundary.scenario.anchorKey)
        assertEquals(CheckInContextStartBasis.WAKE_TIME, boundary.scenario.clock!!.startBasis)
        assertEquals(ScenarioProvenance.RECORDED_REALIZED, recorded.scenario.provenance)
        assertEquals(CategoryKnowledge.REVIEWED_NONE, recorded.scenario.activity)
        assertEquals(CategoryKnowledge.UNKNOWN, recorded.scenario.workout)
        assertEquals(CategoryKnowledge.REVIEWED_NONE, recorded.scenario.usage)
        assertTrue(recorded.scenario.exposures.isEmpty())
        assertEquals(0, recorded.target.reading(DefaultCheckInAxes.MOOD).raw)
        assertNull(recorded.target.reading(DefaultCheckInAxes.ENERGY).raw)
        assertEquals(6, recorded.target.reading(DefaultCheckInAxes.CONFIDENCE).raw)
        assertNull(recorded.target.reading(DefaultCheckInAxes.ANXIETY).raw)
        assertEquals(listOf(DefaultCheckInAxes.ANXIETY), recorded.targetConflicts)
        assertFalse(DefaultCheckInAxes.ENERGY in recorded.targetConflicts)
        assertEquals(
            "State forecasts 2 · available 1 · unavailable 1 · boundary 1 · " +
                "target axes missing 4 · conflicts 1 · recorded zeros 1 · " +
                "conditional 2 · recorded realized 2 · sandbox specified 0",
            forecastExampleSummaryLabel(bundle.summary),
        )
    }

    @Test
    fun endingAxesChangeOnlyTheTarget() {
        val original = projectForecastExamples(history())
        val changed = projectForecastExamples(withState(history(), "end") { withMood(it, 7) })
        val before = original.stateForecasts.examples.single { it.identity.transitionId == "end" }
        val after = changed.stateForecasts.examples.single { it.identity.transitionId == "end" }
        assertEquals(before.input, after.input)
        assertEquals(
            original.conditionalTransitions.examples.single { it.identity.transitionId == "end" }.scenario,
            changed.conditionalTransitions.examples.single { it.identity.transitionId == "end" }.scenario,
        )
        assertEquals(7, after.target.reading(DefaultCheckInAxes.MOOD).raw)
        assertNotEquals(before.target, after.target)
        assertEquals(before.targetConflicts, after.targetConflicts)
        assertEquals(
            original.stateForecasts.examples.single { it.identity.transitionId == "wake" },
            changed.stateForecasts.examples.single { it.identity.transitionId == "wake" },
        )
        val emotionChanged = projectForecastExamples(withState(history(), "end") { it.copy(emotion = "glad") })
        assertEquals(original.stateForecasts, emotionChanged.stateForecasts)
        assertEquals(original.conditionalTransitions, emotionChanged.conditionalTransitions)
        assertEquals(original.walkForward.evaluation, emotionChanged.walkForward.evaluation)
    }

    @Test
    fun recordedBehaviorChangesOnlyTheRecordedScenario() {
        val original = projectForecastExamples(history())
        val behaved = history().let { data ->
            data.copy(intervals = data.intervals.map { interval ->
                if (interval.endingCheckInId != "end") {
                    interval
                } else {
                    interval.copy(
                        activity = ActivityCategoryObservationV2(
                            CategoryObservationKindV2.ENTRIES,
                            interval.windowEndAt,
                            listOf(ActivityObservationV2("walk", null, "Walk", interval.windowStartAt)),
                        ),
                        usage = UsageCategoryObservationV2(
                            CategoryObservationKindV2.ENTRIES,
                            interval.windowEndAt,
                            listOf(
                                UsageObservationV2("u0", null, "tea", "supportive", "0", interval.windowStartAt),
                                UsageObservationV2("u1", null, "tea", "supportive", "", interval.windowStartAt),
                                UsageObservationV2("u2", null, "tea", "supportive", "nope", interval.windowStartAt),
                            ),
                        ),
                    )
                }
            })
        }
        val changed = projectForecastExamples(behaved)
        val before = original.conditionalTransitions.examples.single { it.identity.transitionId == "end" }
        val after = changed.conditionalTransitions.examples.single { it.identity.transitionId == "end" }
        assertEquals(before.input, after.input)
        assertEquals(before.target, after.target)
        assertEquals(before.targetConflicts, after.targetConflicts)
        assertEquals(CategoryKnowledge.REVIEWED_ENTRIES, after.scenario.activity)
        assertEquals(CategoryKnowledge.REVIEWED_ENTRIES, after.scenario.usage)
        assertEquals(CategoryKnowledge.UNKNOWN, after.scenario.workout)
        val evidence = projectBehavioralEvidence(behaved, ZoneId.of("UTC"))
            .intervals
            .single { it.key.raw == "end" }
        assertEquals(evidence.exposures, after.scenario.exposures)
        assertEquals(evidence.activityReview, after.scenario.activity)
        assertEquals(evidence.usageReview, after.scenario.usage)
        val usage = after.scenario.exposures.filterIsInstance<UsageBehavior>().single()
        assertEquals(listOf("", "0", "nope"), usage.amounts.map { it.rawAmountText })
        val zero = usage.amounts.single { it.rawAmountText == "0" }
        val missing = usage.amounts.single { it.rawAmountText == "" }
        val malformed = usage.amounts.single { it.rawAmountText == "nope" }
        assertEquals(UsageQuantityStatus.PARSED, zero.status)
        assertEquals(0.0, checkNotNull(zero.numericAmount), 0.0)
        assertEquals(UsageQuantityStatus.MISSING, missing.status)
        assertEquals(UsageQuantityStatus.UNPARSED, malformed.status)
        assertEquals(UsageParseFailure.MALFORMED, malformed.failure)
        assertTrue(usage.comparable.isEmpty())
        assertEquals(original.walkForward.evaluation, changed.walkForward.evaluation)
        assertNotEquals(before.scenario, after.scenario)
    }

    @Test
    fun anOutgoingTransitionDoesNotChangeTheStateInput() {
        val original = projectForecastExamples(history())
        val data = history()
        val longer = data.copy(intervals = data.intervals.map { interval ->
            if (interval.endingCheckInId == "end") interval.copy(durationMillis = interval.durationMillis + 60) else interval
        })
        val changed = projectForecastExamples(longer)
        val before = original.stateForecasts.examples.single { it.identity.transitionId == "end" }
        val after = changed.stateForecasts.examples.single { it.identity.transitionId == "end" }
        assertEquals(before.input, after.input)
        assertEquals(before.target, after.target)
        assertEquals(before.targetConflicts, after.targetConflicts)
        assertEquals(
            before.identity,
            changed.conditionalTransitions.examples.single { it.identity.transitionId == "end" }.identity,
        )
        val span = changed.conditionalTransitions.examples.single { it.identity.transitionId == "end" }.scenario.span
        assertEquals(ScenarioProvenance.RECORDED_REALIZED, span.provenance)
        assertEquals(2 * HOUR + 60, span.millis)
        assertEquals(original.walkForward.evaluation, changed.walkForward.evaluation)
        assertEquals(
            original.conditionalTransitions.examples.single { it.identity.transitionId == "wake" },
            changed.conditionalTransitions.examples.single { it.identity.transitionId == "wake" },
        )
    }

    @Test
    fun sandboxScenariosStayOutsideRecordedExamplesAndAggregates() {
        val bundle = projectForecastExamples(history())
        val sandbox = sandboxTransitionScenario(
            SandboxScenarioRequest(
                anchorKey = StateOccurrenceKey("wake"),
                horizonMillis = HOUR,
            ),
        )
        assertEquals(ScenarioProvenance.SANDBOX_SPECIFIED, sandbox.provenance)
        assertEquals(CategoryKnowledge.UNKNOWN, sandbox.activity)
        assertNull(sandbox.transitionKey)
        assertNull(sandbox.clock)
        assertEquals(HOUR, sandbox.span.millis)
        assertTrue(bundle.conditionalTransitions.examples.none { it.scenario == sandbox })
        assertTrue(bundle.conditionalTransitions.examples.none { it.scenario.provenance == ScenarioProvenance.SANDBOX_SPECIFIED })
        assertEquals(0, bundle.summary.sandboxSpecifiedCount)
        assertEquals(projectWalkForward(history()).evaluation, bundle.walkForward.evaluation)
        val recorded = bundle.conditionalTransitions.examples.single { it.identity.transitionId == "end" }
        assertThrows(IllegalArgumentException::class.java) {
            ConditionalTransitionExample(
                identity = recorded.identity,
                input = recorded.input,
                scenario = sandbox,
                target = recorded.target,
                targetConflicts = recorded.targetConflicts,
                gaps = recorded.gaps,
            )
        }
    }

    @Test
    fun laterStatesDoNotChangeAnEarlierExample() {
        val original = projectForecastExamples(history())
        val later = history().let { data ->
            data.copy(states = data.states + point("later", 20 * HOUR, mood = 9, confidence = 1))
        }
        val changed = projectForecastExamples(later)
        assertEquals(original.stateForecasts.examples, changed.stateForecasts.examples)
        assertEquals(original.conditionalTransitions.examples, changed.conditionalTransitions.examples)
        assertEquals(original.walkForward.evaluation, changed.walkForward.evaluation)
    }

    @Test
    fun backdatedHistoryChangesTheReconstructedInputAndNotTheTarget() {
        val original = projectForecastExamples(history())
        val backdated = history().let { data ->
            data.copy(states = listOf(point("backdated", -HOUR, mood = 1, energy = 1)) + data.states)
        }
        val changed = projectForecastExamples(backdated)
        val before = original.stateForecasts.examples.single { it.identity.transitionId == "wake" }
        val after = changed.stateForecasts.examples.single { it.identity.transitionId == "wake" }
        assertNotEquals(before.input, after.input)
        assertEquals(before.target, after.target)
        assertEquals(before.targetConflicts, after.targetConflicts)
        assertEquals(
            original.conditionalTransitions.examples.single { it.identity.transitionId == "wake" }.scenario,
            changed.conditionalTransitions.examples.single { it.identity.transitionId == "wake" }.scenario,
        )
        assertEquals(
            original.stateForecasts.examples.single { it.identity.transitionId == "end" }.target,
            changed.stateForecasts.examples.single { it.identity.transitionId == "end" }.target,
        )
    }

    @Test
    fun unavailableExamplesKeepMissingConflictAndZeroDistinct() {
        val unresolved = projectForecastExamples(
            dataset(
                states = listOf(point("end", HOUR, mood = 0, conflicting = listOf(DefaultCheckInAxes.ANXIETY))),
                intervals = listOf(interval("end", "missing", 0, HOUR, CheckInContextStartBasis.PREVIOUS_CHECK_IN)),
            ),
        ).stateForecasts.examples.single()
        assertNull(unresolved.input)
        assertFalse(unresolved.available)
        assertTrue(CausalIneligibilityReason.START_UNRESOLVED in unresolved.gaps)
        assertEquals(0, unresolved.target.reading(DefaultCheckInAxes.MOOD).raw)
        assertEquals(listOf(DefaultCheckInAxes.ANXIETY), unresolved.targetConflicts)
        assertNull(unresolved.target.reading(DefaultCheckInAxes.ENERGY).raw)
        assertFalse(DefaultCheckInAxes.ENERGY in unresolved.targetConflicts)

        val reversed = dataset(
            states = listOf(
                point("start", 2 * HOUR, mood = 4),
                point("end", HOUR, mood = 1),
            ),
            intervals = listOf(interval("end", "start", 2 * HOUR, HOUR, CheckInContextStartBasis.PREVIOUS_CHECK_IN)),
        )
        val invalid = projectForecastExamples(reversed).stateForecasts.examples.single()
        assertTrue(invalid.input != null)
        assertTrue(CausalIneligibilityReason.INVALID_CHRONOLOGY in invalid.gaps)
        assertEquals(1, invalid.target.reading(DefaultCheckInAxes.MOOD).raw)

        val open = history().let { data ->
            data.copy(intervals = data.intervals.map { interval ->
                if (interval.endingCheckInId == "end") interval.copy(completedAt = null) else interval
            })
        }
        val incomplete = projectForecastExamples(open).conditionalTransitions.examples.single { it.identity.transitionId == "end" }
        assertTrue(CausalIneligibilityReason.INCOMPLETE_CONTEXT in incomplete.gaps)
        assertEquals(false, incomplete.scenario.clock!!.completed)
        assertEquals(CategoryKnowledge.REVIEWED_NONE, incomplete.scenario.activity)
        assertEquals(0, incomplete.target.reading(DefaultCheckInAxes.MOOD).raw)
    }

    @Test
    fun exampleOrderFollowsEndingTimeAndThenId() {
        val forward = projectForecastExamples(history())
        val data = history()
        val backward = projectForecastExamples(
            data.copy(states = data.states.reversed(), intervals = data.intervals.reversed()),
        )
        assertEquals(forward.stateForecasts, backward.stateForecasts)
        assertEquals(forward.conditionalTransitions, backward.conditionalTransitions)
        assertEquals(forward.summary, backward.summary)
        assertEquals(
            listOf("wake", "end"),
            forward.stateForecasts.examples.map { it.identity.transitionId },
        )
    }

    @Test
    fun unavailableTrajectoriesShowTheirSupportWithoutAZeroSlope() {
        val alone = projectCausalStateSide(dataset(listOf(point("only", 0, mood = 4)), emptyList())).states.single()
        val one = expert(alone, TrajectoryScaleId.IMMEDIATE_PATH)
        val none = expert(alone, TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertNull(one.slopePerHour)
        assertEquals(1, one.observedCount)
        assertTrue(expertCompactLabel(one).contains("1 reading"))
        assertTrue(expertCompactLabel(one).contains("slope unavailable"))
        assertFalse(expertCompactLabel(one).contains("slope 0"))
        assertEquals(0, none.observedCount)
        assertTrue(expertCompactLabel(none).contains("0 readings"))
        assertTrue(expertCompactLabel(none).contains("slope unavailable"))

        val tied = projectCausalStateSide(
            dataset(
                states = listOf(
                    point("aa", 0, mood = 1),
                    point("bb", 0, mood = 2),
                    point("focal", HOUR, mood = 4),
                ),
                intervals = emptyList(),
            ),
        ).states.single { it.focal.checkInId == "focal" }
        val zeroSpan = expert(tied, TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(TrajectoryExpertGap.ZERO_TIME_SPAN, zeroSpan.unavailable)
        assertNull(zeroSpan.slopePerHour)
        assertEquals(2, zeroSpan.observedCount)
        val tiedLabel = expertCompactLabel(zeroSpan)
        assertTrue(tiedLabel.contains("2 readings"))
        assertTrue(tiedLabel.contains("zero time span"))
        assertTrue(tiedLabel.contains("slope unavailable"))
        assertFalse(tiedLabel.contains("slope 0"))

        val flat = projectCausalStateSide(
            chain(point("early", 0, mood = 4), point("late", HOUR, mood = 4)),
        ).states.single { it.focal.checkInId == "late" }
        val slope = expert(flat, TrajectoryScaleId.IMMEDIATE_PATH)
        assertEquals(0.0, slope.slopePerHour)
        assertTrue(expertCompactLabel(slope).contains("slope 0 points/hour"))
        assertFalse(expertCompactLabel(slope).contains("slope unavailable"))
    }

    @Test
    fun examplesDoNotLearnWeightsOrEnterReleaseNavigation() {
        val bundle = projectForecastExamples(history())
        assertFalse(bundle.stateForecasts.predictive)
        assertFalse(bundle.stateForecasts.suitableForRecommendation)
        assertFalse(bundle.stateForecasts.updatesModel)
        assertFalse(bundle.conditionalTransitions.predictive)
        assertFalse(bundle.conditionalTransitions.updatesModel)
        val scenarioFields = fields(TransitionScenario::class.java)
        listOf("target", "residual", "emotion", "delta", "coordinate", "weight").forEach { token ->
            assertTrue(scenarioFields.none { it.contains(token, ignoreCase = true) })
        }
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/ForecastExample.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/ForecastExample.kt",
        ).readText()
        listOf("Dao", "RoomDatabase", ".insert", "fun combine", "neural").forEach { token ->
            assertFalse(source.contains(token))
        }
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("State forecasts"))
        assertTrue(lab.contains("recorded realized"))
        assertTrue(lab.contains("sandbox specified"))
        assertTrue(lab.contains("Focal core axes recorded"))
        assertTrue(lab.contains("slope unavailable"))
        listOf("Activity", "Workout", "Diet", "Usage", "Wearable", "recommend").forEach { token ->
            assertFalse(lab.contains(token))
        }
        val insights = source(
            "src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
        ).readText()
        val repository = source(
            "src/main/java/com/orbit/wellness/data/CheckInAnalysisRepository.kt",
            "app/src/main/java/com/orbit/wellness/data/CheckInAnalysisRepository.kt",
        ).readText()
        val nav = source(
            "src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
            "app/src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
        ).readText()
        assertFalse(insights.contains("projectForecastExamples"))
        assertFalse(repository.contains("projectForecastExamples"))
        assertFalse(nav.contains("projectForecastExamples"))
        assertNull(OrbitNavigation.researchPreviewRoute(debugBuild = false))
    }

    private fun history(): CheckInAnalysisDatasetV2 {
        return dataset(
            states = listOf(
                point("origin", 0, mood = 2, energy = 4),
                point("wake", 2 * HOUR, mood = 5, emotion = "calm", trigger = "morning"),
                point(
                    "end",
                    4 * HOUR,
                    mood = 0,
                    confidence = 6,
                    conflicting = listOf(DefaultCheckInAxes.ANXIETY),
                ),
            ),
            intervals = listOf(
                interval("wake", "origin", 0, 2 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                interval("end", "wake", 2 * HOUR, 4 * HOUR, CheckInContextStartBasis.PREVIOUS_CHECK_IN).copy(
                    workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, null, emptyList()),
                ),
            ),
        )
    }

    private fun withState(
        data: CheckInAnalysisDatasetV2,
        id: String,
        edit: (StateObservationV2) -> StateObservationV2,
    ): CheckInAnalysisDatasetV2 {
        return data.copy(states = data.states.map { state -> if (state.checkInId == id) edit(state) else state })
    }

    private fun withMood(state: StateObservationV2, mood: Int): StateObservationV2 {
        val reading = AxisReadingV2(DefaultCheckInAxes.MOOD, mood, mood / 10.0)
        return state.copy(
            axes = state.axes + (DefaultCheckInAxes.MOOD to reading),
            core = state.core.copy(mood = reading),
        )
    }

    private fun expert(side: CausalStateSide, scale: TrajectoryScaleId): TrajectoryExpert {
        return (side.pathExperts + side.backgroundExperts).single { expert ->
            expert.scale == scale && expert.axisId == DefaultCheckInAxes.MOOD
        }
    }

    private fun chain(vararg states: StateObservationV2): CheckInAnalysisDatasetV2 {
        val intervals = states.toList().zipWithNext().map { (previous, next) ->
            interval(
                next.checkInId,
                previous.checkInId,
                previous.recordedAt,
                next.recordedAt,
                CheckInContextStartBasis.PREVIOUS_CHECK_IN,
            )
        }
        return dataset(states.toList(), intervals)
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
        confidence: Int? = null,
        trigger: String = "",
        emotion: String = "",
        conflicting: List<String> = emptyList(),
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
            DefaultCheckInAxes.CONFIDENCE to confidence,
        ).mapNotNull { (axis, raw) -> reading(axis, raw) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-11-08",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.CONFIDENCE },
                null,
            ),
            conflictingAxisIds = conflicting,
            trigger = trigger,
            emotion = emotion,
        )
    }

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = basis,
        completedAt = endAt,
        activity = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    )

    private fun fields(type: Class<*>): List<String> {
        return type.declaredFields.map { it.name }.filterNot { it.startsWith("$") || it == "serialVersionUID" }
    }

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
