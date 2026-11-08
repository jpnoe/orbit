package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind

/**
 * One check-in occurrence. A [TransitionOccurrenceKey] with the same [raw]
 * string is a different value: an interval is keyed by its ending check-in id,
 * and these wrappers keep that shared string from being one embedding key.
 */
@JvmInline
value class StateOccurrenceKey(val raw: String)

/** One context interval. [raw] is [ContextIntervalObservationV2.endingCheckInId]. */
@JvmInline
value class TransitionOccurrenceKey(val raw: String)

/**
 * Evidence for one check-in, without its occurrence id.
 *
 * These fields are the V1 candidates for a later encoder. [normalized] is the
 * analysis value `raw / 10.0`. It does not mean adjacent scores are equally far
 * apart. Missing axes are named in [coreMissingAxisIds] and are not filled.
 * [conflictingAxisIds] are disagreements, not ordinary missingness. A recorded
 * 0 stays in [axes]. Anxiety is the stored reading. [emotion] is a name, and
 * an empty emotion stays null.
 */
data class StateEvidencePayload(
    val axes: List<AxisEvidence>,
    val coreMissingAxisIds: List<String>,
    val conflictingAxisIds: List<String>,
    val emotion: EmotionToken?,
)

data class AxisEvidence(
    /** Stable axis id. A categorical token, not a magnitude. */
    val axisId: String,
    val raw: Int,
    val normalized: Double,
)

@JvmInline
value class EmotionToken(val name: String) {
    init {
        require(name.isNotBlank()) { "An empty emotion is absent, not a token" }
    }
}

/**
 * Check-in fields kept for joins and inspection. V1 does not encode them.
 * [note] is not embedded or tokenized.
 */
data class StateEvidenceMetadata(
    val recordedAt: Long,
    val localDate: String,
    val trigger: String,
    val note: String,
)

data class StateEvidenceFrame(
    val key: StateOccurrenceKey,
    val evidence: StateEvidencePayload,
    val metadata: StateEvidenceMetadata,
)

/**
 * One context interval without the check-in ids that key its endpoints.
 * A later encoder may read this. It does not include axis deltas.
 */
data class TransitionEvidencePayload(
    val temporal: TemporalEvidence,
    val activity: CategoryEvidence<ActivityEvidence>,
    val workout: CategoryEvidence<WorkoutEvidence>,
    val diet: CategoryEvidence<MealEvidence>,
    val usage: CategoryEvidence<UsageEvidence>,
    val wearables: List<WearableMetricEvidence>,
)

/**
 * Instants and duration as stored. Duration is not a rate or a logarithm.
 * [startBasis] is a categorical token. [completed] is whether the overview was saved.
 */
data class TemporalEvidence(
    val startAt: Long,
    val endAt: Long,
    val durationMillis: Long,
    val startBasis: String,
    val completed: Boolean,
)

/** The saved-at instant for a transition. Not a duration and not encoded in V1. */
data class TransitionEvidenceMetadata(
    val completedAt: Long?,
)

data class TransitionEvidenceFrame(
    val key: TransitionOccurrenceKey,
    val evidence: TransitionEvidencePayload,
    val metadata: TransitionEvidenceMetadata,
)

/**
 * [kind] is the analysis category kind. [items] keeps the projected rows in
 * their existing order. `none` can have an empty item list and still be observed.
 * `absent` is a different kind. Conflicting rows are not rewritten to entries.
 */
data class CategoryEvidence<T>(
    val kind: CategoryObservationKindV2,
    val items: List<T>,
)

data class ActivityEvidence(
    /** Categorical vocabulary id. Null is a name-only custom activity. */
    val optionId: String?,
    val name: String,
    /** When the report was saved, not an amount of activity. */
    val reportedAt: Long,
)

/**
 * [durationMinutes] is the stored session length. The workout entity rejects 0,
 * so a missing workout is a category kind, not a duration of zero.
 */
data class WorkoutEvidence(
    val optionId: String?,
    val name: String,
    val durationMinutes: Int,
    val reportedAt: Long,
)

/**
 * [size] is the stored label, such as "snack" or "large". It is not a rank.
 * [ordering] is entry order and report time, not a measure of the meal.
 */
data class MealEvidence(
    val size: String,
    val foods: List<FoodEvidence>,
    val ordering: MealOrder,
)

data class MealOrder(
    val sortOrder: Int,
    val reportedAt: Long,
)

data class FoodEvidence(
    val optionId: String?,
    val name: String,
)

/**
 * [amountText] is the stored phrase. A numeric-looking phrase is not parsed.
 * [intent] is the stored supportive or coping token.
 */
data class UsageEvidence(
    val optionId: String?,
    val name: String,
    val intent: String,
    val amountText: String,
    val reportedAt: Long,
)

/**
 * Wearable membership for one metric. [Unobserved] is the absence of a selected
 * row, not a value of zero. Observation ids point into [ContextualEvidence.wearablesById].
 * They are provenance, not model values. [Observed.competingSpans] means more
 * than one span was kept, so those values must not be added together.
 */
sealed interface WearableMetricEvidence {
    val metric: String

    data class Unobserved(override val metric: String) : WearableMetricEvidence

    data class Observed(
        override val metric: String,
        val observationIds: List<String>,
        val competingSpans: Boolean,
    ) : WearableMetricEvidence
}

data class RetrospectiveStateEvidence(
    val frame: StateEvidenceFrame,
    val incomingTransitionKeys: List<TransitionOccurrenceKey>,
    val outgoingTransitionKeys: List<TransitionOccurrenceKey>,
)

data class RetrospectiveTransitionEvidence(
    val frame: TransitionEvidenceFrame,
    val startStateKey: StateOccurrenceKey?,
    val endStateKey: StateOccurrenceKey,
)

/**
 * Model input for one causal step. [startingState] is null when the graph has
 * no resolved start. [transition] is the interval evidence only: no ending state,
 * no ending key, no delta, and no neighborhood.
 */
data class CausalEvidenceInput(
    val startingState: StateEvidencePayload?,
    val transition: TransitionEvidencePayload,
)

/** Ending-state evidence, requested separately from [CausalEvidenceInput]. */
data class CausalEvidenceTarget(
    val endingState: StateEvidencePayload,
)

data class CausalEvidenceExample(
    /** Diagnostic identity. The same string as the ending check-in, kept outside [input]. */
    val exampleKey: TransitionOccurrenceKey,
    val input: CausalEvidenceInput,
    val target: CausalEvidenceTarget,
    val eligibility: CausalEligibility,
)

/**
 * In-memory evidence for one alternating graph and the wearable stream.
 * State and transition maps are built once for this projection.
 * [issues] is the graph's existing issue list.
 */
data class ContextualEvidence(
    val states: List<StateEvidenceFrame>,
    val transitions: List<TransitionEvidenceFrame>,
    val statesByKey: Map<StateOccurrenceKey, StateEvidenceFrame>,
    val transitionsByKey: Map<TransitionOccurrenceKey, TransitionEvidenceFrame>,
    val wearablesById: Map<String, WearableObservation>,
    val retrospectiveStates: List<RetrospectiveStateEvidence>,
    val retrospectiveTransitions: List<RetrospectiveTransitionEvidence>,
    val causalExamples: List<CausalEvidenceExample>,
    val issues: List<AnalysisIssue>,
)

fun projectContextualEvidence(
    graph: AlternatingTemporalGraph,
    wearables: List<WearableObservation> = emptyList(),
): ContextualEvidence {
    val wearablesById = wearables.groupBy { it.id }.mapValues { (_, group) ->
        group.maxWith(compareBy({ it.importedAt }, { it.id }))
    }
    val states = graph.states.map { node -> stateFrame(node.state) }
    val statesByKey = states.associateBy { it.key }
    val transitions = graph.transitions.map { node ->
        transitionFrame(node, wearables, wearablesById)
    }
    val transitionsByKey = transitions.associateBy { it.key }
    val referenced = transitions.asSequence()
        .flatMap { it.evidence.wearables.asSequence() }
        .filterIsInstance<WearableMetricEvidence.Observed>()
        .flatMap { it.observationIds.asSequence() }
        .distinct()
        .associateWith { id -> wearablesById.getValue(id) }
    val retrospectiveStates = graph.states.zip(states) { node, frame ->
        RetrospectiveStateEvidence(
            frame = frame,
            incomingTransitionKeys = node.incomingTransitionIds.map(::TransitionOccurrenceKey),
            outgoingTransitionKeys = node.outgoingTransitionIds.map(::TransitionOccurrenceKey),
        )
    }
    val retrospectiveTransitions = graph.transitions.zip(transitions) { node, frame ->
        RetrospectiveTransitionEvidence(
            frame = frame,
            startStateKey = node.startStateId?.let(::StateOccurrenceKey),
            endStateKey = StateOccurrenceKey(node.endStateId),
        )
    }
    val causalExamples = graph.causalSteps.map { step ->
        val transitionKey = TransitionOccurrenceKey(step.input.transitionId)
        val starting = step.input.startingState?.let { state ->
            statesByKey.getValue(StateOccurrenceKey(state.checkInId)).evidence
        }
        CausalEvidenceExample(
            exampleKey = transitionKey,
            input = CausalEvidenceInput(
                startingState = starting,
                transition = transitionsByKey.getValue(transitionKey).evidence,
            ),
            target = CausalEvidenceTarget(
                endingState = statesByKey.getValue(
                    StateOccurrenceKey(step.target.endingState.checkInId),
                ).evidence,
            ),
            eligibility = step.eligibility,
        )
    }
    return ContextualEvidence(
        states = states,
        transitions = transitions,
        statesByKey = statesByKey,
        transitionsByKey = transitionsByKey,
        wearablesById = referenced,
        retrospectiveStates = retrospectiveStates,
        retrospectiveTransitions = retrospectiveTransitions,
        causalExamples = causalExamples,
        issues = graph.issues,
    )
}

private fun stateFrame(state: StateObservationV2): StateEvidenceFrame {
    val conflicting = state.conflictingAxisIds.toSet()
    return StateEvidenceFrame(
        key = StateOccurrenceKey(state.checkInId),
        evidence = StateEvidencePayload(
            axes = state.axes.entries
                .sortedBy { it.key }
                .map { (axisId, reading) ->
                    AxisEvidence(
                        axisId = axisId,
                        raw = reading.raw,
                        normalized = reading.normalized,
                    )
                },
            coreMissingAxisIds = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.filter { axisId ->
                axisId !in state.axes && axisId !in conflicting
            },
            conflictingAxisIds = state.conflictingAxisIds,
            emotion = state.emotion.takeIf { it.isNotEmpty() }?.let(::EmotionToken),
        ),
        metadata = StateEvidenceMetadata(
            recordedAt = state.recordedAt,
            localDate = state.localDate,
            trigger = state.trigger,
            note = state.note,
        ),
    )
}

private fun transitionFrame(
    node: TransitionOccurrenceNode,
    wearables: List<WearableObservation>,
    wearablesById: Map<String, WearableObservation>,
): TransitionEvidenceFrame {
    val interval = node.interval
    val selected = IntervalWearableSelection.select(
        windowStartAt = interval.windowStartAt,
        windowEndAt = interval.windowEndAt,
        observations = wearables,
        byId = wearablesById,
    )
    return TransitionEvidenceFrame(
        key = TransitionOccurrenceKey(node.id),
        evidence = TransitionEvidencePayload(
            temporal = TemporalEvidence(
                startAt = interval.windowStartAt,
                endAt = interval.windowEndAt,
                durationMillis = interval.durationMillis,
                startBasis = interval.startBasis,
                completed = interval.isComplete,
            ),
            activity = category(interval.activity.kind, interval.activity.activities.map { item ->
                ActivityEvidence(
                    optionId = item.optionId,
                    name = item.name,
                    reportedAt = item.reportedAt,
                )
            }),
            workout = category(interval.workout.kind, interval.workout.workouts.map { item ->
                WorkoutEvidence(
                    optionId = item.optionId,
                    name = item.name,
                    durationMinutes = item.durationMinutes,
                    reportedAt = item.reportedAt,
                )
            }),
            diet = category(interval.diet.kind, interval.diet.meals.map { meal ->
                MealEvidence(
                    size = meal.size,
                    foods = meal.foods.map { food ->
                        FoodEvidence(optionId = food.optionId, name = food.name)
                    },
                    ordering = MealOrder(sortOrder = meal.sortOrder, reportedAt = meal.reportedAt),
                )
            }),
            usage = category(interval.usage.kind, interval.usage.usage.map { item ->
                UsageEvidence(
                    optionId = item.optionId,
                    name = item.name,
                    intent = item.intent,
                    amountText = item.amountText,
                    reportedAt = item.reportedAt,
                )
            }),
            wearables = wearableEvidence(selected),
        ),
        metadata = TransitionEvidenceMetadata(completedAt = interval.completedAt),
    )
}

private fun <T> category(kind: CategoryObservationKindV2, items: List<T>): CategoryEvidence<T> {
    return CategoryEvidence(kind = kind, items = items)
}

private fun wearableEvidence(selected: List<WearableObservation>): List<WearableMetricEvidence> {
    val byMetric = selected.groupBy { it.metric }
    return IntervalWearableSelection.METRICS.map { metric ->
        val rows = byMetric[metric].orEmpty()
        if (rows.isEmpty()) {
            WearableMetricEvidence.Unobserved(metric)
        } else {
            WearableMetricEvidence.Observed(
                metric = metric,
                observationIds = rows.map { it.id },
                competingSpans = rows.count { it.temporalKind != WearableTemporalKind.POINT } > 1,
            )
        }
    }
}
