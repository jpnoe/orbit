package com.orbit.wellness.domain.checkin

/**
 * Fixed channels read from one [CausalPredecessorNeighborhood].
 *
 * Protocol [CAUSAL_PREDECESSOR_FEATURE_VIEW_PROTOCOL]. The neighborhood
 * contract stays the source. This view does not copy the graph, and it does
 * not read sampled backgrounds, episode counts, quantities, names, deeper-hop
 * summaries, or anything that begins at the focal state.
 *
 * An immediate interval is readable only when the ordered path accepted it as
 * depth 1. Ambiguity, an unresolved start, invalid chronology, and a cycle
 * that closes on the focal state leave that interval off the path. Those
 * cases are unavailable here, because the neighborhood does not carry the
 * rejected interval's duration or category review. They are not encoded as
 * reviewed absence or as an exposure count of zero.
 */
const val CAUSAL_PREDECESSOR_FEATURE_VIEW_PROTOCOL = "causal-predecessor-feature-view-v1"

private const val FEATURE_MILLIS_PER_HOUR = 3_600_000.0

enum class PredecessorFeatureChannel {
    IMMEDIATE_DURATION_HOURS,
    IMMEDIATE_INTERVAL_UNAVAILABLE,
    IMMEDIATE_EXPOSURE_COUNT,
    ACTIVITY_REVIEWED_ENTRIES,
    ACTIVITY_UNKNOWN,
    WORKOUT_REVIEWED_ENTRIES,
    WORKOUT_UNKNOWN,
    DIET_REVIEWED_ENTRIES,
    DIET_UNKNOWN,
    USAGE_REVIEWED_ENTRIES,
    USAGE_UNKNOWN,
    RESOLVED_PREDECESSOR_COUNT,
    HOURS_SINCE_BOUNDARY,
    BOUNDARY_UNAVAILABLE,
    ;

    val binary: Boolean
        get() = when (this) {
            IMMEDIATE_INTERVAL_UNAVAILABLE,
            ACTIVITY_REVIEWED_ENTRIES,
            ACTIVITY_UNKNOWN,
            WORKOUT_REVIEWED_ENTRIES,
            WORKOUT_UNKNOWN,
            DIET_REVIEWED_ENTRIES,
            DIET_UNKNOWN,
            USAGE_REVIEWED_ENTRIES,
            USAGE_UNKNOWN,
            BOUNDARY_UNAVAILABLE,
            -> true
            IMMEDIATE_DURATION_HOURS,
            IMMEDIATE_EXPOSURE_COUNT,
            RESOLVED_PREDECESSOR_COUNT,
            HOURS_SINCE_BOUNDARY,
            -> false
        }

    fun label(): String = when (this) {
        IMMEDIATE_DURATION_HOURS -> "immediate duration hours"
        IMMEDIATE_INTERVAL_UNAVAILABLE -> "immediate interval unavailable"
        IMMEDIATE_EXPOSURE_COUNT -> "immediate exposure count"
        ACTIVITY_REVIEWED_ENTRIES -> "activity reviewed entries"
        ACTIVITY_UNKNOWN -> "activity unknown"
        WORKOUT_REVIEWED_ENTRIES -> "workout reviewed entries"
        WORKOUT_UNKNOWN -> "workout unknown"
        DIET_REVIEWED_ENTRIES -> "diet reviewed entries"
        DIET_UNKNOWN -> "diet unknown"
        USAGE_REVIEWED_ENTRIES -> "usage reviewed entries"
        USAGE_UNKNOWN -> "usage unknown"
        RESOLVED_PREDECESSOR_COUNT -> "resolved predecessor count"
        HOURS_SINCE_BOUNDARY -> "hours since boundary"
        BOUNDARY_UNAVAILABLE -> "boundary unavailable"
    }
}

fun projectPredecessorFeatureView(neighborhood: CausalPredecessorNeighborhood): PredecessorFeatureView {
    val immediate = neighborhood.hops.firstOrNull()?.transition
    val predecessors = neighborhood.hops.count { it.predecessor != null }
    val boundary = neighborhood.hops.singleOrNull { it.boundary }
    val hoursSinceBoundary = boundary?.let { hop ->
        (neighborhood.focal.recordedAt - hop.transition.endAt) / FEATURE_MILLIS_PER_HOUR
    }
    val audit = PredecessorFeatureAudit(
        immediateDurationHours = immediate?.let { it.durationMillis / FEATURE_MILLIS_PER_HOUR },
        immediateIntervalUnavailable = immediate == null,
        immediateExposureCount = immediate?.let(::distinctExposureCount),
        activity = immediate?.activityReview,
        workout = immediate?.workoutReview,
        diet = immediate?.dietReview,
        usage = immediate?.usageReview,
        resolvedPredecessorCount = predecessors,
        hoursSinceBoundary = hoursSinceBoundary,
        boundaryUnavailable = boundary == null,
        boundaryEndAt = boundary?.transition?.endAt,
    )
    return PredecessorFeatureView(
        focalKey = neighborhood.focal.stateKey,
        recordedAt = neighborhood.focal.recordedAt,
        audit = audit,
        channels = PredecessorFeatureChannel.entries.map { channel ->
            PredecessorChannelReading(channel, rawChannel(channel, audit))
        },
    )
}

data class PredecessorFeatureView(
    val protocol: String = CAUSAL_PREDECESSOR_FEATURE_VIEW_PROTOCOL,
    val availabilityMode: ObservationAvailabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
    val strictAsKnownAvailability: Boolean = false,
    val pastOnly: Boolean = true,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val focalKey: StateOccurrenceKey,
    val recordedAt: Long,
    val audit: PredecessorFeatureAudit,
    val channels: List<PredecessorChannelReading>,
) {
    init {
        require(protocol == CAUSAL_PREDECESSOR_FEATURE_VIEW_PROTOCOL)
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION)
        require(!strictAsKnownAvailability && pastOnly && !predictive && !suitableForRecommendation)
        require(channels.map { it.channel } == PredecessorFeatureChannel.entries)
        require(reading(PredecessorFeatureChannel.IMMEDIATE_INTERVAL_UNAVAILABLE) == bit(audit.immediateIntervalUnavailable))
        require(reading(PredecessorFeatureChannel.IMMEDIATE_DURATION_HOURS) == audit.immediateDurationHours)
        require(reading(PredecessorFeatureChannel.IMMEDIATE_EXPOSURE_COUNT) == audit.immediateExposureCount?.toDouble())
        require(reading(PredecessorFeatureChannel.RESOLVED_PREDECESSOR_COUNT) == audit.resolvedPredecessorCount.toDouble())
        require(reading(PredecessorFeatureChannel.HOURS_SINCE_BOUNDARY) == audit.hoursSinceBoundary)
        require(reading(PredecessorFeatureChannel.BOUNDARY_UNAVAILABLE) == bit(audit.boundaryUnavailable))
        require(categoryBits(PredecessorFeatureChannel.ACTIVITY_REVIEWED_ENTRIES, PredecessorFeatureChannel.ACTIVITY_UNKNOWN, audit.activity))
        require(categoryBits(PredecessorFeatureChannel.WORKOUT_REVIEWED_ENTRIES, PredecessorFeatureChannel.WORKOUT_UNKNOWN, audit.workout))
        require(categoryBits(PredecessorFeatureChannel.DIET_REVIEWED_ENTRIES, PredecessorFeatureChannel.DIET_UNKNOWN, audit.diet))
        require(categoryBits(PredecessorFeatureChannel.USAGE_REVIEWED_ENTRIES, PredecessorFeatureChannel.USAGE_UNKNOWN, audit.usage))
        require((audit.immediateDurationHours == null) == audit.immediateIntervalUnavailable)
        require((audit.immediateExposureCount == null) == audit.immediateIntervalUnavailable)
        require((audit.activity == null) == audit.immediateIntervalUnavailable)
        require((audit.hoursSinceBoundary == null) == audit.boundaryUnavailable)
        require((audit.boundaryEndAt == null) == audit.boundaryUnavailable)
        require(audit.resolvedPredecessorCount >= 0)
        require(audit.immediateExposureCount == null || audit.immediateExposureCount >= 0)
        require(audit.immediateDurationHours == null || audit.immediateDurationHours.isFinite())
        require(audit.hoursSinceBoundary == null || (audit.hoursSinceBoundary.isFinite() && audit.hoursSinceBoundary >= 0.0))
    }

    fun reading(channel: PredecessorFeatureChannel): Double? = channels.single { it.channel == channel }.raw
}

data class PredecessorChannelReading(
    val channel: PredecessorFeatureChannel,
    val raw: Double?,
) {
    init {
        require(raw == null || raw.isFinite())
        if (channel.binary) require(raw == 0.0 || raw == 1.0)
    }
}

data class PredecessorFeatureAudit(
    val immediateDurationHours: Double?,
    val immediateIntervalUnavailable: Boolean,
    val immediateExposureCount: Int?,
    val activity: CategoryKnowledge?,
    val workout: CategoryKnowledge?,
    val diet: CategoryKnowledge?,
    val usage: CategoryKnowledge?,
    val resolvedPredecessorCount: Int,
    val hoursSinceBoundary: Double?,
    val boundaryUnavailable: Boolean,
    val boundaryEndAt: Long?,
)

private fun rawChannel(channel: PredecessorFeatureChannel, audit: PredecessorFeatureAudit): Double? {
    return when (channel) {
        PredecessorFeatureChannel.IMMEDIATE_DURATION_HOURS -> audit.immediateDurationHours
        PredecessorFeatureChannel.IMMEDIATE_INTERVAL_UNAVAILABLE -> bit(audit.immediateIntervalUnavailable)
        PredecessorFeatureChannel.IMMEDIATE_EXPOSURE_COUNT -> audit.immediateExposureCount?.toDouble()
        PredecessorFeatureChannel.ACTIVITY_REVIEWED_ENTRIES -> entriesBit(audit.activity)
        PredecessorFeatureChannel.ACTIVITY_UNKNOWN -> unknownBit(audit.activity)
        PredecessorFeatureChannel.WORKOUT_REVIEWED_ENTRIES -> entriesBit(audit.workout)
        PredecessorFeatureChannel.WORKOUT_UNKNOWN -> unknownBit(audit.workout)
        PredecessorFeatureChannel.DIET_REVIEWED_ENTRIES -> entriesBit(audit.diet)
        PredecessorFeatureChannel.DIET_UNKNOWN -> unknownBit(audit.diet)
        PredecessorFeatureChannel.USAGE_REVIEWED_ENTRIES -> entriesBit(audit.usage)
        PredecessorFeatureChannel.USAGE_UNKNOWN -> unknownBit(audit.usage)
        PredecessorFeatureChannel.RESOLVED_PREDECESSOR_COUNT -> audit.resolvedPredecessorCount.toDouble()
        PredecessorFeatureChannel.HOURS_SINCE_BOUNDARY -> audit.hoursSinceBoundary
        PredecessorFeatureChannel.BOUNDARY_UNAVAILABLE -> bit(audit.boundaryUnavailable)
    }
}

private fun PredecessorFeatureView.categoryBits(
    entries: PredecessorFeatureChannel,
    unknown: PredecessorFeatureChannel,
    knowledge: CategoryKnowledge?,
): Boolean {
    return reading(entries) == entriesBit(knowledge) && reading(unknown) == unknownBit(knowledge)
}

private fun entriesBit(knowledge: CategoryKnowledge?): Double = when (knowledge) {
    null, CategoryKnowledge.REVIEWED_NONE, CategoryKnowledge.UNKNOWN -> 0.0
    CategoryKnowledge.REVIEWED_ENTRIES -> 1.0
}

private fun unknownBit(knowledge: CategoryKnowledge?): Double = when (knowledge) {
    CategoryKnowledge.UNKNOWN -> 1.0
    null, CategoryKnowledge.REVIEWED_NONE, CategoryKnowledge.REVIEWED_ENTRIES -> 0.0
}

private fun bit(active: Boolean): Double = if (active) 1.0 else 0.0

private fun distinctExposureCount(transition: IntervalBehaviorEvidence): Int {
    return transition.exposures.map { it.identity }.toSet().size
}

fun predecessorFeatureCompactLabel(view: PredecessorFeatureView): String {
    val audit = view.audit
    val duration = audit.immediateDurationHours?.let { "immediate duration ${formatFeatureNumber(it)} hours" }
        ?: "immediate duration unavailable"
    val exposures = audit.immediateExposureCount?.let { "immediate exposure count $it" }
        ?: "immediate exposure count unavailable"
    val categories = listOf(
        "activity" to audit.activity,
        "workout" to audit.workout,
        "diet" to audit.diet,
        "usage" to audit.usage,
    ).joinToString(" · ") { (name, knowledge) -> "$name ${knowledge.phrase()}" }
    val boundary = audit.hoursSinceBoundary?.let { "hours since wake or day boundary ${formatFeatureNumber(it)}" }
        ?: "hours since wake or day boundary unavailable"
    return "Held-out outgoing behavior is excluded.\n" +
        "$duration\n" +
        "$exposures\n" +
        "$categories\n" +
        "resolved predecessors ${audit.resolvedPredecessorCount}\n" +
        boundary
}

fun predecessorFeatureAuditLabel(view: PredecessorFeatureView): String {
    val audit = view.audit
    val channels = view.channels.joinToString(" · ") { reading ->
        "${reading.channel.label()} ${reading.raw?.let(::formatFeatureNumber) ?: "missing"}"
    }
    return "focal ${view.focalKey.raw} · duration ${audit.immediateDurationHours ?: "missing"} · " +
        "exposure count ${audit.immediateExposureCount ?: "missing"} · " +
        "activity ${audit.activity ?: "missing"} · workout ${audit.workout ?: "missing"} · " +
        "diet ${audit.diet ?: "missing"} · usage ${audit.usage ?: "missing"} · " +
        "predecessors ${audit.resolvedPredecessorCount} · " +
        "boundary hours ${audit.hoursSinceBoundary ?: "missing"} · " +
        "boundary end ${audit.boundaryEndAt ?: "missing"} · $channels"
}

fun predecessorFeatureMissingLabel(): String =
    "Predecessor view unavailable. The candidate falls back to the locked null forecast."

private fun CategoryKnowledge?.phrase(): String = when (this) {
    null -> "unavailable"
    CategoryKnowledge.REVIEWED_NONE -> "reviewed absence"
    CategoryKnowledge.REVIEWED_ENTRIES -> "reviewed entries"
    CategoryKnowledge.UNKNOWN -> "unknown"
}

private fun formatFeatureNumber(value: Double): String {
    val rounded = kotlin.math.round(value * 1000.0) / 1000.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
