package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis

const val BEHAVIOR_EXPOSURE_EPISODE_PROTOCOL = "behavior-exposure-episode-v1"

const val REPORTED_WINDOW_LIMIT =
    "The summed reported-window duration is the stored intervals. It is not how long the behavior occurred."

private const val MILLIS_PER_HOUR = 3_600_000.0

/**
 * Retrospective exposure episodes for one history.
 *
 * A present interval joins an earlier present interval only when the earlier
 * interval's ending state is the later interval's resolved starting state.
 * Wake-time and day-start intervals keep a null start. A stored previous id
 * on those boundaries is not turned into a starting state, so it does not
 * continue an episode.
 *
 * Each stored transition ends at its own check-in, so two intervals cannot
 * join into one state. A state can still have several outgoing intervals.
 * Those branches stay in one episode. Path totals follow the one predecessor
 * chain and do not add a sibling branch. Shared prefix intervals are counted
 * once in the episode-wide window sum.
 *
 * Classifications come from the association profile. This does not reclassify
 * intervals, estimate a quantity association, or write Room. Unknown evidence
 * stays a break, not observed absence. [PastOnlyExposureHistory] is the view
 * at a transition's start: it excludes that transition, its ending state, and
 * every later branch.
 */
fun projectBehaviorExposureEpisodes(
    evidence: BehavioralEvidenceDataset,
    graph: AlternatingTemporalGraph,
    associations: BehaviorAssociationDataset,
): BehaviorExposureEpisodeDataset {
    require(evidence.catalogScope == associations.catalogScope) {
        "Exposure episodes require one catalog scope"
    }
    val evidenceKeys = evidence.intervals.map { it.key }
    require(evidenceKeys.size == evidenceKeys.toSet().size) { "Interval keys must be unique" }
    require(evidence.intervals.groupBy { it.endStateKey }.values.all { it.size == 1 }) {
        "Each transition ends at its own state"
    }
    val graphIds = graph.transitions.map { it.id }
    require(graphIds.size == graphIds.toSet().size && graphIds.toSet() == evidenceKeys.map { it.raw }.toSet()) {
        "Exposure episodes require the same transition keys"
    }
    val transitions = graph.transitions.associateBy { it.id }
    evidence.intervals.forEach { interval ->
        val transition = checkNotNull(transitions[interval.key.raw])
        require(transition.endStateId == interval.endStateKey.raw) {
            "An interval end state must match the graph"
        }
        if (interval.isBoundary()) {
            require(interval.startStateKey == null) { "A boundary interval has no resolved start" }
        } else {
            require(interval.startStateKey?.raw == transition.startStateId) {
                "A resolved start must match the graph"
            }
        }
    }
    val evidenceIdentities = evidence.catalog.map { it.identity }.toSet()
    val associationIdentities = associations.profiles.map { it.identity }.toSet()
    require(evidenceIdentities == associationIdentities) {
        "Exposure episodes require the same behavior identities"
    }
    val evidenceKeySet = evidenceKeys.toSet()
    val associationByIdentity = associations.profiles.associateBy { it.identity }
    associationByIdentity.values.forEach { profile ->
        val keys = profile.intervals.map { it.key }
        require(keys.size == keys.toSet().size && keys.toSet() == evidenceKeySet) {
            "Exposure episodes require the association interval keys"
        }
    }
    val names = evidence.catalog.associate { it.identity to it.displayNames }
    val intervals = evidence.intervals.sortedWith(episodeIntervalOrder)
    val profiles = evidence.catalog
        .sortedWith(compareBy({ it.identity.kind.ordinal }, { it.identity.key }, { it.identity.intent ?: "" }))
        .map { entry ->
            exposureProfile(
                entry = entry,
                names = names,
                intervals = intervals,
                classified = associationByIdentity.getValue(entry.identity).intervals.associateBy { it.key },
            )
        }
    return BehaviorExposureEpisodeDataset(profiles = profiles)
}

private fun exposureProfile(
    entry: BehaviorCatalogEntry,
    names: Map<BehaviorIdentity, List<String>>,
    intervals: List<IntervalBehaviorEvidence>,
    classified: Map<TransitionOccurrenceKey, ClassifiedBehaviorInterval>,
): BehaviorExposureProfile {
    val status = intervals.associate { interval ->
        interval.key to classified.getValue(interval.key).status
    }
    val members = intervals.filter { status.getValue(it.key) == BehaviorIntervalStatus.PRESENT }
    val byKey = members.associateBy { it.key }
    val byEnd = members.associateBy { it.endStateKey }
    val predecessors = members.associate { member ->
        val start = member.startStateKey
        val prior = if (start == null) emptyList() else listOfNotNull(byEnd[start]).filter { it.key != member.key }
        member.key to prior.map { it.key }.sortedWith(keyOrder(byKey))
    }
    val successors = members.associate { member ->
        member.key to members
            .filter { predecessors.getValue(it.key).contains(member.key) }
            .map { it.key }
            .sortedWith(keyOrder(byKey))
    }
    val paths = members.associate { member -> member.key to uniquePath(member.key, predecessors, byKey) }
    val usage = entry.identity.kind == BehaviorKind.USAGE
    val pastOnly = intervals.map { interval ->
        pastOnly(interval, status, byEnd, paths, intervals, entry.identity)
    }
    val pastByKey = pastOnly.associateBy { it.transitionKey }
    val episodes = components(members, predecessors, successors)
        .map { component ->
            episode(
                identity = entry.identity,
                displayNames = entry.displayNames,
                component = component,
                byKey = byKey,
                predecessors = predecessors,
                successors = successors,
                paths = paths,
                pastByKey = pastByKey,
                names = names,
                intervals = intervals,
                status = status,
                usage = usage,
            )
        }
        .sortedWith(compareBy({ it.earliestStartAt }, { it.latestEndAt }, { it.id }))
    val contrast = if (classified.values.any { it.status == BehaviorIntervalStatus.ABSENT }) {
        PresenceContrastAvailability.AVAILABLE
    } else {
        PresenceContrastAvailability.UNAVAILABLE
    }
    return BehaviorExposureProfile(
        identity = entry.identity,
        displayNames = entry.displayNames,
        presenceContrast = contrast,
        episodes = episodes,
        pastOnly = pastOnly,
    )
}

private fun episode(
    identity: BehaviorIdentity,
    displayNames: List<String>,
    component: List<TransitionOccurrenceKey>,
    byKey: Map<TransitionOccurrenceKey, IntervalBehaviorEvidence>,
    predecessors: Map<TransitionOccurrenceKey, List<TransitionOccurrenceKey>>,
    successors: Map<TransitionOccurrenceKey, List<TransitionOccurrenceKey>>,
    paths: Map<TransitionOccurrenceKey, List<IntervalBehaviorEvidence>?>,
    pastByKey: Map<TransitionOccurrenceKey, PastOnlyExposureHistory>,
    names: Map<BehaviorIdentity, List<String>>,
    intervals: List<IntervalBehaviorEvidence>,
    status: Map<TransitionOccurrenceKey, BehaviorIntervalStatus>,
    usage: Boolean,
): BehaviorExposureEpisode {
    val componentSet = component.toSet()
    val ordered = topological(component, predecessors, byKey)
    val roots = ordered.filter { predecessors.getValue(it).isEmpty() }
    val terminals = ordered.filter { successors.getValue(it).isEmpty() }
    val idKeys = (roots.ifEmpty { ordered }).sortedWith(keyOrder(byKey))
    val occurrences = ordered.map { key ->
        occurrence(
            interval = byKey.getValue(key),
            predecessors = predecessors.getValue(key),
            path = paths.getValue(key),
            pastOnly = pastByKey.getValue(key),
            names = names,
            identity = identity,
            usage = usage,
        )
    }
    val branchPoints = ordered
        .mapNotNull { key -> branchPoint(byKey.getValue(key), successors.getValue(key), componentSet) }
        .sortedBy { it.stateKey.raw }
    val memberIntervals = ordered.map { byKey.getValue(it) }
    val breaks = intervals
        .filter { status.getValue(it.key) != BehaviorIntervalStatus.PRESENT }
        .flatMap { interval -> breaksTouching(interval, status.getValue(interval.key), memberIntervals) }
        .sortedWith(compareBy({ it.side.ordinal }, { it.key.raw }))
    val diagnostics = diagnostics(memberIntervals, roots, occurrences, branchPoints, usage)
    return BehaviorExposureEpisode(
        id = idKeys.joinToString("+") { it.raw },
        identity = identity,
        displayNames = displayNames,
        rootKeys = roots,
        terminalKeys = terminals,
        memberKeys = ordered,
        branchPoints = branchPoints,
        earliestStartAt = memberIntervals.minOf { it.startAt },
        latestEndAt = memberIntervals.maxOf { it.endAt },
        presentIntervalCount = ordered.size,
        reportedWindowDurationMillis = memberIntervals.sumOf { it.durationMillis },
        hasResolvedStartingState = memberIntervals.any { it.startStateKey != null },
        endingStateKeys = memberIntervals.map { it.endStateKey },
        breaks = breaks,
        diagnostics = diagnostics,
        occurrences = occurrences,
        coExposures = coExposureSummary(occurrences),
    )
}

private fun occurrence(
    interval: IntervalBehaviorEvidence,
    predecessors: List<TransitionOccurrenceKey>,
    path: List<IntervalBehaviorEvidence>?,
    pastOnly: PastOnlyExposureHistory,
    names: Map<BehaviorIdentity, List<String>>,
    identity: BehaviorIdentity,
    usage: Boolean,
): ExposureOccurrence {
    val unique = path != null &&
        predecessors.size <= 1 &&
        pastOnly.status == PastOnlyHistoryStatus.AVAILABLE
    val priorCount = if (unique) pastOnly.priorPresentIntervalCount else null
    val priorWindow = if (unique) pastOnly.priorReportedWindowMillis else null
    val currentUsage = if (usage) usageQuantity(interval, identity) else null
    val inclusiveTotals = if (
        usage && priorCount != null && pastOnly.priorUnitTotals != null && currentUsage?.complete == true
    ) {
        sumTotals(pastOnly.priorUnitTotals + currentUsage.currentTotals)
    } else {
        null
    }
    val inclusiveWindow = if (priorWindow != null) priorWindow + interval.durationMillis else null
    return ExposureOccurrence(
        key = interval.key,
        predecessorKeys = predecessors,
        pathPosition = if (unique) path.size - 1 else null,
        priorPresentIntervalCount = priorCount,
        inclusivePresentIntervalCount = priorCount?.plus(1),
        priorReportedWindowMillis = priorWindow,
        inclusiveReportedWindowMillis = inclusiveWindow,
        elapsedSinceRootMillis = if (unique) elapsed(path.first(), interval) else null,
        rootKey = if (unique) path.first().key else null,
        startStateKey = interval.startStateKey,
        endStateKey = interval.endStateKey,
        startAxes = interval.startAxes,
        endAxes = interval.endAxes,
        axisDeltas = interval.axisDeltas,
        startEmotion = interval.startEmotion,
        endEmotion = interval.endEmotion,
        reportedWindowMillis = interval.durationMillis,
        coExposures = coExposures(interval, identity, names),
        usage = currentUsage?.let { current ->
            current.copy(
                priorTotals = if (priorCount != null) pastOnly.priorUnitTotals else null,
                inclusiveTotals = inclusiveTotals,
                priorUnitlessPerHour = if (priorCount != null) pastOnly.priorUnitlessPerHour else null,
                inclusiveUnitlessPerHour = unitlessRate(inclusiveTotals, inclusiveWindow),
            )
        },
        pastOnly = pastOnly,
    )
}

private fun pastOnly(
    interval: IntervalBehaviorEvidence,
    status: Map<TransitionOccurrenceKey, BehaviorIntervalStatus>,
    membersByEnd: Map<StateOccurrenceKey, IntervalBehaviorEvidence>,
    paths: Map<TransitionOccurrenceKey, List<IntervalBehaviorEvidence>?>,
    allIntervals: List<IntervalBehaviorEvidence>,
    identity: BehaviorIdentity,
): PastOnlyExposureHistory {
    val anchor = interval.startStateKey
    if (anchor == null) {
        return if (interval.isBoundary()) {
            availableHistory(interval, anchor, emptyList(), identity)
        } else {
            unavailableHistory(interval, anchor, PastOnlyGap.UNRESOLVED_LINK)
        }
    }
    val incoming = membersByEnd[anchor]?.takeIf { it.key != interval.key }
    if (incoming != null) {
        val path = paths[incoming.key]
        return if (path == null) {
            unavailableHistory(interval, anchor, PastOnlyGap.AMBIGUOUS_PREDECESSOR)
        } else {
            availableHistory(interval, anchor, path, identity)
        }
    }
    val endedHere = allIntervals.filter { it.endStateKey == anchor && it.key != interval.key }
    val blocker = endedHere.singleOrNull()
    if (blocker == null) return availableHistory(interval, anchor, emptyList(), identity)
    val gap = when (status.getValue(blocker.key)) {
        BehaviorIntervalStatus.UNKNOWN -> PastOnlyGap.UNKNOWN_PRECEDING
        BehaviorIntervalStatus.EXCLUDED -> PastOnlyGap.EXCLUDED_PRECEDING
        BehaviorIntervalStatus.ABSENT -> return availableHistory(interval, anchor, emptyList(), identity)
        BehaviorIntervalStatus.PRESENT -> PastOnlyGap.AMBIGUOUS_PREDECESSOR
    }
    return unavailableHistory(interval, anchor, gap)
}

private fun availableHistory(
    interval: IntervalBehaviorEvidence,
    anchor: StateOccurrenceKey?,
    path: List<IntervalBehaviorEvidence>,
    identity: BehaviorIdentity,
): PastOnlyExposureHistory {
    val totals = cumulativeTotals(path, identity)
    val duration = path.sumOf { it.durationMillis }
    return PastOnlyExposureHistory(
        transitionKey = interval.key,
        anchorStateKey = anchor,
        status = PastOnlyHistoryStatus.AVAILABLE,
        gap = null,
        priorPresentIntervalCount = path.size,
        priorReportedWindowMillis = duration,
        priorUnitTotals = totals,
        priorUnitlessPerHour = unitlessRate(totals, duration),
    )
}

private fun unavailableHistory(
    interval: IntervalBehaviorEvidence,
    anchor: StateOccurrenceKey?,
    gap: PastOnlyGap,
): PastOnlyExposureHistory {
    return PastOnlyExposureHistory(
        transitionKey = interval.key,
        anchorStateKey = anchor,
        status = PastOnlyHistoryStatus.UNAVAILABLE,
        gap = gap,
        priorPresentIntervalCount = null,
        priorReportedWindowMillis = null,
        priorUnitTotals = null,
        priorUnitlessPerHour = null,
    )
}

private fun cumulativeTotals(
    path: List<IntervalBehaviorEvidence>,
    identity: BehaviorIdentity,
): List<UsageUnitTotal>? {
    if (identity.kind != BehaviorKind.USAGE) return null
    if (path.isEmpty()) return emptyList()
    val rows = path.map { usageQuantity(it, identity) }
    if (rows.any { !it.complete }) return null
    return sumTotals(rows.flatMap { it.currentTotals })
}

private fun usageQuantity(
    interval: IntervalBehaviorEvidence,
    identity: BehaviorIdentity,
): UsageExposureQuantity {
    val exposure = interval.exposures.filterIsInstance<UsageBehavior>().singleOrNull { it.identity == identity }
    val amounts = exposure?.amounts.orEmpty()
    val parsed = amounts.filter { it.status == UsageQuantityStatus.PARSED }
    val complete = amounts.isNotEmpty() && parsed.size == amounts.size
    val totals = if (complete) {
        parsed.groupBy { it.unit }
            .entries
            .sortedBy { it.key ?: "" }
            .map { (unit, rows) -> UsageUnitTotal(unit, rows.sumOf { it.numericAmount ?: 0.0 }) }
    } else {
        emptyList()
    }
    return UsageExposureQuantity(
        parsedCount = parsed.size,
        missingCount = amounts.count { it.status == UsageQuantityStatus.MISSING },
        unparsedCount = amounts.count { it.status == UsageQuantityStatus.UNPARSED },
        complete = complete,
        currentTotals = totals,
        currentUnitlessPerHour = unitlessRate(if (complete) totals else null, interval.durationMillis),
        priorTotals = null,
        inclusiveTotals = null,
        priorUnitlessPerHour = null,
        inclusiveUnitlessPerHour = null,
    )
}

private fun sumTotals(totals: List<UsageUnitTotal>): List<UsageUnitTotal> {
    return totals.groupBy { it.unit }
        .entries
        .sortedBy { it.key ?: "" }
        .map { (unit, rows) -> UsageUnitTotal(unit, rows.sumOf { it.total }) }
}

private fun unitlessRate(totals: List<UsageUnitTotal>?, durationMillis: Long?): Double? {
    if (totals == null || durationMillis == null || durationMillis <= 0L) return null
    val unitless = totals.singleOrNull { it.unit == null } ?: return null
    return unitless.total / (durationMillis / MILLIS_PER_HOUR)
}

private fun uniquePath(
    key: TransitionOccurrenceKey,
    predecessors: Map<TransitionOccurrenceKey, List<TransitionOccurrenceKey>>,
    byKey: Map<TransitionOccurrenceKey, IntervalBehaviorEvidence>,
): List<IntervalBehaviorEvidence>? {
    val reversed = mutableListOf<TransitionOccurrenceKey>()
    var cursor = key
    while (true) {
        if (cursor in reversed) return null
        reversed += cursor
        val prior = predecessors.getValue(cursor)
        if (prior.size > 1) return null
        if (prior.isEmpty()) break
        cursor = prior.single()
    }
    return reversed.asReversed().map { byKey.getValue(it) }
}

private fun components(
    members: List<IntervalBehaviorEvidence>,
    predecessors: Map<TransitionOccurrenceKey, List<TransitionOccurrenceKey>>,
    successors: Map<TransitionOccurrenceKey, List<TransitionOccurrenceKey>>,
): List<List<TransitionOccurrenceKey>> {
    val byKey = members.associateBy { it.key }
    val remaining = members.map { it.key }.toMutableSet()
    val components = mutableListOf<List<TransitionOccurrenceKey>>()
    members.forEach { start ->
        if (!remaining.remove(start.key)) return@forEach
        val queue = ArrayDeque<TransitionOccurrenceKey>()
        val found = mutableListOf(start.key)
        queue += start.key
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val neighbors = (predecessors.getValue(node) + successors.getValue(node)).sortedWith(keyOrder(byKey))
            neighbors.forEach { neighbor ->
                if (remaining.remove(neighbor)) {
                    found += neighbor
                    queue += neighbor
                }
            }
        }
        components += found
    }
    return components
}

private fun topological(
    component: List<TransitionOccurrenceKey>,
    predecessors: Map<TransitionOccurrenceKey, List<TransitionOccurrenceKey>>,
    byKey: Map<TransitionOccurrenceKey, IntervalBehaviorEvidence>,
): List<TransitionOccurrenceKey> {
    val pending = component.toMutableSet()
    val ordered = mutableListOf<TransitionOccurrenceKey>()
    while (pending.isNotEmpty()) {
        val ready = pending
            .filter { key -> predecessors.getValue(key).none { it in pending } }
            .sortedWith(keyOrder(byKey))
        val next = ready.firstOrNull() ?: pending.minWith(keyOrder(byKey))
        pending.remove(next)
        ordered += next
    }
    return ordered
}

private fun branchPoint(
    interval: IntervalBehaviorEvidence,
    outgoing: List<TransitionOccurrenceKey>,
    component: Set<TransitionOccurrenceKey>,
): ExposureBranchPoint? {
    if (outgoing.size < 2) return null
    return ExposureBranchPoint(
        stateKey = interval.endStateKey,
        incomingKey = interval.key.takeIf { it in component },
        outgoingKeys = outgoing,
    )
}

private fun breaksTouching(
    interval: IntervalBehaviorEvidence,
    status: BehaviorIntervalStatus,
    members: List<IntervalBehaviorEvidence>,
): List<ExposureBreak> {
    val reasons = breakReasons(interval, status)
    val breaks = mutableListOf<ExposureBreak>()
    members.forEach { member ->
        if (member.startStateKey != null && interval.endStateKey == member.startStateKey) {
            breaks += ExposureBreak(interval.key, ExposureBreakSide.PRECEDING, reasons)
        }
        if (interval.startStateKey != null && interval.startStateKey == member.endStateKey) {
            breaks += ExposureBreak(interval.key, ExposureBreakSide.FOLLOWING, reasons)
        }
    }
    return breaks
}

private fun breakReasons(
    interval: IntervalBehaviorEvidence,
    status: BehaviorIntervalStatus,
): List<ExposureBreakReason> {
    val reasons = mutableListOf<ExposureBreakReason>()
    when (status) {
        BehaviorIntervalStatus.ABSENT -> reasons += ExposureBreakReason.REVIEWED_ABSENCE
        BehaviorIntervalStatus.UNKNOWN -> reasons += ExposureBreakReason.UNKNOWN_EVIDENCE
        BehaviorIntervalStatus.EXCLUDED -> {
            if (!interval.completed || CausalIneligibilityReason.INCOMPLETE_CONTEXT in interval.eligibility.reasons) {
                reasons += ExposureBreakReason.INCOMPLETE
            }
            if (interval.startAt > interval.endAt) reasons += ExposureBreakReason.REVERSED_WINDOW
            if (CausalIneligibilityReason.INVALID_CHRONOLOGY in interval.eligibility.reasons) {
                reasons += ExposureBreakReason.INVALID_CHRONOLOGY
            }
            if (CausalIneligibilityReason.INCONSISTENT_CONTEXT in interval.eligibility.reasons) {
                reasons += ExposureBreakReason.INCONSISTENT_CONTEXT
            }
        }
        BehaviorIntervalStatus.PRESENT -> Unit
    }
    if (!interval.isBoundary() && interval.startStateKey == null) {
        reasons += ExposureBreakReason.UNRESOLVED_LINK
    }
    return reasons.distinct()
}

private fun diagnostics(
    members: List<IntervalBehaviorEvidence>,
    roots: List<TransitionOccurrenceKey>,
    occurrences: List<ExposureOccurrence>,
    branchPoints: List<ExposureBranchPoint>,
    usage: Boolean,
): List<EpisodeDiagnostic> {
    val rootSet = roots.toSet()
    val diagnostics = mutableListOf<EpisodeDiagnostic>()
    val boundaryRoots = members.filter { it.key in rootSet && it.isBoundary() }.map { it.key }
    if (boundaryRoots.isNotEmpty()) {
        diagnostics += EpisodeDiagnostic(EpisodeDiagnosticKind.BOUNDARY_ROOT, boundaryRoots)
    }
    val unresolved = members.filter { it.key in rootSet && !it.isBoundary() && it.startStateKey == null }.map { it.key }
    if (unresolved.isNotEmpty()) {
        diagnostics += EpisodeDiagnostic(EpisodeDiagnosticKind.UNRESOLVED_ROOT, unresolved)
    }
    if (branchPoints.isNotEmpty()) {
        diagnostics += EpisodeDiagnostic(EpisodeDiagnosticKind.BRANCH, branchPoints.map { it.incomingKey ?: it.outgoingKeys.first() })
    }
    val ambiguous = occurrences.filter { it.predecessorKeys.size > 1 }.map { it.key }
    if (ambiguous.isNotEmpty()) {
        diagnostics += EpisodeDiagnostic(EpisodeDiagnosticKind.AMBIGUOUS_PREDECESSOR, ambiguous)
    }
    if (usage && occurrences.any { it.usage?.complete != true }) {
        diagnostics += EpisodeDiagnostic(EpisodeDiagnosticKind.INCOMPLETE_QUANTITY, occurrences.filter { it.usage?.complete != true }.map { it.key })
    }
    return diagnostics
}

private fun coExposures(
    interval: IntervalBehaviorEvidence,
    focal: BehaviorIdentity,
    names: Map<BehaviorIdentity, List<String>>,
): List<CoExposure> {
    return interval.exposures
        .map { it.identity }
        .filter { it != focal }
        .distinct()
        .sortedWith(compareBy({ it.kind.ordinal }, { it.key }, { it.intent ?: "" }))
        .map { identity -> CoExposure(identity, names.getValue(identity)) }
}

private fun coExposureSummary(occurrences: List<ExposureOccurrence>): List<EpisodeCoExposure> {
    val counts = linkedMapOf<BehaviorIdentity, Int>()
    val names = linkedMapOf<BehaviorIdentity, List<String>>()
    occurrences.forEach { occurrence ->
        occurrence.coExposures.forEach { exposure ->
            counts[exposure.identity] = counts.getOrDefault(exposure.identity, 0) + 1
            names.putIfAbsent(exposure.identity, exposure.displayNames)
        }
    }
    return counts.entries
        .sortedWith(compareBy({ it.key.kind.ordinal }, { it.key.key }, { it.key.intent ?: "" }))
        .map { (identity, count) -> EpisodeCoExposure(identity, names.getValue(identity), count) }
}

private fun elapsed(root: IntervalBehaviorEvidence, current: IntervalBehaviorEvidence): Long? {
    val delta = current.startAt - root.startAt
    return if (delta >= 0L) delta else null
}

private fun IntervalBehaviorEvidence.isBoundary(): Boolean {
    return startBasis == CheckInContextStartBasis.WAKE_TIME || startBasis == CheckInContextStartBasis.DAY_START
}

private fun keyOrder(
    byKey: Map<TransitionOccurrenceKey, IntervalBehaviorEvidence>,
) = compareBy<TransitionOccurrenceKey>(
    { byKey.getValue(it).endAt },
    { byKey.getValue(it).startAt },
    { it.raw },
)

private val episodeIntervalOrder = compareBy<IntervalBehaviorEvidence>({ it.endAt }, { it.startAt }, { it.key.raw })

data class BehaviorExposureEpisodeDataset(
    val protocol: String = BEHAVIOR_EXPOSURE_EPISODE_PROTOCOL,
    val retrospective: Boolean = true,
    val descriptive: Boolean = true,
    val withinIndividualHistory: Boolean = true,
    val datasetRelative: Boolean = true,
    val causal: Boolean = false,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val reportedWindowLimit: String = REPORTED_WINDOW_LIMIT,
    val profiles: List<BehaviorExposureProfile>,
) {
    init {
        require(protocol == BEHAVIOR_EXPOSURE_EPISODE_PROTOCOL) { "Unexpected exposure-episode protocol" }
        require(retrospective && descriptive && withinIndividualHistory && datasetRelative) {
            "Exposure episodes stay inside this history"
        }
        require(!causal && !predictive && !suitableForRecommendation) {
            "Exposure episodes are not a cause or a recommendation"
        }
        require(reportedWindowLimit == REPORTED_WINDOW_LIMIT) {
            "Reported windows are not behavior duration"
        }
    }
}

data class BehaviorExposureProfile(
    val identity: BehaviorIdentity,
    val displayNames: List<String>,
    val presenceContrast: PresenceContrastAvailability,
    val episodes: List<BehaviorExposureEpisode>,
    val pastOnly: List<PastOnlyExposureHistory>,
)

enum class PresenceContrastAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

data class BehaviorExposureEpisode(
    val id: String,
    val identity: BehaviorIdentity,
    val displayNames: List<String>,
    val rootKeys: List<TransitionOccurrenceKey>,
    val terminalKeys: List<TransitionOccurrenceKey>,
    val memberKeys: List<TransitionOccurrenceKey>,
    val branchPoints: List<ExposureBranchPoint>,
    val earliestStartAt: Long,
    val latestEndAt: Long,
    val presentIntervalCount: Int,
    val reportedWindowDurationMillis: Long,
    val hasResolvedStartingState: Boolean,
    val endingStateKeys: List<StateOccurrenceKey>,
    val breaks: List<ExposureBreak>,
    val diagnostics: List<EpisodeDiagnostic>,
    val occurrences: List<ExposureOccurrence>,
    val coExposures: List<EpisodeCoExposure>,
) {
    init {
        require(presentIntervalCount == memberKeys.size) { "Present intervals are the episode members" }
        require(memberKeys.size == memberKeys.toSet().size) { "An episode counts each interval once" }
        require(memberKeys == occurrences.map { it.key }) { "Occurrence order follows the episode" }
        require(reportedWindowDurationMillis == occurrences.sumOf { it.reportedWindowMillis }) {
            "The reported window sums each member once"
        }
    }
}

data class ExposureBranchPoint(
    val stateKey: StateOccurrenceKey,
    val incomingKey: TransitionOccurrenceKey?,
    val outgoingKeys: List<TransitionOccurrenceKey>,
)

data class ExposureBreak(
    val key: TransitionOccurrenceKey,
    val side: ExposureBreakSide,
    val reasons: List<ExposureBreakReason>,
)

enum class ExposureBreakSide {
    PRECEDING,
    FOLLOWING,
}

enum class ExposureBreakReason {
    REVIEWED_ABSENCE,
    UNKNOWN_EVIDENCE,
    INCOMPLETE,
    INVALID_CHRONOLOGY,
    INCONSISTENT_CONTEXT,
    REVERSED_WINDOW,
    UNRESOLVED_LINK,
}

data class EpisodeDiagnostic(
    val kind: EpisodeDiagnosticKind,
    val keys: List<TransitionOccurrenceKey>,
)

enum class EpisodeDiagnosticKind {
    BOUNDARY_ROOT,
    UNRESOLVED_ROOT,
    BRANCH,
    AMBIGUOUS_PREDECESSOR,
    INCOMPLETE_QUANTITY,
}

data class ExposureOccurrence(
    val key: TransitionOccurrenceKey,
    val predecessorKeys: List<TransitionOccurrenceKey>,
    val pathPosition: Int?,
    val priorPresentIntervalCount: Int?,
    val inclusivePresentIntervalCount: Int?,
    val priorReportedWindowMillis: Long?,
    val inclusiveReportedWindowMillis: Long?,
    val elapsedSinceRootMillis: Long?,
    val rootKey: TransitionOccurrenceKey?,
    val startStateKey: StateOccurrenceKey?,
    val endStateKey: StateOccurrenceKey,
    val startAxes: CoreAxisSnapshot?,
    val endAxes: CoreAxisSnapshot,
    val axisDeltas: CoreAxisDelta,
    val startEmotion: EmotionToken?,
    val endEmotion: EmotionToken?,
    val reportedWindowMillis: Long,
    val coExposures: List<CoExposure>,
    val usage: UsageExposureQuantity?,
    val pastOnly: PastOnlyExposureHistory,
)

data class CoExposure(
    val identity: BehaviorIdentity,
    val displayNames: List<String>,
)

data class EpisodeCoExposure(
    val identity: BehaviorIdentity,
    val displayNames: List<String>,
    val intervalCount: Int,
)

data class UsageUnitTotal(
    val unit: String?,
    val total: Double,
)

data class UsageExposureQuantity(
    val parsedCount: Int,
    val missingCount: Int,
    val unparsedCount: Int,
    val complete: Boolean,
    val currentTotals: List<UsageUnitTotal>,
    val currentUnitlessPerHour: Double?,
    val priorTotals: List<UsageUnitTotal>?,
    val inclusiveTotals: List<UsageUnitTotal>?,
    val priorUnitlessPerHour: Double?,
    val inclusiveUnitlessPerHour: Double?,
)

/**
 * Exposure history visible at the start of [transitionKey].
 * Counts and totals are the predecessor path only.
 */
data class PastOnlyExposureHistory(
    val transitionKey: TransitionOccurrenceKey,
    val anchorStateKey: StateOccurrenceKey?,
    val status: PastOnlyHistoryStatus,
    val gap: PastOnlyGap?,
    val priorPresentIntervalCount: Int?,
    val priorReportedWindowMillis: Long?,
    val priorUnitTotals: List<UsageUnitTotal>?,
    val priorUnitlessPerHour: Double?,
) {
    init {
        val available = status == PastOnlyHistoryStatus.AVAILABLE
        require(available == (gap == null)) { "An available history has no gap" }
        require(available == (priorPresentIntervalCount != null)) { "An unavailable history has no count" }
        require(available == (priorReportedWindowMillis != null)) { "An unavailable history has no window" }
        if (!available) {
            require(priorUnitTotals == null && priorUnitlessPerHour == null) {
                "An unavailable history has no quantity"
            }
        }
    }
}

enum class PastOnlyHistoryStatus {
    AVAILABLE,
    UNAVAILABLE,
}

enum class PastOnlyGap {
    UNKNOWN_PRECEDING,
    EXCLUDED_PRECEDING,
    UNRESOLVED_LINK,
    AMBIGUOUS_PREDECESSOR,
}
