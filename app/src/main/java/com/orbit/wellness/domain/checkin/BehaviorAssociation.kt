package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * Result contract for association profiles.
 *
 * `behavior-association-v1` is the shape already on main, without emotion
 * distributions. These added fields change that result, so the identifier is
 * `behavior-association-v2`. The result is still pre-release and internal:
 * it is not stored, and it is not shown on Insights.
 */
const val BEHAVIOR_ASSOCIATION_PROTOCOL = "behavior-association-v2"

/**
 * Descriptive association profiles for one person's recorded history.
 *
 * This reads a [BehavioralEvidenceDataset]. It does not reopen Room, refit the
 * behavior catalog, or read a latent map. Differences are present minus absent.
 * They are not effects, ranks, or hypothesis tests. Emotion labels stay
 * categorical: they are counted, not numbered, averaged, or ranked.
 *
 * A conflicting category is unknown for behaviors in that category. If the
 * interval is inconsistent for another reason, including a conflict in a
 * different category, a reviewed category on that interval is excluded rather
 * than treated as absence.
 */
fun projectBehaviorAssociations(evidence: BehavioralEvidenceDataset): BehaviorAssociationDataset {
    val intervals = evidence.intervals.sortedWith(intervalOrder)
    val catalog = evidence.catalog.sortedWith(catalogOrder)
    val days = evidence.calendarDays.sortedBy { it.date }
    val axesByState = axesByState(intervals)
    val profiles = catalog.map { entry ->
        profile(entry, intervals, days, axesByState)
    }
    return BehaviorAssociationDataset(
        catalogScope = evidence.catalogScope,
        profiles = profiles,
        diagnostics = AssociationDiagnostics(
            behaviorCount = profiles.size,
            intervalCount = intervals.size,
            calendarDayCount = days.size,
            excludedIntervalCount = intervals.count { it.isAssociationExcluded() },
        ),
    )
}

private fun profile(
    entry: BehaviorCatalogEntry,
    intervals: List<IntervalBehaviorEvidence>,
    days: List<CalendarDayBehaviorEvidence>,
    axesByState: Map<StateOccurrenceKey, CoreAxisSnapshot>,
): BehaviorAssociationProfile {
    val classified = intervals.map { interval -> classify(interval, entry.identity) }
    val byKey = classified.associateBy { it.key }
    val present = classified.filter { it.status == BehaviorIntervalStatus.PRESENT }
    val absent = classified.filter { it.status == BehaviorIntervalStatus.ABSENT }
    val axes = CORE_ASSOCIATION_AXES.map { axis ->
        axisAssociation(axis, present, absent, intervals.associateBy { it.key })
    }
    return BehaviorAssociationProfile(
        identity = entry.identity,
        displayNames = entry.displayNames,
        intervals = classified,
        support = IntervalAssociationSupport(
            presentKeys = present.map { it.key },
            absentKeys = absent.map { it.key },
            unknownKeys = classified.filter { it.status == BehaviorIntervalStatus.UNKNOWN }.map { it.key },
            excludedKeys = classified.filter { it.status == BehaviorIntervalStatus.EXCLUDED }.map { it.key },
            presentOccurrences = present.sumOf { it.occurrenceCount },
        ),
        axes = axes,
        emotions = emotionAssociation(present, absent, intervals.associateBy { it.key }),
        calendarDays = calendarAssociation(days, byKey, axesByState),
        quantity = quantity(entry.identity, present, intervals.associateBy { it.key }),
    )
}

/**
 * Starting emotions use the same resolved-start rule as starting-state
 * association. Ending emotions use every present or absent interval. Unknown
 * and excluded intervals do not contribute. Token order is the stored name,
 * not the count.
 */
private fun emotionAssociation(
    present: List<ClassifiedBehaviorInterval>,
    absent: List<ClassifiedBehaviorInterval>,
    intervals: Map<TransitionOccurrenceKey, IntervalBehaviorEvidence>,
): EmotionAssociation {
    fun rows(classified: List<ClassifiedBehaviorInterval>) = classified.map { intervals.getValue(it.key) }
    val presentRows = rows(present)
    val absentRows = rows(absent)
    return EmotionAssociation(
        presentStarting = emotionDistribution(presentRows.filter { it.startStateKey != null }.map { it.startEmotion }),
        absentStarting = emotionDistribution(absentRows.filter { it.startStateKey != null }.map { it.startEmotion }),
        presentEnding = emotionDistribution(presentRows.map { it.endEmotion }),
        absentEnding = emotionDistribution(absentRows.map { it.endEmotion }),
    )
}

private fun emotionDistribution(tokens: List<EmotionToken?>): EmotionDistribution {
    val recorded = tokens.mapNotNull { it?.name }
    val counts = recorded.groupingBy { it }.eachCount()
        .entries
        .sortedBy { it.key }
        .map { (token, count) -> EmotionTokenCount(token = token, count = count) }
    return EmotionDistribution(
        eligibleCount = tokens.size,
        recordedCount = recorded.size,
        missingCount = tokens.size - recorded.size,
        tokens = counts,
    )
}

private fun classify(
    interval: IntervalBehaviorEvidence,
    identity: BehaviorIdentity,
): ClassifiedBehaviorInterval {
    val occurrences = occurrenceCount(interval, identity)
    val status = when {
        interval.isAssociationExcluded() -> BehaviorIntervalStatus.EXCLUDED
        interval.review(identity.kind) == CategoryKnowledge.UNKNOWN -> BehaviorIntervalStatus.UNKNOWN
        interval.eligibility.reasons.contains(CausalIneligibilityReason.INCONSISTENT_CONTEXT) ->
            BehaviorIntervalStatus.EXCLUDED
        occurrences > 0 -> BehaviorIntervalStatus.PRESENT
        else -> BehaviorIntervalStatus.ABSENT
    }
    return ClassifiedBehaviorInterval(
        key = interval.key,
        endAt = interval.endAt,
        status = status,
        occurrenceCount = if (status == BehaviorIntervalStatus.PRESENT) occurrences else 0,
    )
}

private fun IntervalBehaviorEvidence.isAssociationExcluded(): Boolean {
    if (!completed) return true
    if (startAt > endAt) return true
    val reasons = eligibility.reasons
    return CausalIneligibilityReason.INCOMPLETE_CONTEXT in reasons ||
        CausalIneligibilityReason.INVALID_CHRONOLOGY in reasons
}

private fun occurrenceCount(interval: IntervalBehaviorEvidence, identity: BehaviorIdentity): Int {
    return interval.exposures.sumOf { exposure ->
        if (exposure.identity != identity) {
            0
        } else when (exposure) {
            is UsageBehavior -> exposure.amounts.size.coerceAtLeast(1)
            else -> 1
        }
    }
}

private fun axisAssociation(
    axis: String,
    present: List<ClassifiedBehaviorInterval>,
    absent: List<ClassifiedBehaviorInterval>,
    intervals: Map<TransitionOccurrenceKey, IntervalBehaviorEvidence>,
): AxisAssociation {
    val presentSide = side(axis, present, intervals)
    val absentSide = side(axis, absent, intervals)
    return AxisAssociation(
        axisId = axis,
        present = presentSide,
        absent = absentSide,
        endingContrast = contrast(presentSide.ending, absentSide.ending),
        startingContrast = contrast(presentSide.starting, absentSide.starting),
        deltaContrast = contrast(presentSide.delta, absentSide.delta),
    )
}

private fun side(
    axis: String,
    classified: List<ClassifiedBehaviorInterval>,
    intervals: Map<TransitionOccurrenceKey, IntervalBehaviorEvidence>,
): StateTransitionSummaries {
    val rows = classified.map { intervals.getValue(it.key) }
    val ending = rows.filter { it.endAxes.value(axis) != null }
    val starting = rows.filter { it.startStateKey != null && it.startAxes?.value(axis) != null }
    val deltas = rows.filter { it.axisDeltas.value(axis) != null }
    return StateTransitionSummaries(
        starting = populationSummary(starting.map { it.startAxes!!.value(axis)!!.toDouble() }),
        ending = populationSummary(ending.map { it.endAxes.value(axis)!!.toDouble() }),
        delta = populationSummary(deltas.map { it.axisDeltas.value(axis)!!.toDouble() }),
        startingKeys = starting.map { it.key },
        endingKeys = ending.map { it.key },
        deltaKeys = deltas.map { it.key },
    )
}

private fun contrast(present: NumericSummary?, absent: NumericSummary?): DescriptiveContrast? {
    if (present == null || absent == null) return null
    return DescriptiveContrast(
        presentCount = present.count,
        absentCount = absent.count,
        difference = present.mean - absent.mean,
    )
}

private fun calendarAssociation(
    days: List<CalendarDayBehaviorEvidence>,
    statusByKey: Map<TransitionOccurrenceKey, ClassifiedBehaviorInterval>,
    axesByState: Map<StateOccurrenceKey, CoreAxisSnapshot>,
): CalendarDayAssociation {
    val grouped = days.groupBy { dayStatus(it, statusByKey) }
    val presentDays = grouped[DayBehaviorStatus.PRESENT].orEmpty()
    val absentDays = grouped[DayBehaviorStatus.ABSENT].orEmpty()
    val unknownDays = grouped[DayBehaviorStatus.UNKNOWN].orEmpty()
    val axes = CORE_ASSOCIATION_AXES.map { axis ->
        val present = populationSummary(presentDays.mapNotNull { dayMean(it, axis, axesByState) })
        val absent = populationSummary(absentDays.mapNotNull { dayMean(it, axis, axesByState) })
        CalendarAxisAssociation(
            axisId = axis,
            present = present,
            absent = absent,
            contrast = contrast(present, absent),
        )
    }
    return CalendarDayAssociation(
        presentDayCount = presentDays.size,
        absentDayCount = absentDays.size,
        unknownDayCount = unknownDays.size,
        presentDates = presentDays.map { it.date },
        absentDates = absentDays.map { it.date },
        unknownDates = unknownDays.map { it.date },
        axes = axes,
    )
}

private fun dayStatus(
    day: CalendarDayBehaviorEvidence,
    statusByKey: Map<TransitionOccurrenceKey, ClassifiedBehaviorInterval>,
): DayBehaviorStatus {
    val completed = day.completedExposureKeys.map { key -> statusByKey.getValue(key).status }
    if (completed.any { it == BehaviorIntervalStatus.PRESENT }) return DayBehaviorStatus.PRESENT
    val ambiguous = day.incompleteCoverageKeys.isNotEmpty() ||
        day.overlappingKeys.isNotEmpty() ||
        completed.isEmpty() ||
        completed.any { it != BehaviorIntervalStatus.ABSENT }
    return if (ambiguous) DayBehaviorStatus.UNKNOWN else DayBehaviorStatus.ABSENT
}

private fun dayMean(
    day: CalendarDayBehaviorEvidence,
    axis: String,
    axesByState: Map<StateOccurrenceKey, CoreAxisSnapshot>,
): Double? {
    val values = day.stateOccurrenceIds.mapNotNull { key -> axesByState[key]?.value(axis)?.toDouble() }
    if (values.isEmpty()) return null
    return values.sum() / values.size
}

private fun quantity(
    identity: BehaviorIdentity,
    present: List<ClassifiedBehaviorInterval>,
    intervals: Map<TransitionOccurrenceKey, IntervalBehaviorEvidence>,
): BehaviorQuantityCoverage {
    val rows = present.map { intervals.getValue(it.key) }
    return when (identity.kind) {
        BehaviorKind.WORKOUT -> workoutQuantity(identity, rows)
        BehaviorKind.USAGE -> usageQuantity(identity, rows)
        BehaviorKind.ACTIVITY, BehaviorKind.FOOD, BehaviorKind.MEAL_SIZE -> BehaviorQuantityCoverage.PresenceOnly
    }
}

private fun workoutQuantity(
    identity: BehaviorIdentity,
    rows: List<IntervalBehaviorEvidence>,
): BehaviorQuantityCoverage.WorkoutMinutes {
    val minutes = rows.flatMap { interval ->
        interval.exposures.filterIsInstance<WorkoutBehavior>()
            .filter { it.identity == identity && it.minutes >= 0 }
            .map { it.minutes.toDouble() }
    }
    val intervalsWithMinutes = rows.count { interval ->
        interval.exposures.filterIsInstance<WorkoutBehavior>().any { it.identity == identity && it.minutes >= 0 }
    }
    return BehaviorQuantityCoverage.WorkoutMinutes(
        intervalCount = intervalsWithMinutes,
        sessionCount = minutes.size,
        minutes = populationSummary(minutes),
    )
}

private fun usageQuantity(
    identity: BehaviorIdentity,
    rows: List<IntervalBehaviorEvidence>,
): BehaviorQuantityCoverage.UsageAmounts {
    val parsed = mutableListOf<List<ComparableUsageTotal>>()
    var missing = 0
    rows.forEach { interval ->
        val exposure = interval.exposures.filterIsInstance<UsageBehavior>().singleOrNull { it.identity == identity }
            ?: return@forEach
        if (exposure.amounts.isNotEmpty() && exposure.amounts.all { it.status == UsageQuantityStatus.PARSED }) {
            parsed += exposure.comparable
        } else {
            missing += 1
        }
    }
    val units = parsed.flatMap { it }
        .groupBy { it.unit }
        .entries
        .sortedWith(compareBy { it.key ?: "" })
        .map { (unit, totals) ->
            UsageUnitCoverage(
                unit = unit,
                parsedIntervalCount = totals.size,
                totalAmount = populationSummary(totals.map { it.total }),
                perHour = populationSummary(totals.mapNotNull { it.perHour }),
            )
        }
    return BehaviorQuantityCoverage.UsageAmounts(
        parsedIntervalCount = parsed.size,
        missingOrUnparsedIntervalCount = missing,
        units = units,
    )
}

private fun axesByState(intervals: List<IntervalBehaviorEvidence>): Map<StateOccurrenceKey, CoreAxisSnapshot> {
    val axes = linkedMapOf<StateOccurrenceKey, CoreAxisSnapshot>()
    intervals.forEach { interval ->
        val startKey = interval.startStateKey
        val startAxes = interval.startAxes
        if (startKey != null && startAxes != null) axes[startKey] = startAxes
        axes[interval.endStateKey] = interval.endAxes
    }
    return axes
}

/**
 * Population standard deviation, dividing by the observation count.
 * An empty list is unavailable. One observation has deviation zero.
 * A recorded zero stays an observation.
 */
fun populationSummary(values: List<Double>): NumericSummary? {
    if (values.isEmpty()) return null
    require(values.all { it.isFinite() }) { "An association summary only accepts finite observations" }
    val count = values.size
    val mean = values.sum() / count
    val deviation = sqrt(values.sumOf { value ->
        val delta = value - mean
        delta * delta
    } / count)
    return NumericSummary(
        count = count,
        mean = mean,
        deviation = deviation,
        minimum = values.min(),
        maximum = values.max(),
    )
}

private fun IntervalBehaviorEvidence.review(kind: BehaviorKind): CategoryKnowledge {
    return when (kind) {
        BehaviorKind.ACTIVITY -> activityReview
        BehaviorKind.WORKOUT -> workoutReview
        BehaviorKind.FOOD, BehaviorKind.MEAL_SIZE -> dietReview
        BehaviorKind.USAGE -> usageReview
    }
}

private fun CoreAxisSnapshot.value(axis: String): Int? = when (axis) {
    DefaultCheckInAxes.MOOD -> mood
    DefaultCheckInAxes.ENERGY -> energy
    DefaultCheckInAxes.CONFIDENCE -> confidence
    DefaultCheckInAxes.ANXIETY -> anxiety
    else -> null
}

private fun CoreAxisDelta.value(axis: String): Int? = when (axis) {
    DefaultCheckInAxes.MOOD -> mood
    DefaultCheckInAxes.ENERGY -> energy
    DefaultCheckInAxes.CONFIDENCE -> confidence
    DefaultCheckInAxes.ANXIETY -> anxiety
    else -> null
}

private val intervalOrder = compareBy<IntervalBehaviorEvidence>({ it.endAt }, { it.startAt }, { it.key.raw })

private val catalogOrder = compareBy<BehaviorCatalogEntry>(
    { it.identity.kind.ordinal },
    { it.identity.key },
    { it.identity.intent ?: "" },
)

val CORE_ASSOCIATION_AXES: List<String> = listOf(
    DefaultCheckInAxes.MOOD,
    DefaultCheckInAxes.ENERGY,
    DefaultCheckInAxes.CONFIDENCE,
    DefaultCheckInAxes.ANXIETY,
)

/**
 * Retrospective descriptive profiles for the catalog in one evidence dataset.
 * Nothing here is a rank, a probability, or a recommendation.
 */
data class BehaviorAssociationDataset(
    val protocol: String = BEHAVIOR_ASSOCIATION_PROTOCOL,
    val retrospective: Boolean = true,
    val descriptive: Boolean = true,
    val withinIndividualHistory: Boolean = true,
    val datasetRelative: Boolean = true,
    val causal: Boolean = false,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val catalogScope: String,
    val profiles: List<BehaviorAssociationProfile>,
    val diagnostics: AssociationDiagnostics,
) {
    init {
        require(protocol == BEHAVIOR_ASSOCIATION_PROTOCOL) { "Unexpected association protocol" }
        require(retrospective && descriptive && withinIndividualHistory && datasetRelative) {
            "Association profiles stay inside this history"
        }
        require(!causal && !predictive && !suitableForRecommendation) {
            "Association profiles are not predictions or recommendations"
        }
    }
}

data class AssociationDiagnostics(
    val behaviorCount: Int,
    val intervalCount: Int,
    val calendarDayCount: Int,
    val excludedIntervalCount: Int,
)

data class BehaviorAssociationProfile(
    val identity: BehaviorIdentity,
    val displayNames: List<String>,
    val intervals: List<ClassifiedBehaviorInterval>,
    val support: IntervalAssociationSupport,
    val axes: List<AxisAssociation>,
    val emotions: EmotionAssociation,
    val calendarDays: CalendarDayAssociation,
    val quantity: BehaviorQuantityCoverage,
)

data class EmotionAssociation(
    val presentStarting: EmotionDistribution,
    val absentStarting: EmotionDistribution,
    val presentEnding: EmotionDistribution,
    val absentEnding: EmotionDistribution,
)

data class EmotionDistribution(
    val eligibleCount: Int,
    val recordedCount: Int,
    val missingCount: Int,
    val tokens: List<EmotionTokenCount>,
) {
    init {
        require(eligibleCount == recordedCount + missingCount) { "Emotion support must add up" }
        require(tokens.sumOf { it.count } == recordedCount) { "Token counts must match recorded emotions" }
        require(tokens.map { it.token } == tokens.map { it.token }.distinct().sorted()) {
            "Emotion tokens stay in stored-name order"
        }
    }
}

data class EmotionTokenCount(
    val token: String,
    val count: Int,
) {
    init {
        require(token.isNotEmpty()) { "An empty emotion is missing, not a token" }
        require(count > 0) { "A token count is omitted when it is zero" }
    }
}

enum class BehaviorIntervalStatus {
    PRESENT,
    ABSENT,
    UNKNOWN,
    EXCLUDED,
}

data class ClassifiedBehaviorInterval(
    val key: TransitionOccurrenceKey,
    val endAt: Long,
    val status: BehaviorIntervalStatus,
    val occurrenceCount: Int,
)

data class IntervalAssociationSupport(
    val presentKeys: List<TransitionOccurrenceKey>,
    val absentKeys: List<TransitionOccurrenceKey>,
    val unknownKeys: List<TransitionOccurrenceKey>,
    val excludedKeys: List<TransitionOccurrenceKey>,
    val presentOccurrences: Int,
)

data class NumericSummary(
    val count: Int,
    val mean: Double,
    val deviation: Double,
    val minimum: Double,
    val maximum: Double,
) {
    init {
        require(count > 0) { "An empty summary is unavailable" }
        require(mean.isFinite() && deviation.isFinite() && deviation >= 0.0) { "A summary must be finite" }
    }
}

data class StateTransitionSummaries(
    val starting: NumericSummary?,
    val ending: NumericSummary?,
    val delta: NumericSummary?,
    val startingKeys: List<TransitionOccurrenceKey>,
    val endingKeys: List<TransitionOccurrenceKey>,
    val deltaKeys: List<TransitionOccurrenceKey>,
)

data class DescriptiveContrast(
    val presentCount: Int,
    val absentCount: Int,
    val difference: Double,
) {
    init {
        require(presentCount > 0 && absentCount > 0) { "A contrast needs both sides" }
        require(difference.isFinite()) { "A contrast must be finite" }
    }
}

data class AxisAssociation(
    val axisId: String,
    val present: StateTransitionSummaries,
    val absent: StateTransitionSummaries,
    val endingContrast: DescriptiveContrast?,
    val startingContrast: DescriptiveContrast?,
    val deltaContrast: DescriptiveContrast?,
)

enum class DayBehaviorStatus {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

data class CalendarDayAssociation(
    val presentDayCount: Int,
    val absentDayCount: Int,
    val unknownDayCount: Int,
    val presentDates: List<LocalDate>,
    val absentDates: List<LocalDate>,
    val unknownDates: List<LocalDate>,
    val axes: List<CalendarAxisAssociation>,
)

data class CalendarAxisAssociation(
    val axisId: String,
    val present: NumericSummary?,
    val absent: NumericSummary?,
    val contrast: DescriptiveContrast?,
)

sealed interface BehaviorQuantityCoverage {
    data object PresenceOnly : BehaviorQuantityCoverage

    data class WorkoutMinutes(
        val intervalCount: Int,
        val sessionCount: Int,
        val minutes: NumericSummary?,
    ) : BehaviorQuantityCoverage

    data class UsageAmounts(
        val parsedIntervalCount: Int,
        val missingOrUnparsedIntervalCount: Int,
        val units: List<UsageUnitCoverage>,
    ) : BehaviorQuantityCoverage
}

data class UsageUnitCoverage(
    val unit: String?,
    val parsedIntervalCount: Int,
    val totalAmount: NumericSummary?,
    val perHour: NumericSummary?,
)
