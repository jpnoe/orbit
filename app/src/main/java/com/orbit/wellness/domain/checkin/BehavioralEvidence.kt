package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The behavior catalog is fit on the full history it is given.
 * A leakage-safe evaluation has to fit that catalog from training history only.
 */
const val BEHAVIOR_CATALOG_SCOPE = "retrospective-full-history"

/**
 * Organizes stored contextual evidence for a later association pass.
 *
 * This does not estimate effects, rank behaviors, write prose, recommend,
 * fit a latent model, or write Room. Identities, keys, timestamps, and display
 * labels stay outside numeric geometry. Ending-state values and deltas stay on
 * this retrospective frame and are not copied onto a causal input.
 *
 * A reviewed `none` says that category was observed empty. A reviewed `entries`
 * list is what was observed present. Unreviewed, absent, and conflicting
 * categories stay unknown. Unknown is not rewritten to absence, and this
 * projection does not expand one row per catalog option.
 *
 * Wake-time and day-start intervals keep the ending state and do not receive
 * an invented start, a starting emotion, or a measured delta. An empty emotion
 * stays missing. A linked pair with no shared observed axis stays linked and
 * has no measured delta.
 */
fun projectBehavioralEvidence(
    dataset: CheckInAnalysisDatasetV2,
    zone: ZoneId,
): BehavioralEvidenceDataset {
    return projectBehavioralEvidence(projectAlternatingTemporalGraph(dataset), dataset, zone)
}

fun projectBehavioralEvidence(
    graph: AlternatingTemporalGraph,
    dataset: CheckInAnalysisDatasetV2,
    zone: ZoneId,
): BehavioralEvidenceDataset {
    val evidence = projectContextualEvidence(graph)
    val steps = graph.causalSteps.associateBy { it.input.transitionId }
    val states = dataset.states.associateBy { StateOccurrenceKey(it.checkInId) }
    val intervals = evidence.retrospectiveTransitions.map { link ->
        intervalEvidence(
            link = link,
            eligibility = steps.getValue(link.frame.key.raw).eligibility,
            states = states,
        )
    }.sortedWith(compareBy({ it.endAt }, { it.startAt }, { it.key.raw }))
    val catalog = catalogFrom(intervals)
    val unplaced = intervals
        .filter { it.startAt > it.endAt }
        .map { it.key }
    val days = calendarDays(
        dataset = dataset.copy(intervals = dataset.intervals.filter { it.windowStartAt <= it.windowEndAt }),
        zone = zone,
    )
    return BehavioralEvidenceDataset(
        catalogScope = BEHAVIOR_CATALOG_SCOPE,
        catalog = catalog,
        intervals = intervals,
        calendarDays = days,
        unplacedIntervalKeys = unplaced,
    )
}

private fun intervalEvidence(
    link: RetrospectiveTransitionEvidence,
    eligibility: CausalEligibility,
    states: Map<StateOccurrenceKey, StateObservationV2>,
): IntervalBehaviorEvidence {
    val temporal = link.frame.evidence.temporal
    val boundary = temporal.startBasis == CheckInContextStartBasis.WAKE_TIME ||
        temporal.startBasis == CheckInContextStartBasis.DAY_START
    val startKey = if (boundary) null else link.startStateKey
    val start = startKey?.let { states[it] }
    val end = states.getValue(link.endStateKey)
    val startAxes = start?.axisSnapshot()
    val endAxes = end.axisSnapshot()
    val measure = start != null && start.recordedAt < end.recordedAt
    return IntervalBehaviorEvidence(
        key = link.frame.key,
        startAt = temporal.startAt,
        endAt = temporal.endAt,
        durationMillis = temporal.durationMillis,
        startBasis = temporal.startBasis,
        completed = temporal.completed,
        eligibility = eligibility,
        startStateKey = startKey,
        endStateKey = link.endStateKey,
        activityReview = link.frame.evidence.activity.kind.toKnowledge(),
        workoutReview = link.frame.evidence.workout.kind.toKnowledge(),
        dietReview = link.frame.evidence.diet.kind.toKnowledge(),
        usageReview = link.frame.evidence.usage.kind.toKnowledge(),
        exposures = exposuresOf(link.frame.evidence, temporal.durationMillis),
        startAxes = startAxes,
        endAxes = endAxes,
        axisDeltas = axisDeltas(startAxes, endAxes, measure),
        startEmotion = start?.emotion?.let(::recordedEmotion),
        endEmotion = recordedEmotion(end.emotion),
    )
}

/** An empty emotion is missing. A non-empty snapshot stays the stored token. */
private fun recordedEmotion(raw: String): EmotionToken? {
    if (raw.isEmpty()) return null
    return EmotionToken(raw)
}

private fun exposuresOf(payload: TransitionEvidencePayload, durationMillis: Long): List<BehaviorExposure> {
    val exposures = mutableListOf<BehaviorExposure>()
    if (payload.activity.kind == CategoryObservationKindV2.ENTRIES) {
        payload.activity.items.forEach { item ->
            exposures += ReportedBehavior(
                identity = behaviorIdentity(BehaviorKind.ACTIVITY, item.optionId, item.name),
                displayName = item.name,
            )
        }
    }
    if (payload.workout.kind == CategoryObservationKindV2.ENTRIES) {
        payload.workout.items.forEach { item ->
            exposures += WorkoutBehavior(
                identity = behaviorIdentity(BehaviorKind.WORKOUT, item.optionId, item.name),
                displayName = item.name,
                minutes = item.durationMinutes,
            )
        }
    }
    if (payload.diet.kind == CategoryObservationKindV2.ENTRIES) {
        payload.diet.items.forEach { meal ->
            if (meal.size.isNotBlank()) {
                exposures += ReportedBehavior(
                    identity = behaviorIdentity(BehaviorKind.MEAL_SIZE, optionId = null, name = meal.size),
                    displayName = meal.size,
                )
            }
            meal.foods.forEach { food ->
                exposures += ReportedBehavior(
                    identity = behaviorIdentity(BehaviorKind.FOOD, food.optionId, food.name),
                    displayName = food.name,
                )
            }
        }
    }
    if (payload.usage.kind == CategoryObservationKindV2.ENTRIES) {
        payload.usage.items
            .groupBy { behaviorIdentity(BehaviorKind.USAGE, it.optionId, it.name, it.intent) }
            .forEach { (identity, items) ->
                val amounts = items.map { usageQuantity(it) }
                    .sortedWith(compareBy({ it.rawAmountText }, { it.unit ?: "" }, { it.status.name }))
                exposures += UsageBehavior(
                    identity = identity,
                    displayNames = items.map { it.name }.distinct().sorted(),
                    amounts = amounts,
                    comparable = comparableTotals(amounts, durationMillis),
                )
            }
    }
    return exposures.sortedWith(exposureOrder)
}

private fun usageQuantity(item: UsageEvidence): UsageQuantityEvidence {
    return when (val parsed = UsageAmountParser.parse(item.amountText)) {
        UsageAmountParse.Missing -> UsageQuantityEvidence(
            rawAmountText = item.amountText,
            status = UsageQuantityStatus.MISSING,
            numericAmount = null,
            unit = null,
            failure = null,
        )
        is UsageAmountParse.Parsed -> UsageQuantityEvidence(
            rawAmountText = item.amountText,
            status = UsageQuantityStatus.PARSED,
            numericAmount = parsed.number,
            unit = parsed.unit,
            failure = null,
        )
        is UsageAmountParse.Unparsed -> UsageQuantityEvidence(
            rawAmountText = item.amountText,
            status = UsageQuantityStatus.UNPARSED,
            numericAmount = null,
            unit = null,
            failure = parsed.reason,
        )
    }
}

/**
 * Totals exist only when every amount for the identity was parsed.
 * A null unit is a unitless count. Physical units stay in their own total and
 * are not given a per-hour rate. A unitless rate is the total divided by the
 * stored duration in hours, and only when that duration is positive.
 */
private fun comparableTotals(
    amounts: List<UsageQuantityEvidence>,
    durationMillis: Long,
): List<ComparableUsageTotal> {
    if (amounts.isEmpty() || amounts.any { it.status != UsageQuantityStatus.PARSED }) return emptyList()
    val hours = durationMillis / 3_600_000.0
    return amounts.groupBy { it.unit }
        .entries
        .sortedWith(compareBy { it.key ?: "" })
        .map { (unit, rows) ->
            val total = rows.sumOf { it.numericAmount ?: 0.0 }
            ComparableUsageTotal(
                unit = unit,
                total = total,
                perHour = if (unit == null && hours > 0.0 && total.isFinite()) total / hours else null,
            )
        }
}

private fun catalogFrom(intervals: List<IntervalBehaviorEvidence>): List<BehaviorCatalogEntry> {
    val names = linkedMapOf<BehaviorIdentity, MutableSet<String>>()
    intervals.forEach { interval ->
        interval.exposures.forEach { exposure ->
            val seen = names.getOrPut(exposure.identity) { linkedSetOf() }
            when (exposure) {
                is UsageBehavior -> seen += exposure.displayNames
                is ReportedBehavior -> seen += exposure.displayName
                is WorkoutBehavior -> seen += exposure.displayName
            }
        }
    }
    return names.keys
        .sortedWith(compareBy({ it.kind.ordinal }, { it.key }, { it.intent ?: "" }))
        .map { identity ->
            BehaviorCatalogEntry(
                identity = identity,
                displayNames = names.getValue(identity).sorted(),
            )
        }
}

private fun calendarDays(
    dataset: CheckInAnalysisDatasetV2,
    zone: ZoneId,
): List<CalendarDayBehaviorEvidence> {
    val byId = dataset.intervals.associateBy { it.endingCheckInId }
    val dates = linkedSetOf<LocalDate>()
    dataset.states.forEach { state ->
        dates += Instant.ofEpochMilli(state.recordedAt).atZone(zone).toLocalDate()
    }
    dataset.intervals.forEach { interval ->
        dates += datesTouched(interval.windowStartAt, interval.windowEndAt, zone)
    }
    return dates.sorted().map { date ->
        val definition = TemporalWindowProjector.calendarDay(date, zone)
        val placed = TemporalWindowProjector.project(dataset, definition)
        val contained = placed.containedIntervalIds.map(::TransitionOccurrenceKey)
        CalendarDayBehaviorEvidence(
            date = date,
            zoneId = zone.id,
            startAt = definition.startAt,
            endAt = definition.endAt,
            stateOccurrenceIds = placed.stateIds.map(::StateOccurrenceKey),
            completedExposureKeys = contained.filter { key -> byId[key.raw]?.isComplete == true },
            incompleteCoverageKeys = contained.filter { key -> byId[key.raw]?.isComplete != true },
            overlappingKeys = placed.overlappingIntervalIds.map(::TransitionOccurrenceKey),
        )
    }
}

private fun datesTouched(start: Long, end: Long, zone: ZoneId): List<LocalDate> {
    if (start > end) return emptyList()
    val first = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val lastInstant = if (end == start) start else end - 1
    val last = Instant.ofEpochMilli(lastInstant).atZone(zone).toLocalDate()
    val dates = mutableListOf<LocalDate>()
    var cursor = first
    while (!cursor.isAfter(last)) {
        dates += cursor
        cursor = cursor.plusDays(1)
    }
    return dates
}

private fun StateObservationV2.axisSnapshot() = CoreAxisSnapshot(
    mood = core.mood?.raw,
    energy = core.energy?.raw,
    confidence = core.confidence?.raw,
    anxiety = core.anxiety?.raw,
)

private fun axisDeltas(
    start: CoreAxisSnapshot?,
    end: CoreAxisSnapshot,
    measure: Boolean,
): CoreAxisDelta {
    fun one(before: Int?, after: Int?): Int? {
        return if (measure && before != null && after != null) after - before else null
    }
    return CoreAxisDelta(
        mood = one(start?.mood, end.mood),
        energy = one(start?.energy, end.energy),
        confidence = one(start?.confidence, end.confidence),
        anxiety = one(start?.anxiety, end.anxiety),
    )
}

private fun CategoryObservationKindV2.toKnowledge(): CategoryKnowledge {
    return when (this) {
        CategoryObservationKindV2.NONE -> CategoryKnowledge.REVIEWED_NONE
        CategoryObservationKindV2.ENTRIES -> CategoryKnowledge.REVIEWED_ENTRIES
        CategoryObservationKindV2.UNREVIEWED,
        CategoryObservationKindV2.ABSENT,
        CategoryObservationKindV2.CONFLICTING,
        -> CategoryKnowledge.UNKNOWN
    }
}

fun behaviorIdentity(
    kind: BehaviorKind,
    optionId: String?,
    name: String,
    intent: String? = null,
): BehaviorIdentity {
    return BehaviorIdentity(
        kind = kind,
        key = categoricalKey(optionId, name),
        intent = if (kind == BehaviorKind.USAGE) intent else null,
    )
}

private val exposureOrder = compareBy<BehaviorExposure>(
    { it.identity.kind.ordinal },
    { it.identity.key },
    { it.identity.intent ?: "" },
    { it.displaySortKey() },
)

private fun BehaviorExposure.displaySortKey(): String = when (this) {
    is ReportedBehavior -> displayName
    is WorkoutBehavior -> "$displayName|$minutes"
    is UsageBehavior -> displayNames.joinToString(",") + "|" + amounts.joinToString(",") { it.rawAmountText }
}

enum class BehaviorKind {
    ACTIVITY,
    WORKOUT,
    FOOD,
    MEAL_SIZE,
    USAGE,
}

/**
 * [key] is the encoder's option id, or its normalized name when no option id
 * was stored. [intent] distinguishes supportive and coping usage. An amount
 * unit is not part of this identity.
 */
data class BehaviorIdentity(
    val kind: BehaviorKind,
    val key: String,
    val intent: String? = null,
)

data class BehaviorCatalogEntry(
    val identity: BehaviorIdentity,
    val displayNames: List<String>,
)

enum class CategoryKnowledge {
    /** Reviewed, and every known option in the category was observed absent. */
    REVIEWED_NONE,
    /** Reviewed. Listed exposures were present. Other catalogued options may later be treated as absent. */
    REVIEWED_ENTRIES,
    /** Unreviewed, missing, or conflicting. Not absence. */
    UNKNOWN,
}

data class BehavioralEvidenceDataset(
    val catalogScope: String,
    val catalog: List<BehaviorCatalogEntry>,
    val intervals: List<IntervalBehaviorEvidence>,
    val calendarDays: List<CalendarDayBehaviorEvidence>,
    val unplacedIntervalKeys: List<TransitionOccurrenceKey>,
) {
    init {
        require(catalogScope == BEHAVIOR_CATALOG_SCOPE) { "The catalog scope is the history it was given" }
    }
}

data class IntervalBehaviorEvidence(
    val key: TransitionOccurrenceKey,
    val startAt: Long,
    val endAt: Long,
    val durationMillis: Long,
    val startBasis: String,
    val completed: Boolean,
    val eligibility: CausalEligibility,
    val startStateKey: StateOccurrenceKey?,
    val endStateKey: StateOccurrenceKey,
    val activityReview: CategoryKnowledge,
    val workoutReview: CategoryKnowledge,
    val dietReview: CategoryKnowledge,
    val usageReview: CategoryKnowledge,
    val exposures: List<BehaviorExposure>,
    val startAxes: CoreAxisSnapshot?,
    val endAxes: CoreAxisSnapshot,
    /** Null on an axis that was not explicitly recorded at both endpoints. */
    val axisDeltas: CoreAxisDelta,
    /** Present only when a resolved starting state recorded an emotion. */
    val startEmotion: EmotionToken?,
    /** Present only when the ending check-in recorded an emotion. */
    val endEmotion: EmotionToken?,
)

data class CoreAxisSnapshot(
    val mood: Int?,
    val energy: Int?,
    val confidence: Int?,
    val anxiety: Int?,
)

data class CoreAxisDelta(
    val mood: Int?,
    val energy: Int?,
    val confidence: Int?,
    val anxiety: Int?,
) {
    fun measured(): List<Int> = listOfNotNull(mood, energy, confidence, anxiety)
}

data class CalendarDayBehaviorEvidence(
    val date: LocalDate,
    val zoneId: String,
    val startAt: Long,
    val endAt: Long,
    val stateOccurrenceIds: List<StateOccurrenceKey>,
    val completedExposureKeys: List<TransitionOccurrenceKey>,
    val incompleteCoverageKeys: List<TransitionOccurrenceKey>,
    val overlappingKeys: List<TransitionOccurrenceKey>,
)

sealed interface BehaviorExposure {
    val identity: BehaviorIdentity
}

data class ReportedBehavior(
    override val identity: BehaviorIdentity,
    val displayName: String,
) : BehaviorExposure

data class WorkoutBehavior(
    override val identity: BehaviorIdentity,
    val displayName: String,
    val minutes: Int,
) : BehaviorExposure

data class UsageBehavior(
    override val identity: BehaviorIdentity,
    val displayNames: List<String>,
    val amounts: List<UsageQuantityEvidence>,
    val comparable: List<ComparableUsageTotal>,
) : BehaviorExposure

enum class UsageQuantityStatus {
    MISSING,
    PARSED,
    UNPARSED,
}

data class UsageQuantityEvidence(
    val rawAmountText: String,
    val status: UsageQuantityStatus,
    val numericAmount: Double?,
    val unit: String?,
    val failure: UsageParseFailure?,
)

data class ComparableUsageTotal(
    val unit: String?,
    val total: Double,
    val perHour: Double?,
)
