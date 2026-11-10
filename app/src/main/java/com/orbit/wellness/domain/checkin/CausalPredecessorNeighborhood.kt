package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextCategories
import java.time.ZoneId
import kotlin.math.max

/**
 * One past-only view of a stored focal state.
 *
 * `causal-predecessor-neighborhood-v1` follows the resolved incoming graph
 * path and samples earlier transitions that ended inside a fixed window.
 * It does not replace the stored state, fit a coefficient, or score a target.
 *
 * Deferred, and not represented here: retrospective-successor-neighborhood,
 * bidirectional-smoothed-state, and a past-only model of successor-cluster
 * probabilities.
 */
const val CAUSAL_PREDECESSOR_NEIGHBORHOOD_PROTOCOL = "causal-predecessor-neighborhood-v1"

const val CAUSAL_PREDECESSOR_NEIGHBORHOOD_ASSUMPTIONS =
    "Past-only event-time reconstruction of the graph predecessor path and sampled transition backgrounds. " +
        "Strict as-known availability is false. This is not a fitted model and it is not for recommendation. " +
        "It does not read a target or a residual. " +
        "Deferred and not implemented: retrospective-successor-neighborhood, bidirectional-smoothed-state, " +
        "and a past-only successor-cluster model."

enum class PredecessorStopReason {
    END_OF_PATH,
    BOUNDARY,
    UNRESOLVED,
    INVALID_CHRONOLOGY,
    AMBIGUOUS_PREDECESSOR,
    CYCLE,
}

enum class PredecessorBackgroundScale(val requestedDurationMillis: Long) {
    PRIOR_6_HOURS(6L * 3_600_000L),
    PRIOR_24_HOURS(24L * 3_600_000L),
    PRIOR_7_DAYS(7L * 24L * 3_600_000L),
}

fun projectCausalPredecessorNeighborhood(
    dataset: CheckInAnalysisDatasetV2,
    zone: ZoneId,
): CausalPredecessorNeighborhoodDataset {
    val graph = projectAlternatingTemporalGraph(dataset)
    val evidence = projectIntervalBehaviorEvidence(graph, dataset)
    val statesById = graph.states.associateBy { it.id }
    val neighborhoods = graph.states.map { focal ->
        projectOne(focal, graph, statesById, evidence, dataset, zone)
    }
    return CausalPredecessorNeighborhoodDataset(neighborhoods = neighborhoods)
}

data class CausalPredecessorNeighborhoodDataset(
    val protocol: String = CAUSAL_PREDECESSOR_NEIGHBORHOOD_PROTOCOL,
    val availabilityMode: ObservationAvailabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
    val strictAsKnownAvailability: Boolean = false,
    val pastOnly: Boolean = true,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val updatesModel: Boolean = false,
    val neighborhoods: List<CausalPredecessorNeighborhood>,
) {
    init {
        require(protocol == CAUSAL_PREDECESSOR_NEIGHBORHOOD_PROTOCOL)
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION)
        require(!strictAsKnownAvailability)
        require(pastOnly)
        require(!predictive)
        require(!suitableForRecommendation)
        require(!updatesModel)
        require(
            neighborhoods == neighborhoods.sortedWith(
                compareBy({ it.focal.recordedAt }, { it.focal.stateKey.raw }),
            ),
        ) { "Neighborhoods follow recorded time and then state id" }
    }
}

data class CausalPredecessorNeighborhood(
    val focal: PredecessorStateView,
    val hops: List<PredecessorHop>,
    val stop: PredecessorStop,
    val backgrounds: List<PredecessorBackground>,
) {
    init {
        require(hops.map { it.depth } == (1..hops.size).toList()) { "Depth is stored on each hop" }
        require(stop.depth == hops.size)
        require(backgrounds.map { it.scale } == PredecessorBackgroundScale.entries) {
            "Backgrounds stay in scale order"
        }
        if (hops.isNotEmpty()) {
            require(hops.first().transition.endStateKey == focal.stateKey)
        }
        hops.zipWithNext { current, next ->
            require(next.transition.endStateKey == current.predecessor?.stateKey) {
                "The next hop ends at the predecessor just adopted"
            }
        }
        hops.forEach { hop ->
            require(hop.transition.endAt <= focal.recordedAt) { "A hop ends at or before the focal state" }
            if (hop.boundary) {
                require(hop.predecessor == null) { "A boundary hop does not adopt a predecessor" }
            } else {
                require(hop.predecessor != null) { "A continuing hop names its predecessor" }
            }
        }
    }
}

data class PredecessorStateView(
    val stateKey: StateOccurrenceKey,
    val recordedAt: Long,
    val axes: List<FocalAxisCoverage>,
    val emotionPresent: Boolean,
    val emotion: String?,
    val trigger: String,
) {
    init {
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(emotionPresent == (emotion != null))
    }
}

data class PredecessorHop(
    val depth: Int,
    val transition: IntervalBehaviorEvidence,
    val predecessor: PredecessorStateView?,
    val boundary: Boolean,
) {
    init {
        require(depth >= 1)
    }
}

data class PredecessorCandidate(
    val transitionKey: TransitionOccurrenceKey,
    val previousCheckInId: String?,
    val startAt: Long,
    val endAt: Long,
)

data class PredecessorStop(
    val reason: PredecessorStopReason,
    val depth: Int,
    val stoppingTransitionKey: TransitionOccurrenceKey?,
    val unresolvedPreviousId: String?,
    val candidates: List<PredecessorCandidate>,
    val eligibility: CausalEligibility?,
) {
    init {
        require(depth >= 0)
        require(reason != PredecessorStopReason.AMBIGUOUS_PREDECESSOR || candidates.size > 1)
        require(reason != PredecessorStopReason.UNRESOLVED || stoppingTransitionKey != null)
    }
}

data class PredecessorBackground(
    val scale: PredecessorBackgroundScale,
    val windowStartAt: Long,
    val windowEndAt: Long,
    val requestedDurationMillis: Long,
    val effectiveDurationMillis: Long,
    val coverage: Double,
    val observationCount: Int,
    val distinctEndTimeCount: Int,
    val completedIntervals: List<IntervalBehaviorEvidence>,
    val incompleteOrIneligibleIntervals: List<IntervalBehaviorEvidence>,
    val categories: List<PredecessorCategoryCount>,
    val exposures: List<PredecessorExposureCount>,
    val distinctEpisodeCount: Int?,
) {
    val completedKeys: List<TransitionOccurrenceKey>
        get() = completedIntervals.map { it.key }

    val incompleteOrIneligibleKeys: List<TransitionOccurrenceKey>
        get() = incompleteOrIneligibleIntervals.map { it.key }

    init {
        require(windowEndAt > windowStartAt || requestedDurationMillis == 0L)
        require(requestedDurationMillis == scale.requestedDurationMillis)
        require(effectiveDurationMillis >= 0L)
        require(coverage.isFinite() && coverage in 0.0..1.0)
        require(observationCount == completedIntervals.size + incompleteOrIneligibleIntervals.size)
        require(distinctEndTimeCount >= 0)
        require(categories.map { it.category } == CheckInContextCategories.ALL)
        require(distinctEpisodeCount == null || distinctEpisodeCount >= 0)
        val members = completedIntervals + incompleteOrIneligibleIntervals
        require(members.all { it.endAt > windowStartAt && it.endAt <= windowEndAt })
        require(completedIntervals.all { it.completed && it.eligibility.eligible })
        require(incompleteOrIneligibleIntervals.none { it.completed && it.eligibility.eligible })
    }
}

data class PredecessorCategoryCount(
    val category: String,
    val reviewedNone: Int,
    val reviewedEntries: Int,
    val unknown: Int,
)

data class PredecessorExposureCount(
    val identity: BehaviorIdentity,
    val intervalCount: Int,
    val comparableTotals: List<ComparableUsageTotal>,
    val storedWorkoutMinutes: List<Int>,
) {
    init {
        require(intervalCount > 0)
    }
}

private fun projectOne(
    focal: StateOccurrenceNode,
    graph: AlternatingTemporalGraph,
    statesById: Map<String, StateOccurrenceNode>,
    evidence: List<IntervalBehaviorEvidence>,
    dataset: CheckInAnalysisDatasetV2,
    zone: ZoneId,
): CausalPredecessorNeighborhood {
    val (hops, stop) = orderedPath(focal, graph, statesById, evidence)
    val focalAt = focal.state.recordedAt
    val historyFrom = graph.states
        .filter { it.state.recordedAt <= focalAt }
        .minOf { it.state.recordedAt }
    val episodes = prefixEpisodes(dataset, focalAt, focal.id, zone)
    val backgrounds = PredecessorBackgroundScale.entries.map { scale ->
        background(scale, focalAt, historyFrom, evidence, episodes)
    }
    return CausalPredecessorNeighborhood(
        focal = stateView(focal.state),
        hops = hops,
        stop = stop,
        backgrounds = backgrounds,
    )
}

private fun orderedPath(
    focal: StateOccurrenceNode,
    graph: AlternatingTemporalGraph,
    statesById: Map<String, StateOccurrenceNode>,
    evidence: List<IntervalBehaviorEvidence>,
): Pair<List<PredecessorHop>, PredecessorStop> {
    val hops = mutableListOf<PredecessorHop>()
    val visited = mutableSetOf(focal.id)
    var cursor = focal
    while (true) {
        val incoming = graph.transitions
            .filter { it.endStateId == cursor.id }
            .sortedWith(
                compareBy(
                    { it.interval.windowStartAt },
                    { it.interval.previousCheckInId ?: "" },
                    { it.interval.windowEndAt },
                    { it.id },
                ),
            )
        if (incoming.isEmpty()) {
            return hops to stop(PredecessorStopReason.END_OF_PATH, hops.size)
        }
        if (incoming.size > 1) {
            return hops to stop(
                reason = PredecessorStopReason.AMBIGUOUS_PREDECESSOR,
                depth = hops.size,
                candidates = incoming.map { it.toCandidate() },
            )
        }
        val node = incoming.single()
        val transition = evidence.single { row ->
            row.key.raw == node.id &&
                row.startAt == node.interval.windowStartAt &&
                row.endAt == node.interval.windowEndAt &&
                row.durationMillis == node.interval.durationMillis
        }
        val eligibility = transition.eligibility
        if (CausalIneligibilityReason.BOUNDARY_INTERVAL in eligibility.reasons) {
            hops += PredecessorHop(hops.size + 1, transition, predecessor = null, boundary = true)
            return hops to stop(
                reason = PredecessorStopReason.BOUNDARY,
                depth = hops.size,
                stoppingTransitionKey = transition.key,
                eligibility = eligibility,
            )
        }
        if (node.startStateId == null || CausalIneligibilityReason.START_UNRESOLVED in eligibility.reasons) {
            return hops to stop(
                reason = PredecessorStopReason.UNRESOLVED,
                depth = hops.size,
                stoppingTransitionKey = transition.key,
                unresolvedPreviousId = node.interval.previousCheckInId,
                eligibility = eligibility,
            )
        }
        val startId = node.startStateId
        if (startId in visited) {
            return hops to stop(
                reason = PredecessorStopReason.CYCLE,
                depth = hops.size,
                stoppingTransitionKey = transition.key,
                eligibility = eligibility,
            )
        }
        val start = statesById[startId]
        if (
            start == null ||
            start.state.recordedAt >= cursor.state.recordedAt ||
            CausalIneligibilityReason.INVALID_CHRONOLOGY in eligibility.reasons
        ) {
            return hops to stop(
                reason = PredecessorStopReason.INVALID_CHRONOLOGY,
                depth = hops.size,
                stoppingTransitionKey = transition.key,
                eligibility = eligibility,
            )
        }
        hops += PredecessorHop(hops.size + 1, transition, stateView(start.state), boundary = false)
        visited += startId
        cursor = start
    }
}

private fun background(
    scale: PredecessorBackgroundScale,
    focalAt: Long,
    historyFrom: Long,
    evidence: List<IntervalBehaviorEvidence>,
    episodes: List<BehaviorExposureEpisode>?,
): PredecessorBackground {
    val requested = scale.requestedDurationMillis
    val windowStart = if (focalAt < requested) Long.MIN_VALUE else focalAt - requested
    val openedAt = max(windowStart, historyFrom)
    val effective = (focalAt - openedAt).coerceAtLeast(0L).coerceAtMost(requested)
    val coverage = if (requested == 0L) 0.0 else effective.toDouble() / requested.toDouble()
    val members = evidence
        .filter { it.endAt > windowStart && it.endAt <= focalAt }
        .sortedWith(compareBy({ it.endAt }, { it.startAt }, { it.key.raw }, { it.startStateKey?.raw ?: "" }))
    val completed = members.filter { it.completed && it.eligibility.eligible }
    val other = members.filterNot { it.completed && it.eligibility.eligible }
    val windowKeys = members.map { it.key }.toSet()
    val episodeCount = episodes?.count { episode -> episode.memberKeys.any { it in windowKeys } }
    return PredecessorBackground(
        scale = scale,
        windowStartAt = windowStart,
        windowEndAt = focalAt,
        requestedDurationMillis = requested,
        effectiveDurationMillis = effective,
        coverage = coverage.coerceIn(0.0, 1.0),
        observationCount = members.size,
        distinctEndTimeCount = members.map { it.endAt }.toSet().size,
        completedIntervals = completed,
        incompleteOrIneligibleIntervals = other,
        categories = categoryCounts(members),
        exposures = exposureCounts(members),
        distinctEpisodeCount = episodeCount,
    )
}

private fun prefixEpisodes(
    dataset: CheckInAnalysisDatasetV2,
    focalAt: Long,
    focalId: String,
    zone: ZoneId,
): List<BehaviorExposureEpisode>? {
    val intervals = dataset.intervals.filter { it.windowEndAt <= focalAt }
    val endingIds = intervals.map { it.endingCheckInId }
    if (endingIds.size != endingIds.toSet().size) return null
    val linkedIds = intervals.flatMap { listOfNotNull(it.endingCheckInId, it.previousCheckInId) }.toSet() + focalId
    val states = dataset.states.filter { it.recordedAt <= focalAt || it.checkInId in linkedIds }
    val stateIds = states.map { it.checkInId }.toSet()
    val issues = dataset.issues.filter { issue ->
        val id = issue.checkInId
        id == null || id in stateIds || id in endingIds
    }
    val prefix = dataset.copy(
        states = states,
        intervals = intervals,
        transitions = emptyList(),
        issues = issues,
    )
    val evidence = projectBehavioralEvidence(prefix, zone)
    val associations = projectBehaviorAssociations(evidence)
    val graph = projectAlternatingTemporalGraph(prefix)
    return projectBehaviorExposureEpisodes(evidence, graph, associations).profiles.flatMap { it.episodes }
}

private fun categoryCounts(members: List<IntervalBehaviorEvidence>): List<PredecessorCategoryCount> {
    val reviews = listOf(
        CheckInContextCategories.ACTIVITY to { interval: IntervalBehaviorEvidence -> interval.activityReview },
        CheckInContextCategories.WORKOUT to { interval: IntervalBehaviorEvidence -> interval.workoutReview },
        CheckInContextCategories.DIET to { interval: IntervalBehaviorEvidence -> interval.dietReview },
        CheckInContextCategories.USAGE to { interval: IntervalBehaviorEvidence -> interval.usageReview },
    )
    return reviews.map { (category, review) ->
        PredecessorCategoryCount(
            category = category,
            reviewedNone = members.count { review(it) == CategoryKnowledge.REVIEWED_NONE },
            reviewedEntries = members.count { review(it) == CategoryKnowledge.REVIEWED_ENTRIES },
            unknown = members.count { review(it) == CategoryKnowledge.UNKNOWN },
        )
    }
}

private fun exposureCounts(members: List<IntervalBehaviorEvidence>): List<PredecessorExposureCount> {
    data class Bucket(
        var intervals: Int = 0,
        val totals: MutableList<ComparableUsageTotal> = mutableListOf(),
        val minutes: MutableList<Int> = mutableListOf(),
    )
    val buckets = linkedMapOf<BehaviorIdentity, Bucket>()
    members.forEach { interval ->
        interval.exposures.groupBy { it.identity }.toSortedMap(
            compareBy({ it.kind.ordinal }, { it.key }, { it.intent ?: "" }),
        ).forEach { (identity, rows) ->
            val bucket = buckets.getOrPut(identity) { Bucket() }
            bucket.intervals += 1
            rows.forEach { exposure ->
                when (exposure) {
                    is UsageBehavior -> bucket.totals += exposure.comparable
                    is WorkoutBehavior -> bucket.minutes += exposure.minutes
                    is ReportedBehavior -> Unit
                }
            }
        }
    }
    return buckets.map { (identity, bucket) ->
        PredecessorExposureCount(
            identity = identity,
            intervalCount = bucket.intervals,
            comparableTotals = bucket.totals,
            storedWorkoutMinutes = bucket.minutes,
        )
    }.sortedWith(compareBy({ it.identity.kind.ordinal }, { it.identity.key }, { it.identity.intent ?: "" }))
}

private fun stateView(state: StateObservationV2): PredecessorStateView {
    val emotion = state.emotion.takeIf { it.isNotEmpty() }
    return PredecessorStateView(
        stateKey = StateOccurrenceKey(state.checkInId),
        recordedAt = state.recordedAt,
        axes = focalAxes(state),
        emotionPresent = emotion != null,
        emotion = emotion,
        trigger = state.trigger,
    )
}

private fun focalAxes(state: StateObservationV2): List<FocalAxisCoverage> {
    return CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        val reading = state.core.inOrder().first { it.first == axisId }.second
        val status = when {
            axisId in state.conflictingAxisIds -> FocalAxisStatus.CONFLICTING
            reading == null -> FocalAxisStatus.MISSING
            else -> FocalAxisStatus.RECORDED
        }
        FocalAxisCoverage(
            axisId = axisId,
            status = status,
            raw = if (status == FocalAxisStatus.RECORDED) reading?.raw else null,
        )
    }
}

private fun TransitionOccurrenceNode.toCandidate() = PredecessorCandidate(
    transitionKey = TransitionOccurrenceKey(id),
    previousCheckInId = interval.previousCheckInId,
    startAt = interval.windowStartAt,
    endAt = interval.windowEndAt,
)

private fun stop(
    reason: PredecessorStopReason,
    depth: Int,
    stoppingTransitionKey: TransitionOccurrenceKey? = null,
    unresolvedPreviousId: String? = null,
    candidates: List<PredecessorCandidate> = emptyList(),
    eligibility: CausalEligibility? = null,
) = PredecessorStop(
    reason = reason,
    depth = depth,
    stoppingTransitionKey = stoppingTransitionKey,
    unresolvedPreviousId = unresolvedPreviousId,
    candidates = candidates,
    eligibility = eligibility,
)

fun predecessorCompactLabel(neighborhood: CausalPredecessorNeighborhood): String {
    val states = 1 + neighborhood.hops.count { it.predecessor != null }
    val incoming = neighborhood.hops.firstOrNull()
    val incomingLine = if (incoming == null) {
        "no immediate incoming transition"
    } else {
        val reviews = categoryPhrase(incoming.transition)
        "immediate incoming $reviews · exposures ${incoming.transition.exposures.size}"
    }
    val windows = neighborhood.backgrounds.joinToString("\n") { background ->
        val episodes = background.distinctEpisodeCount?.toString() ?: "unavailable"
        "${background.scale.name} completed ${background.completedIntervals.size} · " +
            "other coverage ${background.incompleteOrIneligibleIntervals.size} · " +
            "observations ${background.observationCount} · episodes $episodes"
    }
    return "ordered depth ${neighborhood.hops.size} · states $states · transitions ${neighborhood.hops.size}\n" +
        "stop ${neighborhood.stop.reason.name}\n" +
        "$incomingLine\n" +
        windows + "\n" +
        "event-time reconstruction · strict as known false"
}

fun predecessorTechnicalLabel(neighborhood: CausalPredecessorNeighborhood): String {
    val focal = neighborhood.focal
    val axes = focal.axes.joinToString(", ") { axis ->
        val raw = axis.raw?.toString() ?: "none"
        "${axis.axisId} ${axis.status.name} $raw"
    }
    val emotion = if (focal.emotionPresent) focal.emotion else "absent"
    val hops = if (neighborhood.hops.isEmpty()) {
        "no hops"
    } else {
        neighborhood.hops.joinToString("\n") { hop ->
            val predecessor = hop.predecessor?.stateKey?.raw ?: "none"
            val gaps = hop.transition.eligibility.reasons.joinToString(",") { it.name }.ifEmpty { "none" }
            val identities = hop.transition.exposures.joinToString(",") { exposure ->
                "${exposure.identity.kind.name}:${exposure.identity.key}"
            }.ifEmpty { "none" }
            "depth ${hop.depth} · transition ${hop.transition.key.raw} · predecessor $predecessor · " +
                "boundary ${hop.boundary} · ${hop.transition.startAt} to ${hop.transition.endAt} · " +
                "duration ${hop.transition.durationMillis} · basis ${hop.transition.startBasis} · " +
                "completed ${hop.transition.completed} · gaps $gaps · " +
                categoryPhrase(hop.transition) + " · identities $identities"
        }
    }
    val stop = neighborhood.stop
    val candidates = if (stop.candidates.isEmpty()) {
        "none"
    } else {
        stop.candidates.joinToString("; ") { candidate ->
            "${candidate.transitionKey.raw} previous ${candidate.previousCheckInId} " +
                "${candidate.startAt} to ${candidate.endAt}"
        }
    }
    val windows = neighborhood.backgrounds.joinToString("\n") { background ->
        val completed = background.completedKeys.joinToString(",") { it.raw }.ifEmpty { "none" }
        val other = background.incompleteOrIneligibleKeys.joinToString(",") { it.raw }.ifEmpty { "none" }
        val categories = background.categories.joinToString("; ") { category ->
            "${category.category} none ${category.reviewedNone} entries ${category.reviewedEntries} unknown ${category.unknown}"
        }
        val exposures = if (background.exposures.isEmpty()) {
            "none"
        } else {
            background.exposures.joinToString("; ") { exposure ->
                val totals = exposure.comparableTotals.joinToString(",") { total ->
                    "${total.unit ?: "unitless"} ${total.total}"
                }.ifEmpty { "none" }
                "${exposure.identity.kind.name}:${exposure.identity.key} intervals ${exposure.intervalCount} " +
                    "minutes ${exposure.storedWorkoutMinutes} totals $totals"
            }
        }
        "${background.scale.name} ${background.windowStartAt} to ${background.windowEndAt} · " +
            "requested ${background.requestedDurationMillis} · effective ${background.effectiveDurationMillis} · " +
            "coverage ${background.coverage} · end times ${background.distinctEndTimeCount} · " +
            "completed $completed · other $other · $categories · $exposures"
    }
    return CAUSAL_PREDECESSOR_NEIGHBORHOOD_ASSUMPTIONS + "\n" +
        "focal ${focal.stateKey.raw} at ${focal.recordedAt} · $axes · emotion $emotion · trigger ${focal.trigger}\n" +
        hops + "\n" +
        "stop ${stop.reason.name} at depth ${stop.depth} · transition ${stop.stoppingTransitionKey?.raw ?: "none"} · " +
        "unresolved ${stop.unresolvedPreviousId ?: "none"} · candidates $candidates\n" +
        windows
}

fun predecessorMissingLabel(): String = "This anchor has no predecessor neighborhood."

private fun categoryPhrase(transition: IntervalBehaviorEvidence): String {
    return "activity ${transition.activityReview.name} · workout ${transition.workoutReview.name} · " +
        "diet ${transition.dietReview.name} · usage ${transition.usageReview.name}"
}
