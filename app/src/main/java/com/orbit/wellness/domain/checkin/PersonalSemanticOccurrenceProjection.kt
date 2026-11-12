package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.SleepEvent
import com.orbit.wellness.data.local.SubstanceUseEvent
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableObservation

/**
 * Audit of the occurrence grammar already stored for one history.
 *
 * This is not an embedding, a clusterer, a learner, or a stored graph.
 * [ConceptIdentityKey] is a logical identity. This pass does not decide
 * whether a later representation stores that identity as a node, a relation,
 * or an embedding-table entry. Links below are an inspectable view of
 * observations that an existing contract already records.
 *
 * The inventory is fit on the lists it is given. It is not a walk-forward
 * vocabulary, and masking an outcome would not make it one.
 */
const val PERSONAL_SEMANTIC_OCCURRENCE_PROJECTION = "personal-semantic-occurrence-projection-v1"

const val OCCURRENCE_PROJECTION_SCOPE = "supplied-lists"

const val SLEEP_LOG_IDENTITY = "sleep-log"

enum class SemanticConceptType {
    EMOTION,
    ACTIVITY,
    WORKOUT,
    FOOD,
    MEAL_SIZE,
    CONTEXTUAL_USAGE,
    TIMED_SUBSTANCE,
    SLEEP_LOG,
    WEARABLE_SLEEP,
}

fun SemanticConceptType.auditLabel(): String = when (this) {
    SemanticConceptType.EMOTION -> "emotion"
    SemanticConceptType.ACTIVITY -> "activity"
    SemanticConceptType.WORKOUT -> "workout"
    SemanticConceptType.FOOD -> "food"
    SemanticConceptType.MEAL_SIZE -> "meal size"
    SemanticConceptType.CONTEXTUAL_USAGE -> "contextual usage"
    SemanticConceptType.TIMED_SUBSTANCE -> "timed substance"
    SemanticConceptType.SLEEP_LOG -> "sleep log"
    SemanticConceptType.WEARABLE_SLEEP -> "wearable sleep"
}

/**
 * Logical concept identity. [identity] may equal a check-in id. Equality still
 * includes [type] and [intent], so that shared string is not one key.
 */
data class ConceptIdentityKey(
    val type: SemanticConceptType,
    val identity: String,
    val intent: String? = null,
)

enum class AxisOccurrenceStatus {
    OBSERVED,
    MISSING,
    CONFLICTING,
}

data class AxisOccurrence(
    val axisId: String,
    val status: AxisOccurrenceStatus,
    val raw: Int?,
) {
    init {
        require((status == AxisOccurrenceStatus.OBSERVED) == (raw != null)) {
            "An observed axis has its raw reading, and only an observed axis does"
        }
    }
}

data class StateOccurrenceProjection(
    val key: StateOccurrenceKey,
    val recordedAt: Long,
    val axes: List<AxisOccurrence>,
    val emotion: EmotionToken?,
)

data class TransitionOccurrenceProjection(
    val key: TransitionOccurrenceKey,
    val startAt: Long,
    val endAt: Long,
    val durationMillis: Long,
    val startBasis: String,
    val boundary: Boolean,
    val completed: Boolean,
    val eligibility: CausalEligibility,
    val startStateKey: StateOccurrenceKey?,
    val endStateKey: StateOccurrenceKey,
) {
    init {
        require(!boundary || startStateKey == null) {
            "A boundary interval does not receive a starting state"
        }
    }
}

sealed interface OccurrenceQuantity {
    data class WorkoutMinutes(val minutes: Int) : OccurrenceQuantity
    data class UsageAmounts(val amounts: List<UsageQuantityEvidence>) : OccurrenceQuantity
}

sealed interface ConceptObservationLink {
    val concept: ConceptIdentityKey
    val label: String

    data class OnState(
        val key: StateOccurrenceKey,
        override val concept: ConceptIdentityKey,
        override val label: String,
    ) : ConceptObservationLink

    data class OnTransition(
        val key: TransitionOccurrenceKey,
        override val concept: ConceptIdentityKey,
        override val label: String,
        val quantity: OccurrenceQuantity?,
    ) : ConceptObservationLink
}

data class UnattachedSourceObservation(
    val concept: ConceptIdentityKey,
    val recordId: String,
    val label: String,
    val startedAt: Long,
    val endedAt: Long?,
    val quantityText: String?,
    val intent: String?,
)

data class CategoryStatusCount(
    val category: String,
    val reviewedEmpty: Int,
    val reviewedEntries: Int,
    val unreviewed: Int,
    val absent: Int,
    val conflicting: Int,
) {
    val unknown: Int
        get() = unreviewed + absent

    init {
        require(reviewedEmpty >= 0 && reviewedEntries >= 0 && unreviewed >= 0 && absent >= 0 && conflicting >= 0)
    }
}

data class AxisCoverageCount(
    val axisId: String,
    val observed: Int,
    val missing: Int,
    val conflicting: Int,
)

data class OccurrenceStructureCounts(
    val states: Int,
    val transitions: Int,
    val valid: Int,
    val boundary: Int,
    val incomplete: Int,
    val conflicting: Int,
    val unlinkedStates: Int,
    val unlinkedTransitions: Int,
    val invalidChronology: Int,
)

data class ConceptSupport(
    val key: ConceptIdentityKey,
    val displayNames: List<String>,
    val backboneOccurrences: Int,
    val unattachedOccurrences: Int,
) {
    val supportCount: Int
        get() = backboneOccurrences + unattachedOccurrences

    init {
        require(backboneOccurrences >= 0 && unattachedOccurrences >= 0)
        require(supportCount > 0)
    }
}

data class QuantityCoverage(
    val workoutOccurrences: Int,
    val usageOccurrences: Int,
    val usageAmountRows: Int,
    val usageMissing: Int,
    val usageParsed: Int,
    val usageUnparsed: Int,
)

data class TypedKeyOverlap(
    val raw: String,
    val inState: Boolean,
    val inTransition: Boolean,
    val concepts: List<ConceptIdentityKey>,
) {
    init {
        val spaces = listOf(inState, inTransition, concepts.isNotEmpty()).count { it }
        require(spaces >= 2) { "An overlap names a raw string found in more than one key space" }
    }
}

data class PersonalSemanticOccurrenceProjection(
    val protocol: String = PERSONAL_SEMANTIC_OCCURRENCE_PROJECTION,
    val scope: String = OCCURRENCE_PROJECTION_SCOPE,
    val states: List<StateOccurrenceProjection>,
    val transitions: List<TransitionOccurrenceProjection>,
    val links: List<ConceptObservationLink>,
    val unattached: List<UnattachedSourceObservation>,
    val concepts: List<ConceptSupport>,
    val structure: OccurrenceStructureCounts,
    val categories: List<CategoryStatusCount>,
    val axes: List<AxisCoverageCount>,
    val statesWithEmotion: Int,
    val statesWithoutEmotion: Int,
    val statesWithNote: Int,
    val customAxisReadings: Int,
    val wearableRowsOutsideInventory: Int,
    val quantity: QuantityCoverage,
    val overlaps: List<TypedKeyOverlap>,
    val judgments: List<String>,
    val retrospective: Boolean = true,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
) {
    init {
        require(protocol == PERSONAL_SEMANTIC_OCCURRENCE_PROJECTION)
        require(scope == OCCURRENCE_PROJECTION_SCOPE)
        require(retrospective && !predictive && !updatesModel && !suitableForRecommendation)
        require(statesWithEmotion + statesWithoutEmotion == states.size)
        axes.forEach { axis ->
            require(axis.observed + axis.missing + axis.conflicting == states.size)
        }
    }

    fun seenOnce(): List<ConceptSupport> = concepts.filter { it.supportCount == 1 }

    fun seenTwice(): List<ConceptSupport> = concepts.filter { it.supportCount == 2 }
}

fun projectPersonalSemanticOccurrences(
    dataset: CheckInAnalysisDatasetV2,
    sleep: List<SleepEvent> = emptyList(),
    substances: List<SubstanceUseEvent> = emptyList(),
    wearables: List<WearableObservation> = emptyList(),
): PersonalSemanticOccurrenceProjection {
    val graph = projectAlternatingTemporalGraph(dataset)
    val eligibility = graph.causalSteps.associateBy { it.input.transitionId }
    val states = graph.states.map { node -> stateProjection(node.state) }
    val stateIds = states.map { it.key }.toSet()
    val transitions = graph.transitions.map { node ->
        val boundary = isBoundary(node.interval.startBasis)
        val ending = StateOccurrenceKey(node.endStateId)
        val starting = if (boundary) {
            null
        } else {
            node.startStateId?.let { StateOccurrenceKey(it) }
        }
        TransitionOccurrenceProjection(
            key = TransitionOccurrenceKey(node.id),
            startAt = node.interval.windowStartAt,
            endAt = node.interval.windowEndAt,
            durationMillis = node.interval.durationMillis,
            startBasis = node.interval.startBasis,
            boundary = boundary,
            completed = node.interval.isComplete,
            eligibility = eligibility.getValue(node.id).eligibility,
            startStateKey = starting?.takeIf { it in stateIds },
            endStateKey = ending,
        )
    }
    val transitionIds = transitions.map { it.key }.toSet()
    val links = buildList {
        states.forEach { state ->
            val emotion = state.emotion ?: return@forEach
            add(
                ConceptObservationLink.OnState(
                    key = state.key,
                    concept = ConceptIdentityKey(SemanticConceptType.EMOTION, emotion.name),
                    label = emotion.name,
                ),
            )
        }
        graph.transitions.forEach { node ->
            addAll(transitionLinks(node))
        }
    }.filter { link ->
        when (link) {
            is ConceptObservationLink.OnState -> link.key in stateIds
            is ConceptObservationLink.OnTransition -> link.key in transitionIds
        }
    }
    val wearableSleep = wearables.filter { it.metric == WearableMetric.SLEEP_DURATION }
    val unattached = sleepObservations(sleep) + substanceObservations(substances) + wearableSleepObservations(wearableSleep)
    val concepts = support(links, unattached)
    val endingIds = transitions.map { it.endStateKey }.toSet()
    val startingIds = transitions.mapNotNull { it.startStateKey }.toSet()
    val structure = OccurrenceStructureCounts(
        states = states.size,
        transitions = transitions.size,
        valid = transitions.count { it.eligibility.eligible },
        boundary = transitions.count { it.boundary },
        incomplete = transitions.count { !it.completed },
        conflicting = transitions.count { transition ->
            transition.eligibility.reasons.contains(CausalIneligibilityReason.INCONSISTENT_CONTEXT)
        },
        unlinkedStates = states.count { it.key !in endingIds && it.key !in startingIds },
        unlinkedTransitions = transitions.count { transition ->
            !transition.boundary && transition.startStateKey == null
        },
        invalidChronology = transitions.count { transition ->
            transition.eligibility.reasons.contains(CausalIneligibilityReason.INVALID_CHRONOLOGY)
        },
    )
    return PersonalSemanticOccurrenceProjection(
        states = states,
        transitions = transitions,
        links = links,
        unattached = unattached,
        concepts = concepts,
        structure = structure,
        categories = categoryCounts(graph),
        axes = axisCoverage(states),
        statesWithEmotion = states.count { it.emotion != null },
        statesWithoutEmotion = states.count { it.emotion == null },
        statesWithNote = graph.states.count { it.state.note.isNotEmpty() },
        customAxisReadings = graph.states.sumOf { state ->
            state.state.axes.keys.count { it !in CheckInAnalysisDatasetV2.CORE_AXIS_IDS }
        },
        wearableRowsOutsideInventory = wearables.count { it.metric != WearableMetric.SLEEP_DURATION },
        quantity = quantityCoverage(links),
        overlaps = overlaps(states, transitions, concepts),
        judgments = judgments(graph, wearables, unattached),
    )
}

private fun stateProjection(state: StateObservationV2): StateOccurrenceProjection {
    return StateOccurrenceProjection(
        key = StateOccurrenceKey(state.checkInId),
        recordedAt = state.recordedAt,
        axes = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId -> axisOccurrence(state, axisId) },
        emotion = if (state.emotion.isEmpty()) null else EmotionToken(state.emotion),
    )
}

private fun axisOccurrence(state: StateObservationV2, axisId: String): AxisOccurrence {
    if (axisId in state.conflictingAxisIds) {
        return AxisOccurrence(axisId, AxisOccurrenceStatus.CONFLICTING, null)
    }
    val reading = state.axes[axisId] ?: return AxisOccurrence(axisId, AxisOccurrenceStatus.MISSING, null)
    return AxisOccurrence(axisId, AxisOccurrenceStatus.OBSERVED, reading.raw)
}

private fun isBoundary(startBasis: String): Boolean {
    return startBasis == CheckInContextStartBasis.WAKE_TIME ||
        startBasis == CheckInContextStartBasis.DAY_START
}

private fun transitionLinks(node: TransitionOccurrenceNode): List<ConceptObservationLink.OnTransition> {
    val interval = node.interval
    val key = TransitionOccurrenceKey(node.id)
    val links = mutableListOf<ConceptObservationLink.OnTransition>()
    if (interval.activity.kind == CategoryObservationKindV2.ENTRIES) {
        interval.activity.activities.forEach { item ->
            links += ConceptObservationLink.OnTransition(
                key = key,
                concept = conceptKey(behaviorIdentity(BehaviorKind.ACTIVITY, item.optionId, item.name)),
                label = item.name,
                quantity = null,
            )
        }
    }
    if (interval.workout.kind == CategoryObservationKindV2.ENTRIES) {
        interval.workout.workouts.forEach { item ->
            links += ConceptObservationLink.OnTransition(
                key = key,
                concept = conceptKey(behaviorIdentity(BehaviorKind.WORKOUT, item.optionId, item.name)),
                label = item.name,
                quantity = OccurrenceQuantity.WorkoutMinutes(item.durationMinutes),
            )
        }
    }
    if (interval.diet.kind == CategoryObservationKindV2.ENTRIES) {
        interval.diet.meals.forEach { meal ->
            if (meal.size.isNotBlank()) {
                links += ConceptObservationLink.OnTransition(
                    key = key,
                    concept = conceptKey(behaviorIdentity(BehaviorKind.MEAL_SIZE, optionId = null, name = meal.size)),
                    label = meal.size,
                    quantity = null,
                )
            }
            meal.foods.forEach { food ->
                links += ConceptObservationLink.OnTransition(
                    key = key,
                    concept = conceptKey(behaviorIdentity(BehaviorKind.FOOD, food.optionId, food.name)),
                    label = food.name,
                    quantity = null,
                )
            }
        }
    }
    if (interval.usage.kind == CategoryObservationKindV2.ENTRIES) {
        interval.usage.usage
            .groupBy { behaviorIdentity(BehaviorKind.USAGE, it.optionId, it.name, it.intent) }
            .forEach { (identity, items) ->
                val amounts = items.map { usageQuantity(it.amountText) }
                    .sortedWith(compareBy({ it.rawAmountText }, { it.unit ?: "" }, { it.status.name }))
                links += ConceptObservationLink.OnTransition(
                    key = key,
                    concept = conceptKey(identity),
                    label = items.map { it.name }.distinct().sorted().joinToString(", "),
                    quantity = OccurrenceQuantity.UsageAmounts(amounts),
                )
            }
    }
    return links
}

private fun conceptKey(identity: BehaviorIdentity): ConceptIdentityKey {
    val type = when (identity.kind) {
        BehaviorKind.ACTIVITY -> SemanticConceptType.ACTIVITY
        BehaviorKind.WORKOUT -> SemanticConceptType.WORKOUT
        BehaviorKind.FOOD -> SemanticConceptType.FOOD
        BehaviorKind.MEAL_SIZE -> SemanticConceptType.MEAL_SIZE
        BehaviorKind.USAGE -> SemanticConceptType.CONTEXTUAL_USAGE
    }
    return ConceptIdentityKey(type, identity.key, identity.intent)
}

private fun usageQuantity(amountText: String): UsageQuantityEvidence {
    return when (val parsed = UsageAmountParser.parse(amountText)) {
        UsageAmountParse.Missing -> UsageQuantityEvidence(
            rawAmountText = amountText,
            status = UsageQuantityStatus.MISSING,
            numericAmount = null,
            unit = null,
            failure = null,
        )
        is UsageAmountParse.Parsed -> UsageQuantityEvidence(
            rawAmountText = amountText,
            status = UsageQuantityStatus.PARSED,
            numericAmount = parsed.number,
            unit = parsed.unit,
            failure = null,
        )
        is UsageAmountParse.Unparsed -> UsageQuantityEvidence(
            rawAmountText = amountText,
            status = UsageQuantityStatus.UNPARSED,
            numericAmount = null,
            unit = null,
            failure = parsed.reason,
        )
    }
}

private fun sleepObservations(sleep: List<SleepEvent>): List<UnattachedSourceObservation> {
    val concept = ConceptIdentityKey(SemanticConceptType.SLEEP_LOG, SLEEP_LOG_IDENTITY)
    return sleep.sortedWith(compareBy({ it.wakeTime }, { it.id })).map { event ->
        UnattachedSourceObservation(
            concept = concept,
            recordId = event.id,
            label = SLEEP_LOG_IDENTITY,
            startedAt = event.bedtime,
            endedAt = event.wakeTime,
            quantityText = "quality ${event.sleepQuality}",
            intent = null,
        )
    }
}

private fun substanceObservations(substances: List<SubstanceUseEvent>): List<UnattachedSourceObservation> {
    return substances.sortedWith(compareBy({ it.timestamp }, { it.id })).map { event ->
        UnattachedSourceObservation(
            concept = ConceptIdentityKey(
                type = SemanticConceptType.TIMED_SUBSTANCE,
                identity = categoricalKey(optionId = null, name = event.name),
                intent = event.intent.takeIf { it.isNotBlank() },
            ),
            recordId = event.id,
            label = event.name,
            startedAt = event.timestamp,
            endedAt = null,
            quantityText = event.amount,
            intent = event.intent.takeIf { it.isNotBlank() },
        )
    }
}

private fun wearableSleepObservations(rows: List<WearableObservation>): List<UnattachedSourceObservation> {
    val concept = ConceptIdentityKey(SemanticConceptType.WEARABLE_SLEEP, WearableMetric.SLEEP_DURATION)
    return rows.sortedWith(compareBy({ it.observedStartAt }, { it.id })).map { row ->
        UnattachedSourceObservation(
            concept = concept,
            recordId = row.id,
            label = row.metric,
            startedAt = row.observedStartAt,
            endedAt = row.observedEndAt,
            quantityText = "${row.value} ${row.unit}",
            intent = null,
        )
    }
}

private fun support(
    links: List<ConceptObservationLink>,
    unattached: List<UnattachedSourceObservation>,
): List<ConceptSupport> {
    val names = linkedMapOf<ConceptIdentityKey, MutableList<String>>()
    val backbone = linkedMapOf<ConceptIdentityKey, Int>()
    val detached = linkedMapOf<ConceptIdentityKey, Int>()
    links.forEach { link ->
        backbone[link.concept] = backbone.getOrDefault(link.concept, 0) + 1
        names.getOrPut(link.concept) { mutableListOf() }.add(link.label)
    }
    unattached.forEach { row ->
        detached[row.concept] = detached.getOrDefault(row.concept, 0) + 1
        names.getOrPut(row.concept) { mutableListOf() }.add(row.label)
    }
    return names.keys
        .sortedWith(compareBy({ it.type.ordinal }, { it.identity }, { it.intent ?: "" }))
        .map { key ->
            ConceptSupport(
                key = key,
                displayNames = names.getValue(key).distinct().sorted(),
                backboneOccurrences = backbone.getOrDefault(key, 0),
                unattachedOccurrences = detached.getOrDefault(key, 0),
            )
        }
}

private fun categoryCounts(graph: AlternatingTemporalGraph): List<CategoryStatusCount> {
    return graph.transitions.firstOrNull()?.interval?.categoriesInOrder().orEmpty().map { sample ->
        CategoryStatusCount(
            category = sample.category,
            reviewedEmpty = graph.transitions.count { category(it, sample.category).kind == CategoryObservationKindV2.NONE },
            reviewedEntries = graph.transitions.count { category(it, sample.category).kind == CategoryObservationKindV2.ENTRIES },
            unreviewed = graph.transitions.count { category(it, sample.category).kind == CategoryObservationKindV2.UNREVIEWED },
            absent = graph.transitions.count { category(it, sample.category).kind == CategoryObservationKindV2.ABSENT },
            conflicting = graph.transitions.count { category(it, sample.category).kind == CategoryObservationKindV2.CONFLICTING },
        )
    }
}

private fun category(node: TransitionOccurrenceNode, category: String): ContextCategoryObservationV2 {
    return node.interval.categoriesInOrder().first { it.category == category }
}

private fun axisCoverage(states: List<StateOccurrenceProjection>): List<AxisCoverageCount> {
    return CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        val readings = states.map { state -> state.axes.first { it.axisId == axisId } }
        AxisCoverageCount(
            axisId = axisId,
            observed = readings.count { it.status == AxisOccurrenceStatus.OBSERVED },
            missing = readings.count { it.status == AxisOccurrenceStatus.MISSING },
            conflicting = readings.count { it.status == AxisOccurrenceStatus.CONFLICTING },
        )
    }
}

private fun quantityCoverage(links: List<ConceptObservationLink>): QuantityCoverage {
    val quantities = links.filterIsInstance<ConceptObservationLink.OnTransition>().mapNotNull { it.quantity }
    val workouts = quantities.filterIsInstance<OccurrenceQuantity.WorkoutMinutes>()
    val usage = quantities.filterIsInstance<OccurrenceQuantity.UsageAmounts>()
    val amounts = usage.flatMap { it.amounts }
    return QuantityCoverage(
        workoutOccurrences = workouts.size,
        usageOccurrences = usage.size,
        usageAmountRows = amounts.size,
        usageMissing = amounts.count { it.status == UsageQuantityStatus.MISSING },
        usageParsed = amounts.count { it.status == UsageQuantityStatus.PARSED },
        usageUnparsed = amounts.count { it.status == UsageQuantityStatus.UNPARSED },
    )
}

private fun overlaps(
    states: List<StateOccurrenceProjection>,
    transitions: List<TransitionOccurrenceProjection>,
    concepts: List<ConceptSupport>,
): List<TypedKeyOverlap> {
    val stateRaws = states.map { it.key.raw }.toSet()
    val transitionRaws = transitions.map { it.key.raw }.toSet()
    val byIdentity = concepts.groupBy { it.key.identity }
    return (stateRaws + transitionRaws + byIdentity.keys)
        .distinct()
        .sorted()
        .mapNotNull { raw ->
            val conceptKeys = byIdentity[raw].orEmpty().map { it.key }
            val inState = raw in stateRaws
            val inTransition = raw in transitionRaws
            val spaces = listOf(inState, inTransition, conceptKeys.isNotEmpty()).count { it }
            if (spaces < 2) null else TypedKeyOverlap(raw, inState, inTransition, conceptKeys)
        }
}

private fun judgments(
    graph: AlternatingTemporalGraph,
    wearables: List<WearableObservation>,
    unattached: List<UnattachedSourceObservation>,
): List<String> {
    val lines = mutableListOf(
        "Concept links are an audit view of records the existing contracts already keep. They do not choose a graph node, a relation, or an embedding table.",
        "The inventory is fit on the lists it was given. It is not a walk-forward vocabulary.",
        "A boundary interval keeps its ending state and does not receive a starting state, including when a previous id is stored.",
        "Reviewed emptiness is category evidence and is not a concept occurrence. Unknown stays unknown.",
        "A conflicting category is counted on its own. Behavior association currently folds that status into unknown.",
        "Contextual usage keeps intent and amount on the occurrence. It is not renamed to medication. No medication record type is stored.",
        "Sleep logs have no stored concept name, so the nights share one type-level identity and stay unattached.",
        "Wearable sleep stays unattached. Steps and heart rate are not concept tokens.",
        "Timed substance events stay unattached. No audited contract joins them to an interval.",
        "Check-in notes and triggers are not concept tokens. Exact activity, workout, and meal logs are not in this snapshot.",
    )
    if (graph.states.any { it.state.note.isNotEmpty() }) {
        lines += "This history has check-in notes. They remain untokenized."
    }
    if (graph.states.any { state -> state.state.axes.keys.any { it !in CheckInAnalysisDatasetV2.CORE_AXIS_IDS } }) {
        lines += "Custom axis readings stay on the check-in and outside the four core statuses."
    }
    val otherWearables = wearables.count { it.metric != WearableMetric.SLEEP_DURATION }
    if (otherWearables > 0) {
        lines += "$otherWearables wearable rows are outside this concept inventory."
    }
    if (unattached.isEmpty()) {
        lines += "No sleep log, timed substance event, or wearable sleep row was supplied."
    }
    return lines
}
