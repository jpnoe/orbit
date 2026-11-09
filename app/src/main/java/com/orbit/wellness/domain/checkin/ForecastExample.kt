package com.orbit.wellness.domain.checkin

import java.time.ZoneId

/**
 * Gate-ready examples built from one check-in snapshot.
 *
 * [StateForecastExample] carries only the starting check-in. Its ending
 * [ObservedAxisTarget] is held beside that input. A missing axis stays on the
 * target, and a conflicting axis is named beside it so conflict is not treated
 * as ordinary missingness. A recorded zero stays zero.
 *
 * [ConditionalTransitionExample] adds a sanitized scenario. Recorded scenarios
 * describe what was reported on a completed interval. They are not claimed to
 * have been known at the anchor. A sandbox scenario is built only when a caller
 * supplies one, and it is not inserted into this recorded projection or into
 * the walk-forward aggregate.
 *
 * The projection reuses one graph, one trajectory, the state-side bundle, and
 * the observation-process contexts. It does not fit a weight, update a model,
 * or write Room.
 */
const val STATE_FORECAST_EXAMPLE_PROTOCOL = "state-forecast-example-v1"

const val CONDITIONAL_TRANSITION_EXAMPLE_PROTOCOL = "conditional-transition-example-v1"

enum class ScenarioProvenance {
    /** Reported on the completed interval. Retrospective, not known-at-anchor. */
    RECORDED_REALIZED,
    /** Supplied before a sandbox forecast. Known to that sandbox forecast only. */
    SANDBOX_SPECIFIED,
}

data class ExampleIdentity(
    val transitionId: String,
    val anchorKey: StateOccurrenceKey?,
    val anchorAt: Long?,
    val endingKey: StateOccurrenceKey,
    val endingAt: Long,
)

data class StateForecastInput(
    val stateSide: CausalStateSide,
    val observation: ObservationProcessContext,
) {
    init {
        require(stateSide.trajectory.stateKey == observation.stateKey) {
            "The forecast input pairs one state with its observation process"
        }
        require(stateSide.focal.recordedAt == observation.recordedAt)
    }
}

data class StateForecastExample(
    val identity: ExampleIdentity,
    val input: StateForecastInput?,
    val target: ObservedAxisTarget,
    val targetConflicts: List<String>,
    val gaps: List<CausalIneligibilityReason>,
) {
    val available: Boolean
        get() = gaps.isEmpty()

    init {
        require((input == null) == (identity.anchorKey == null))
        require(input == null || input.stateSide.trajectory.stateKey == identity.anchorKey)
        require(input == null || input.observation.recordedAt == identity.anchorAt)
        require(targetConflicts == targetConflicts.distinct())
        require(targetConflicts.all { it in CheckInAnalysisDatasetV2.CORE_AXIS_IDS })
        targetConflicts.forEach { axisId ->
            require(target.reading(axisId).raw == null) { "A conflicting target axis has no raw value" }
        }
    }
}

data class ScenarioClock(
    val startAt: Long,
    val endAt: Long,
    val startBasis: String,
    val completed: Boolean,
)

data class ScenarioSpan(
    val millis: Long,
    val provenance: ScenarioProvenance,
)

/**
 * Behavior, quantity, clock, and duration for one transition.
 *
 * Category knowledge and usage quantities come from the behavioral-evidence
 * projection. Ending axes, ending emotion, deltas, and residuals are absent.
 * A wake or day-start interval keeps a null scenario anchor even when the
 * graph resolved an earlier check-in. That earlier check-in can still be the
 * state-forecast input, and the boundary gap stays on the example.
 */
data class TransitionScenario(
    val provenance: ScenarioProvenance,
    val transitionKey: TransitionOccurrenceKey?,
    val anchorKey: StateOccurrenceKey?,
    val clock: ScenarioClock?,
    val span: ScenarioSpan,
    val activity: CategoryKnowledge,
    val workout: CategoryKnowledge,
    val diet: CategoryKnowledge,
    val usage: CategoryKnowledge,
    val exposures: List<BehaviorExposure>,
) {
    init {
        require(span.provenance == provenance)
        when (provenance) {
            ScenarioProvenance.RECORDED_REALIZED -> {
                require(transitionKey != null && clock != null) {
                    "A recorded scenario names its interval"
                }
            }
            ScenarioProvenance.SANDBOX_SPECIFIED -> {
                require(transitionKey == null && clock == null && anchorKey != null) {
                    "A sandbox scenario is a supplied horizon, not a logged interval"
                }
                require(span.millis > 0L) { "A sandbox horizon is positive" }
            }
        }
    }
}

data class ConditionalTransitionExample(
    val identity: ExampleIdentity,
    val input: StateForecastInput?,
    val scenario: TransitionScenario,
    val target: ObservedAxisTarget,
    val targetConflicts: List<String>,
    val gaps: List<CausalIneligibilityReason>,
) {
    val available: Boolean
        get() = gaps.isEmpty()

    init {
        require(scenario.provenance == ScenarioProvenance.RECORDED_REALIZED) {
            "Recorded conditional examples do not absorb a sandbox scenario"
        }
        require(scenario.transitionKey?.raw == identity.transitionId)
    }
}

data class ExampleReasonCount(
    val reason: CausalIneligibilityReason,
    val count: Int,
) {
    init {
        require(count > 0)
    }
}

data class ForecastExampleSummary(
    val stateExampleCount: Int,
    val availableStateExamples: Int,
    val unavailableStateExamples: Int,
    val reasons: List<ExampleReasonCount>,
    val missingTargetAxes: Int,
    val conflictingTargetAxes: Int,
    val recordedZeroTargetAxes: Int,
    val conditionalExampleCount: Int,
    val recordedRealizedCount: Int,
    val sandboxSpecifiedCount: Int,
) {
    init {
        require(stateExampleCount == availableStateExamples + unavailableStateExamples)
        require(conditionalExampleCount == recordedRealizedCount)
        require(sandboxSpecifiedCount == 0) { "Sandbox scenarios stay outside the recorded projection" }
        require(reasons.map { it.reason } == reasons.map { it.reason }.distinct())
        require(missingTargetAxes >= 0 && conflictingTargetAxes >= 0 && recordedZeroTargetAxes >= 0)
    }
}

data class StateForecastExampleDataset(
    val protocol: String = STATE_FORECAST_EXAMPLE_PROTOCOL,
    val pastOnly: Boolean = true,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val updatesModel: Boolean = false,
    val examples: List<StateForecastExample>,
) {
    init {
        require(protocol == STATE_FORECAST_EXAMPLE_PROTOCOL)
        require(pastOnly && !predictive && !suitableForRecommendation && !updatesModel) {
            "State-forecast examples are not a fitted forecast"
        }
        require(examplesAreOrdered(examples.map { it.identity }))
    }
}

data class ConditionalTransitionExampleDataset(
    val protocol: String = CONDITIONAL_TRANSITION_EXAMPLE_PROTOCOL,
    val pastOnly: Boolean = true,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val updatesModel: Boolean = false,
    val examples: List<ConditionalTransitionExample>,
) {
    init {
        require(protocol == CONDITIONAL_TRANSITION_EXAMPLE_PROTOCOL)
        require(pastOnly && !predictive && !suitableForRecommendation && !updatesModel) {
            "Conditional examples are not a fitted transition model"
        }
        require(examplesAreOrdered(examples.map { it.identity }))
        require(examples.all { it.scenario.provenance == ScenarioProvenance.RECORDED_REALIZED })
    }
}

data class ForecastExampleBundle(
    val walkForward: WalkForwardProjection,
    val observationProcess: ObservationProcessDataset,
    val stateForecasts: StateForecastExampleDataset,
    val conditionalTransitions: ConditionalTransitionExampleDataset,
    val summary: ForecastExampleSummary,
) {
    init {
        require(stateForecasts.examples.map { it.identity } == conditionalTransitions.examples.map { it.identity })
        require(summary.stateExampleCount == stateForecasts.examples.size)
        require(summary.conditionalExampleCount == conditionalTransitions.examples.size)
        require(summary == summarizeExamples(stateForecasts.examples, conditionalTransitions.examples))
    }
}

data class SandboxScenarioRequest(
    val anchorKey: StateOccurrenceKey,
    val horizonMillis: Long,
    val activity: CategoryKnowledge = CategoryKnowledge.UNKNOWN,
    val workout: CategoryKnowledge = CategoryKnowledge.UNKNOWN,
    val diet: CategoryKnowledge = CategoryKnowledge.UNKNOWN,
    val usage: CategoryKnowledge = CategoryKnowledge.UNKNOWN,
    val exposures: List<BehaviorExposure> = emptyList(),
)

fun projectForecastExamples(dataset: CheckInAnalysisDatasetV2): ForecastExampleBundle {
    val graph = projectAlternatingTemporalGraph(dataset)
    val trajectory = projectStateTrajectoryContext(graph)
    val stateSide = projectCausalStateSide(dataset, trajectory)
    val observation = projectObservationProcess(dataset, trajectory)
    val walkForward = walkForwardFrom(graph, stateSide)
    val behavior = projectBehavioralEvidence(graph, dataset, SCENARIO_ZONE)
    val stateExamples = graph.causalSteps.map { step ->
        stateExample(step, stateSide, observation)
    }
    val conditional = graph.causalSteps.zip(stateExamples).map { (step, state) ->
        conditionalExample(step, state, behavior.intervals)
    }
    return ForecastExampleBundle(
        walkForward = walkForward,
        observationProcess = observation,
        stateForecasts = StateForecastExampleDataset(examples = stateExamples),
        conditionalTransitions = ConditionalTransitionExampleDataset(examples = conditional),
        summary = summarizeExamples(stateExamples, conditional),
    )
}

fun sandboxTransitionScenario(request: SandboxScenarioRequest): TransitionScenario {
    return TransitionScenario(
        provenance = ScenarioProvenance.SANDBOX_SPECIFIED,
        transitionKey = null,
        anchorKey = request.anchorKey,
        clock = null,
        span = ScenarioSpan(request.horizonMillis, ScenarioProvenance.SANDBOX_SPECIFIED),
        activity = request.activity,
        workout = request.workout,
        diet = request.diet,
        usage = request.usage,
        exposures = request.exposures,
    )
}

private fun stateExample(
    step: CausalStep,
    stateSide: CausalStateSideDataset,
    observation: ObservationProcessDataset,
): StateForecastExample {
    val starting = step.input.startingState
    val ending = step.target.endingState
    val anchorKey = starting?.checkInId?.let(::StateOccurrenceKey)
    val input = anchorKey?.let { key ->
        StateForecastInput(
            stateSide = stateSide.states.single { it.trajectory.stateKey == key },
            observation = observation.contexts.single { it.stateKey == key },
        )
    }
    return StateForecastExample(
        identity = ExampleIdentity(
            transitionId = step.input.transitionId,
            anchorKey = anchorKey,
            anchorAt = starting?.recordedAt,
            endingKey = StateOccurrenceKey(ending.checkInId),
            endingAt = ending.recordedAt,
        ),
        input = input,
        target = observedAxisTarget(ending),
        targetConflicts = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.filter { it in ending.conflictingAxisIds },
        gaps = step.eligibility.reasons,
    )
}

private fun conditionalExample(
    step: CausalStep,
    state: StateForecastExample,
    intervals: List<IntervalBehaviorEvidence>,
): ConditionalTransitionExample {
    val interval = step.input.interval
    val evidence = intervals.single { candidate ->
        candidate.key.raw == step.input.transitionId &&
            candidate.startAt == interval.windowStartAt &&
            candidate.endAt == interval.windowEndAt &&
            candidate.startBasis == interval.startBasis
    }
    return ConditionalTransitionExample(
        identity = state.identity,
        input = state.input,
        scenario = recordedScenario(evidence),
        target = state.target,
        targetConflicts = state.targetConflicts,
        gaps = state.gaps,
    )
}

private fun recordedScenario(evidence: IntervalBehaviorEvidence): TransitionScenario {
    return TransitionScenario(
        provenance = ScenarioProvenance.RECORDED_REALIZED,
        transitionKey = evidence.key,
        anchorKey = evidence.startStateKey,
        clock = ScenarioClock(
            startAt = evidence.startAt,
            endAt = evidence.endAt,
            startBasis = evidence.startBasis,
            completed = evidence.completed,
        ),
        span = ScenarioSpan(evidence.durationMillis, ScenarioProvenance.RECORDED_REALIZED),
        activity = evidence.activityReview,
        workout = evidence.workoutReview,
        diet = evidence.dietReview,
        usage = evidence.usageReview,
        exposures = evidence.exposures,
    )
}

private fun summarizeExamples(
    stateExamples: List<StateForecastExample>,
    conditional: List<ConditionalTransitionExample>,
): ForecastExampleSummary {
    val reasons = CausalIneligibilityReason.entries.mapNotNull { reason ->
        val count = stateExamples.count { reason in it.gaps }
        if (count == 0) null else ExampleReasonCount(reason, count)
    }
    var missing = 0
    var conflicts = 0
    var zeros = 0
    stateExamples.forEach { example ->
        example.target.readings.forEach { reading ->
            when {
                reading.axisId in example.targetConflicts -> conflicts += 1
                reading.raw == 0 -> zeros += 1
                reading.raw == null -> missing += 1
            }
        }
    }
    val available = stateExamples.count { it.available }
    return ForecastExampleSummary(
        stateExampleCount = stateExamples.size,
        availableStateExamples = available,
        unavailableStateExamples = stateExamples.size - available,
        reasons = reasons,
        missingTargetAxes = missing,
        conflictingTargetAxes = conflicts,
        recordedZeroTargetAxes = zeros,
        conditionalExampleCount = conditional.size,
        recordedRealizedCount = conditional.count { it.scenario.provenance == ScenarioProvenance.RECORDED_REALIZED },
        sandboxSpecifiedCount = 0,
    )
}

private fun examplesAreOrdered(identities: List<ExampleIdentity>): Boolean {
    val keys = identities.map { it.endingAt to it.transitionId }
    return keys == keys.sortedWith(compareBy({ it.first }, { it.second }))
}

private val SCENARIO_ZONE: ZoneId = ZoneId.of("UTC")
