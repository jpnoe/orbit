package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val BEHAVIOR_CONTEXT_BALANCE_PROTOCOL = "behavior-context-balance-v1"

/** Below this resultant length, the circular mean is retained but weakly representative. */
const val WEAK_CLOCK_CONCENTRATION = 0.2

const val CLOCK_MINUTES_PER_DAY = 24 * 60

const val INTERVAL_MIDPOINT_LIMIT =
    "The interval midpoint describes the stored window. It is not an inferred event time."

/**
 * Descriptive context and overlap for one association dataset.
 *
 * This reads the evidence frames and the association classifications. It does
 * not reclassify intervals, fit an adjusted difference, or write Room. A wake anchor is the stored start of a completed, well-ordered
 * `wake_time` boundary. A day-start boundary is not a wake anchor. An anchor
 * applies to an endpoint only when that endpoint is on the anchor's local
 * date and the latest such anchor is unique. An interval whose start and end
 * select different anchors leaves both time-since-wake values unavailable.
 * Yesterday's wake is not carried across midnight.
 *
 * Pairwise distances use the raw 0–10 starting axes recorded on both sides.
 * Missing axes are left out, not filled with zero. The root-mean-square
 * divides by the shared-axis count. End-clock gaps are scheduling metadata.
 * Time-since-wake in a pair uses the interval start, which is known before
 * the ending state. Ending axes, deltas, and ending emotions are not inputs.
 */
fun projectBehaviorContextBalance(
    evidence: BehavioralEvidenceDataset,
    associations: BehaviorAssociationDataset,
    zone: ZoneId,
): BehaviorContextBalanceDataset {
    require(evidence.catalogScope == associations.catalogScope) {
        "Context balance requires one catalog scope"
    }
    val evidenceIdentities = evidence.catalog.map { it.identity }.toSet()
    val associationIdentities = associations.profiles.map { it.identity }.toSet()
    require(evidenceIdentities == associationIdentities) {
        "Context balance requires the same behavior identities"
    }
    val evidenceKeys = evidence.intervals.map { it.key }
    require(evidenceKeys.size == evidenceKeys.toSet().size) { "Interval keys must be unique" }
    val evidenceKeySet = evidenceKeys.toSet()
    associations.profiles.forEach { profile ->
        val keys = profile.intervals.map { it.key }
        require(keys.size == keys.toSet().size && keys.toSet() == evidenceKeySet) {
            "Context balance requires the association interval keys"
        }
    }
    val dayZones = evidence.calendarDays.map { it.zoneId }.distinct()
    require(dayZones.isEmpty() || dayZones == listOf(zone.id)) {
        "Context balance requires the evidence calendar zone"
    }
    val anchors = wakeAnchors(evidence.intervals, zone)
    val wake = assignWake(evidence.intervals, anchors, zone)
    val descriptors = evidence.intervals
        .sortedWith(descriptorOrder)
        .map { interval -> descriptor(interval, zone, wake.hours.getValue(interval.key)) }
    val byKey = descriptors.associateBy { it.key }
    val profiles = associations.profiles
        .sortedWith(compareBy({ it.identity.kind.ordinal }, { it.identity.key }, { it.identity.intent ?: "" }))
        .map { profile -> balanceProfile(profile, byKey) }
    return BehaviorContextBalanceDataset(
        zoneId = zone.id,
        intervals = descriptors,
        profiles = profiles,
        wakeDiagnostics = wake.diagnostics,
    )
}

internal fun localClockMinute(instant: Long, zone: ZoneId): Int {
    val local = Instant.ofEpochMilli(instant).atZone(zone)
    return local.hour * 60 + local.minute
}

internal fun circularClockGapMinutes(first: Int, second: Int): Double {
    val raw = abs(first - second)
    return minOf(raw, CLOCK_MINUTES_PER_DAY - raw).toDouble()
}

internal fun circularClockSummary(minutes: List<Int>): CircularClockSummary? {
    if (minutes.isEmpty()) return null
    val coords = minutes.map(::cyclicClock)
    val meanSine = coords.sumOf { it.sine } / minutes.size
    val meanCosine = coords.sumOf { it.cosine } / minutes.size
    val resultant = sqrt(meanSine * meanSine + meanCosine * meanCosine).let { value ->
        when {
            value < 0.0 -> 0.0
            abs(value - 1.0) < 1e-12 -> 1.0
            value > 1.0 -> 1.0
            else -> value
        }
    }
    var meanMinute = atan2(meanSine, meanCosine) / (2.0 * PI) * CLOCK_MINUTES_PER_DAY
    meanMinute %= CLOCK_MINUTES_PER_DAY.toDouble()
    if (meanMinute < 0.0) meanMinute += CLOCK_MINUTES_PER_DAY
    if (meanMinute >= CLOCK_MINUTES_PER_DAY - 1e-9) meanMinute = 0.0
    return CircularClockSummary(
        count = minutes.size,
        meanMinute = meanMinute,
        meanSine = meanSine,
        meanCosine = meanCosine,
        concentration = resultant,
        meanWeaklyRepresentative = resultant < WEAK_CLOCK_CONCENTRATION,
    )
}

private fun wakeAnchors(
    intervals: List<IntervalBehaviorEvidence>,
    zone: ZoneId,
): List<WakeAnchor> {
    return intervals.mapNotNull { interval ->
        if (interval.startBasis != CheckInContextStartBasis.WAKE_TIME) return@mapNotNull null
        if (!interval.completed || interval.startAt > interval.endAt) return@mapNotNull null
        WakeAnchor(
            instant = interval.startAt,
            key = interval.key,
            localDate = Instant.ofEpochMilli(interval.startAt).atZone(zone).toLocalDate(),
        )
    }.sortedWith(compareBy({ it.instant }, { it.key.raw }))
}

private fun assignWake(
    intervals: List<IntervalBehaviorEvidence>,
    anchors: List<WakeAnchor>,
    zone: ZoneId,
): WakeAssignment {
    val diagnostics = mutableListOf<WakeAnchorDiagnostic>()
    anchors.groupBy { it.instant }
        .filter { it.value.size > 1 }
        .toSortedMap()
        .forEach { (instant, group) ->
            diagnostics += WakeAnchorDiagnostic(
                kind = WakeAnchorIssueKind.DUPLICATE_INSTANT,
                keys = group.map { it.key }.sortedBy { it.raw },
                instant = instant,
            )
        }
    val hours = linkedMapOf<TransitionOccurrenceKey, WakeHours>()
    intervals.sortedWith(descriptorOrder).forEach { interval ->
        val start = selectAnchor(interval.startAt, anchors, zone)
        val end = selectAnchor(interval.endAt, anchors, zone)
        val incompatible = start is AnchorPick.Chosen && end is AnchorPick.Chosen &&
            start.anchor.instant != end.anchor.instant
        if (incompatible || start is AnchorPick.Ambiguous || end is AnchorPick.Ambiguous) {
            diagnostics += WakeAnchorDiagnostic(
                kind = WakeAnchorIssueKind.INCOMPATIBLE_ENDPOINTS,
                keys = listOf(interval.key),
                instant = null,
            )
            hours[interval.key] = WakeHours(startHours = null, endHours = null)
        } else {
            hours[interval.key] = WakeHours(
                startHours = (start as? AnchorPick.Chosen)?.let { hoursSince(interval.startAt, it.anchor) },
                endHours = (end as? AnchorPick.Chosen)?.let { hoursSince(interval.endAt, it.anchor) },
            )
        }
    }
    return WakeAssignment(
        hours = hours,
        diagnostics = diagnostics.sortedWith(
            compareBy({ it.kind.ordinal }, { it.instant ?: Long.MAX_VALUE }, { it.keys.first().raw }),
        ),
    )
}

private fun selectAnchor(endpoint: Long, anchors: List<WakeAnchor>, zone: ZoneId): AnchorPick {
    val date = Instant.ofEpochMilli(endpoint).atZone(zone).toLocalDate()
    val eligible = anchors.filter { it.localDate == date && it.instant <= endpoint }
    if (eligible.isEmpty()) return AnchorPick.None
    val latest = eligible.maxOf { it.instant }
    val atLatest = eligible.filter { it.instant == latest }
    if (atLatest.size != 1) return AnchorPick.Ambiguous
    return AnchorPick.Chosen(atLatest.single())
}

private fun hoursSince(endpoint: Long, anchor: WakeAnchor): Double {
    return (endpoint - anchor.instant) / 3_600_000.0
}

private fun descriptor(
    interval: IntervalBehaviorEvidence,
    zone: ZoneId,
    wake: WakeHours,
): IntervalContextDescriptor {
    val midpoint = if (interval.startAt <= interval.endAt) {
        localClockMinute(interval.startAt + (interval.endAt - interval.startAt) / 2, zone)
    } else {
        null
    }
    val startMinute = localClockMinute(interval.startAt, zone)
    val endMinute = localClockMinute(interval.endAt, zone)
    return IntervalContextDescriptor(
        key = interval.key,
        startAt = interval.startAt,
        endAt = interval.endAt,
        durationHours = interval.durationMillis / 3_600_000.0,
        zoneId = zone.id,
        startClockMinute = startMinute,
        endClockMinute = endMinute,
        midpointClockMinute = midpoint,
        startClock = cyclicClock(startMinute),
        endClock = cyclicClock(endMinute),
        midpointClock = midpoint?.let(::cyclicClock),
        startingAxes = interval.startAxes,
        startingAxisMissing = missingAxes(interval.startAxes),
        timeSinceWakeStartHours = wake.startHours,
        timeSinceWakeEndHours = wake.endHours,
    )
}

private fun missingAxes(axes: CoreAxisSnapshot?): List<String> {
    if (axes == null) return CORE_ASSOCIATION_AXES
    return CORE_ASSOCIATION_AXES.filter { axes.axis(it) == null }
}

private fun balanceProfile(
    profile: BehaviorAssociationProfile,
    byKey: Map<TransitionOccurrenceKey, IntervalContextDescriptor>,
): BehaviorContextBalanceProfile {
    val present = profile.intervals
        .filter { it.status == BehaviorIntervalStatus.PRESENT }
        .map { byKey.getValue(it.key) }
        .sortedWith(descriptorOrderOf)
    val absent = profile.intervals
        .filter { it.status == BehaviorIntervalStatus.ABSENT }
        .map { byKey.getValue(it.key) }
        .sortedWith(descriptorOrderOf)
    val pairs = present.flatMap { left ->
        absent.map { right -> overlapPair(left, right) }
    }
    return BehaviorContextBalanceProfile(
        identity = profile.identity,
        present = groupBalance(present),
        absent = groupBalance(absent),
        pairs = pairs,
        nearest = OppositeContextMeasure.entries.map { measure -> nearestSummary(measure, present, absent) },
    )
}

private fun groupBalance(rows: List<IntervalContextDescriptor>): ContextGroupBalance {
    return ContextGroupBalance(
        intervalCount = rows.size,
        startClock = circularClockSummary(rows.map { it.startClockMinute }),
        endClock = circularClockSummary(rows.map { it.endClockMinute }),
        midpointClock = circularClockSummary(rows.mapNotNull { it.midpointClockMinute }),
        durationHours = populationSummary(rows.map { it.durationHours }),
        timeSinceWakeStartHours = populationSummary(rows.mapNotNull { it.timeSinceWakeStartHours }),
        timeSinceWakeEndHours = populationSummary(rows.mapNotNull { it.timeSinceWakeEndHours }),
        startingAxes = CORE_ASSOCIATION_AXES.map { axis ->
            StartingAxisBalance(
                axisId = axis,
                summary = populationSummary(rows.mapNotNull { it.startingAxes.axis(axis)?.toDouble() }),
            )
        },
    )
}

private fun overlapPair(
    present: IntervalContextDescriptor,
    absent: IntervalContextDescriptor,
): ContextOverlapPair {
    val (shared, rms) = startingAxisDistance(present, absent)
    val midpointGap = if (present.midpointClockMinute != null && absent.midpointClockMinute != null) {
        circularClockGapMinutes(present.midpointClockMinute, absent.midpointClockMinute)
    } else {
        null
    }
    val wakeGap = if (present.timeSinceWakeStartHours != null && absent.timeSinceWakeStartHours != null) {
        abs(present.timeSinceWakeStartHours - absent.timeSinceWakeStartHours)
    } else {
        null
    }
    return ContextOverlapPair(
        presentKey = present.key,
        absentKey = absent.key,
        startClockGapMinutes = circularClockGapMinutes(present.startClockMinute, absent.startClockMinute),
        endClockGapMinutes = circularClockGapMinutes(present.endClockMinute, absent.endClockMinute),
        midpointClockGapMinutes = midpointGap,
        durationGapHours = abs(present.durationHours - absent.durationHours),
        durationRatio = if (present.durationHours > 0.0 && absent.durationHours > 0.0) {
            present.durationHours / absent.durationHours
        } else {
            null
        },
        timeSinceWakeGapHours = wakeGap,
        sharedStartingAxisCount = shared,
        startingAxisRms = rms,
    )
}

private fun startingAxisDistance(
    present: IntervalContextDescriptor,
    absent: IntervalContextDescriptor,
): Pair<Int, Double?> {
    val deltas = CORE_ASSOCIATION_AXES.mapNotNull { axis ->
        val left = present.startingAxes.axis(axis)
        val right = absent.startingAxes.axis(axis)
        if (left == null || right == null) null else (left - right).toDouble()
    }
    if (deltas.isEmpty()) return 0 to null
    return deltas.size to sqrt(deltas.sumOf { it * it } / deltas.size)
}

private fun nearestSummary(
    measure: OppositeContextMeasure,
    present: List<IntervalContextDescriptor>,
    absent: List<IntervalContextDescriptor>,
): NearestOppositeSummary {
    val matches = present.mapNotNull { row -> nearest(measure, row, absent) }
        .sortedWith(compareBy({ it.presentEndAt }, { it.presentKey.raw }))
    return NearestOppositeSummary(
        measure = measure,
        matches = matches,
        distances = populationSummary(matches.map { it.distance }),
        unavailablePresentCount = present.size - matches.size,
    )
}

private fun nearest(
    measure: OppositeContextMeasure,
    present: IntervalContextDescriptor,
    absent: List<IntervalContextDescriptor>,
): NearestOpposite? {
    val candidates = absent.mapNotNull { other ->
        val distance = distance(measure, present, other) ?: return@mapNotNull null
        ScoredAbsent(other, distance, sharedCount(measure, present, other))
    }
    val chosen = candidates.minWithOrNull(
        compareBy({ it.distance }, { it.absent.endAt }, { it.absent.startAt }, { it.absent.key.raw }),
    ) ?: return null
    return NearestOpposite(
        presentKey = present.key,
        presentEndAt = present.endAt,
        absentKey = chosen.absent.key,
        distance = chosen.distance,
        sharedStartingAxisCount = chosen.shared,
    )
}

private fun distance(
    measure: OppositeContextMeasure,
    present: IntervalContextDescriptor,
    absent: IntervalContextDescriptor,
): Double? {
    return when (measure) {
        OppositeContextMeasure.END_CLOCK ->
            circularClockGapMinutes(present.endClockMinute, absent.endClockMinute)
        OppositeContextMeasure.MIDPOINT_CLOCK -> {
            val left = present.midpointClockMinute
            val right = absent.midpointClockMinute
            if (left == null || right == null) null else circularClockGapMinutes(left, right)
        }
        OppositeContextMeasure.STARTING_AXIS_RMS -> startingAxisDistance(present, absent).second
        OppositeContextMeasure.TIME_SINCE_WAKE -> {
            val left = present.timeSinceWakeStartHours
            val right = absent.timeSinceWakeStartHours
            if (left == null || right == null) null else abs(left - right)
        }
        OppositeContextMeasure.DURATION -> abs(present.durationHours - absent.durationHours)
    }
}

private fun sharedCount(
    measure: OppositeContextMeasure,
    present: IntervalContextDescriptor,
    absent: IntervalContextDescriptor,
): Int? {
    return if (measure == OppositeContextMeasure.STARTING_AXIS_RMS) {
        startingAxisDistance(present, absent).first
    } else {
        null
    }
}

private fun cyclicClock(minute: Int): CyclicClock {
    val angle = minute / CLOCK_MINUTES_PER_DAY.toDouble() * 2.0 * PI
    return CyclicClock(minute = minute, sine = sin(angle), cosine = cos(angle))
}

private fun CoreAxisSnapshot?.axis(id: String): Int? = when (id) {
    DefaultCheckInAxes.MOOD -> this?.mood
    DefaultCheckInAxes.ENERGY -> this?.energy
    DefaultCheckInAxes.CONFIDENCE -> this?.confidence
    DefaultCheckInAxes.ANXIETY -> this?.anxiety
    else -> null
}

private val descriptorOrder = compareBy<IntervalBehaviorEvidence>({ it.endAt }, { it.startAt }, { it.key.raw })

private val descriptorOrderOf = compareBy<IntervalContextDescriptor>({ it.endAt }, { it.startAt }, { it.key.raw })

private data class WakeAnchor(
    val instant: Long,
    val key: TransitionOccurrenceKey,
    val localDate: java.time.LocalDate,
)

private data class WakeHours(
    val startHours: Double?,
    val endHours: Double?,
)

private data class WakeAssignment(
    val hours: Map<TransitionOccurrenceKey, WakeHours>,
    val diagnostics: List<WakeAnchorDiagnostic>,
)

private sealed interface AnchorPick {
    data object None : AnchorPick
    data object Ambiguous : AnchorPick
    data class Chosen(val anchor: WakeAnchor) : AnchorPick
}

private data class ScoredAbsent(
    val absent: IntervalContextDescriptor,
    val distance: Double,
    val shared: Int?,
)

data class BehaviorContextBalanceDataset(
    val protocol: String = BEHAVIOR_CONTEXT_BALANCE_PROTOCOL,
    val retrospective: Boolean = true,
    val descriptive: Boolean = true,
    val withinIndividualHistory: Boolean = true,
    val datasetRelative: Boolean = true,
    val causal: Boolean = false,
    val predictive: Boolean = false,
    val adjustedBehaviorEffect: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val zoneId: String,
    val midpointLimit: String = INTERVAL_MIDPOINT_LIMIT,
    val intervals: List<IntervalContextDescriptor>,
    val profiles: List<BehaviorContextBalanceProfile>,
    val wakeDiagnostics: List<WakeAnchorDiagnostic>,
) {
    init {
        require(protocol == BEHAVIOR_CONTEXT_BALANCE_PROTOCOL) { "Unexpected context-balance protocol" }
        require(retrospective && descriptive && withinIndividualHistory && datasetRelative) {
            "Context balance stays inside this history"
        }
        require(!causal && !predictive && !adjustedBehaviorEffect && !suitableForRecommendation) {
            "Context balance is not an adjusted difference"
        }
        require(midpointLimit == INTERVAL_MIDPOINT_LIMIT) { "The midpoint is a window descriptor" }
    }
}

data class BehaviorContextBalanceProfile(
    val identity: BehaviorIdentity,
    val present: ContextGroupBalance,
    val absent: ContextGroupBalance,
    val pairs: List<ContextOverlapPair>,
    val nearest: List<NearestOppositeSummary>,
)

data class IntervalContextDescriptor(
    val key: TransitionOccurrenceKey,
    val startAt: Long,
    val endAt: Long,
    val durationHours: Double,
    val zoneId: String,
    val startClockMinute: Int,
    val endClockMinute: Int,
    val midpointClockMinute: Int?,
    val startClock: CyclicClock,
    val endClock: CyclicClock,
    val midpointClock: CyclicClock?,
    val startingAxes: CoreAxisSnapshot?,
    val startingAxisMissing: List<String>,
    val timeSinceWakeStartHours: Double?,
    val timeSinceWakeEndHours: Double?,
)

data class CyclicClock(
    val minute: Int,
    val sine: Double,
    val cosine: Double,
)

data class CircularClockSummary(
    val count: Int,
    val meanMinute: Double,
    val meanSine: Double,
    val meanCosine: Double,
    val concentration: Double,
    val meanWeaklyRepresentative: Boolean,
) {
    init {
        require(count > 0) { "An empty circular summary is unavailable" }
        require(meanMinute in 0.0..<CLOCK_MINUTES_PER_DAY.toDouble()) { "A clock mean stays inside one day" }
        require(concentration in 0.0..1.0) { "Concentration stays between 0 and 1" }
    }
}

data class ContextGroupBalance(
    val intervalCount: Int,
    val startClock: CircularClockSummary?,
    val endClock: CircularClockSummary?,
    val midpointClock: CircularClockSummary?,
    val durationHours: NumericSummary?,
    val timeSinceWakeStartHours: NumericSummary?,
    val timeSinceWakeEndHours: NumericSummary?,
    val startingAxes: List<StartingAxisBalance>,
)

data class StartingAxisBalance(
    val axisId: String,
    val summary: NumericSummary?,
)

data class ContextOverlapPair(
    val presentKey: TransitionOccurrenceKey,
    val absentKey: TransitionOccurrenceKey,
    val startClockGapMinutes: Double,
    val endClockGapMinutes: Double,
    val midpointClockGapMinutes: Double?,
    val durationGapHours: Double,
    val durationRatio: Double?,
    val timeSinceWakeGapHours: Double?,
    val sharedStartingAxisCount: Int,
    val startingAxisRms: Double?,
)

enum class OppositeContextMeasure {
    END_CLOCK,
    MIDPOINT_CLOCK,
    STARTING_AXIS_RMS,
    TIME_SINCE_WAKE,
    DURATION,
}

data class NearestOppositeSummary(
    val measure: OppositeContextMeasure,
    val matches: List<NearestOpposite>,
    val distances: NumericSummary?,
    val unavailablePresentCount: Int,
)

data class NearestOpposite(
    val presentKey: TransitionOccurrenceKey,
    val presentEndAt: Long,
    val absentKey: TransitionOccurrenceKey,
    val distance: Double,
    val sharedStartingAxisCount: Int?,
)

enum class WakeAnchorIssueKind {
    DUPLICATE_INSTANT,
    INCOMPATIBLE_ENDPOINTS,
}

data class WakeAnchorDiagnostic(
    val kind: WakeAnchorIssueKind,
    val keys: List<TransitionOccurrenceKey>,
    val instant: Long?,
)
