package com.orbit.wellness.domain.checkin

import java.time.Duration
import kotlin.math.abs

/**
 * Past-only description of how check-ins were sampled before a focal state.
 *
 * The focal check-in is already known, so its trigger, core-axis coverage,
 * emotion presence, and conflicts stay on the context. Historical counts use
 * observations whose timestamps are strictly earlier. An observation at the
 * focal timestamp is not ordered before it.
 *
 * [ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION] means membership
 * follows the timestamps stored on the check-ins. The current schema does not
 * record when a value first became available. An earlier-timestamped check-in
 * that is added or edited later can therefore change a reconstructed context
 * for a focal state that already existed. [strictAsKnownAvailability] stays
 * false until a prospective ledger records availability separately. This pass
 * does not migrate Room.
 *
 * Counts of burstiness, density, missingness, and triggers describe the
 * observation process. They are not behavior differences, causes, or quality
 * scores. A later gate may use them for reliability. This pass does not.
 */
const val OBSERVATION_PROCESS_PROTOCOL = "observation-process-context-v1"

enum class ObservationAvailabilityMode {
    EVENT_TIME_RECONSTRUCTION,
}

enum class ObservationSamplingId(val requestedDuration: Duration?) {
    RESOLVED_WAKING_PATH(null),
    PRIOR_24_HOURS(Duration.ofHours(24)),
    PRIOR_7_DAYS(Duration.ofDays(7)),
}

enum class FocalAxisStatus {
    RECORDED,
    MISSING,
    CONFLICTING,
}

data class FocalAxisCoverage(
    val axisId: String,
    val status: FocalAxisStatus,
    val raw: Int?,
) {
    init {
        require((status == FocalAxisStatus.RECORDED) == (raw != null)) {
            "A recorded axis carries its raw value, including zero"
        }
        require(raw == null || raw in 0..10) { "A core axis reading stays on 0–10" }
    }
}

data class AxisSamplingCount(
    val axisId: String,
    val recordedCount: Int,
    val missingCount: Int,
    val conflictingCount: Int,
) {
    init {
        require(recordedCount >= 0 && missingCount >= 0 && conflictingCount >= 0)
    }
}

data class EmotionSamplingCount(
    val recordedCount: Int,
    val missingCount: Int,
) {
    init {
        require(recordedCount >= 0 && missingCount >= 0)
    }
}

data class TriggerCount(
    val trigger: String,
    val count: Int,
) {
    init {
        require(count > 0) { "A trigger with no observations is omitted" }
    }
}

data class GapSummary(
    val count: Int,
    val meanMillis: Double,
    val deviationMillis: Double,
    val medianMillis: Double,
    val minimumMillis: Long,
    val maximumMillis: Long,
) {
    init {
        require(count >= 1)
        require(meanMillis.isFinite() && deviationMillis >= 0.0 && medianMillis.isFinite())
        require(minimumMillis <= maximumMillis)
        require(medianMillis in minimumMillis.toDouble()..maximumMillis.toDouble())
        require(meanMillis in minimumMillis.toDouble()..maximumMillis.toDouble())
    }
}

data class ObservationSampling(
    val sampling: ObservationSamplingId,
    val observationCount: Int,
    val distinctTimestampCount: Int,
    val duplicateTimestampCount: Int,
    val requestedDurationMillis: Long?,
    val historyAvailableFrom: Long,
    val effectiveObservableDurationMillis: Long?,
    val coverageFraction: Double?,
    val earliestAt: Long?,
    val latestAt: Long?,
    val timeSinceLatestMillis: Long?,
    val gaps: GapSummary?,
    val observationRatePerHour: Double?,
    val axes: List<AxisSamplingCount>,
    val emotion: EmotionSamplingCount,
    val triggers: List<TriggerCount>,
    val occurrenceKeys: List<StateOccurrenceKey>,
) {
    init {
        require(observationCount == occurrenceKeys.size)
        require(distinctTimestampCount in 0..observationCount)
        require((distinctTimestampCount == 0) == (observationCount == 0))
        require(duplicateTimestampCount == observationCount - distinctTimestampCount)
        require((earliestAt == null) == (observationCount == 0))
        require((earliestAt == null) == (latestAt == null))
        require((latestAt == null) == (timeSinceLatestMillis == null))
        if (earliestAt != null && latestAt != null) require(earliestAt <= latestAt)
        require(timeSinceLatestMillis == null || timeSinceLatestMillis > 0L)
        require((gaps == null) == (distinctTimestampCount < 2))
        if (gaps != null) require(gaps.count == distinctTimestampCount - 1)
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(axes.all { it.recordedCount + it.missingCount + it.conflictingCount == observationCount })
        require(emotion.recordedCount + emotion.missingCount == observationCount)
        require(triggers.sumOf { it.count } == observationCount)
        require(triggers.map { it.trigger } == triggers.map { it.trigger }.distinct().sorted())
        val duration = effectiveObservableDurationMillis
        require((observationRatePerHour == null) == (duration == null || duration == 0L))
        if (observationRatePerHour != null && duration != null) {
            val expected = observationCount.toDouble() / (duration.toDouble() / MILLIS_PER_HOUR)
            require(abs(observationRatePerHour - expected) <= 1e-9)
        }
        when (sampling) {
            ObservationSamplingId.RESOLVED_WAKING_PATH -> {
                require(requestedDurationMillis == null && coverageFraction == null)
                if (observationCount == 0) {
                    require(effectiveObservableDurationMillis == null)
                } else {
                    require(effectiveObservableDurationMillis == timeSinceLatestMillis!! + (latestAt!! - earliestAt!!))
                }
            }
            else -> {
                val requested = checkNotNull(requestedDurationMillis)
                require(requested > 0L)
                require(coverageFraction != null && coverageFraction in 0.0..1.0)
                require(effectiveObservableDurationMillis != null && effectiveObservableDurationMillis >= 0L)
                require(
                    abs(
                        checkNotNull(coverageFraction) - effectiveObservableDurationMillis.toDouble() / requested.toDouble(),
                    ) <= 1e-9,
                )
            }
        }
    }
}

data class ObservationProcessContext(
    val stateKey: StateOccurrenceKey,
    val recordedAt: Long,
    val trigger: String,
    val emotion: String,
    val emotionPresent: Boolean,
    val axes: List<FocalAxisCoverage>,
    val conflictingAxisIds: List<String>,
    val samplings: List<ObservationSampling>,
) {
    init {
        require(emotionPresent == emotion.isNotEmpty())
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
        require(conflictingAxisIds == conflictingAxisIds.distinct())
        require(
            axes.filter { it.status == FocalAxisStatus.CONFLICTING }.map { it.axisId }.toSet() ==
                conflictingAxisIds.filter { it in CheckInAnalysisDatasetV2.CORE_AXIS_IDS }.toSet(),
        )
        require(samplings.map { it.sampling } == ObservationSamplingId.entries)
        val history = samplings.map { it.historyAvailableFrom }.distinct()
        require(history.size == 1 && history.single() <= recordedAt) {
            "Reconstructed history starts at one dataset timestamp no later than the focal"
        }
        samplings.forEach { sampling ->
            require(sampling.timeSinceLatestMillis == sampling.latestAt?.let { recordedAt - it })
        }
    }
}

data class ObservationProcessDataset(
    val protocol: String = OBSERVATION_PROCESS_PROTOCOL,
    val availabilityMode: ObservationAvailabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
    val strictAsKnownAvailability: Boolean = false,
    val pastOnly: Boolean = true,
    val predictive: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val contexts: List<ObservationProcessContext> = emptyList(),
) {
    init {
        require(protocol == OBSERVATION_PROCESS_PROTOCOL) { "Unexpected observation-process protocol" }
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION) {
            "This pass reconstructs sampling from stored event time"
        }
        require(!strictAsKnownAvailability) {
            "Stored event time is not a record of when each value first became known"
        }
        require(pastOnly && !predictive && !suitableForRecommendation) {
            "Observation-process evidence stays past-only and is not advice"
        }
        require(contexts == contexts.sortedWith(compareBy({ it.recordedAt }, { it.stateKey.raw }))) {
            "Observation-process contexts stay in recorded-time order"
        }
    }
}

fun projectObservationProcess(dataset: CheckInAnalysisDatasetV2): ObservationProcessDataset {
    if (dataset.states.isEmpty()) return ObservationProcessDataset()
    return projectObservationProcess(dataset, projectStateTrajectoryContext(dataset))
}

internal fun projectObservationProcess(
    dataset: CheckInAnalysisDatasetV2,
    trajectory: StateTrajectoryContextDataset,
): ObservationProcessDataset {
    if (dataset.states.isEmpty()) return ObservationProcessDataset()
    val states = dataset.states.associateBy { it.checkInId }
    val ordered = dataset.states.sortedWith(compareBy({ it.recordedAt }, { it.checkInId }))
    val historyFrom = ordered.first().recordedAt
    return ObservationProcessDataset(
        contexts = trajectory.contexts.map { context ->
            val focal = states.getValue(context.stateKey.raw)
            val path = context.resolvedPredecessorKeys
                .map { states.getValue(it.raw) }
                .filter { it.recordedAt < focal.recordedAt }
            ObservationProcessContext(
                stateKey = context.stateKey,
                recordedAt = focal.recordedAt,
                trigger = focal.trigger,
                emotion = focal.emotion,
                emotionPresent = focal.emotion.isNotEmpty(),
                axes = focalCoverage(focal),
                conflictingAxisIds = focal.conflictingAxisIds,
                samplings = ObservationSamplingId.entries.map { sampling ->
                    val members = when (sampling) {
                        ObservationSamplingId.RESOLVED_WAKING_PATH -> path
                        else -> windowMembers(focal.recordedAt, checkNotNull(sampling.requestedDuration), ordered)
                    }
                    summarize(
                        sampling = sampling,
                        members = members,
                        focalAt = focal.recordedAt,
                        historyFrom = historyFrom,
                    )
                },
            )
        },
    )
}

private fun summarize(
    sampling: ObservationSamplingId,
    members: List<StateObservationV2>,
    focalAt: Long,
    historyFrom: Long,
): ObservationSampling {
    val ordered = members.sortedWith(compareBy({ it.recordedAt }, { it.checkInId }))
    val times = ordered.map { it.recordedAt }
    val distinct = times.distinct().sorted()
    val earliest = times.minOrNull()
    val latest = times.maxOrNull()
    val requested = sampling.requestedDuration?.toMillis()
    val effective = when (sampling) {
        ObservationSamplingId.RESOLVED_WAKING_PATH -> earliest?.let { focalAt - it }
        else -> {
            val start = focalAt - checkNotNull(requested)
            (focalAt - maxOf(start, historyFrom)).coerceAtLeast(0L)
        }
    }
    val coverage = requested?.let { effective!!.toDouble() / it.toDouble() }
    val rate = if (effective == null || effective == 0L) {
        null
    } else {
        ordered.size.toDouble() / (effective.toDouble() / MILLIS_PER_HOUR)
    }
    return ObservationSampling(
        sampling = sampling,
        observationCount = ordered.size,
        distinctTimestampCount = distinct.size,
        duplicateTimestampCount = ordered.size - distinct.size,
        requestedDurationMillis = requested,
        historyAvailableFrom = historyFrom,
        effectiveObservableDurationMillis = effective,
        coverageFraction = coverage,
        earliestAt = earliest,
        latestAt = latest,
        timeSinceLatestMillis = latest?.let { focalAt - it },
        gaps = gapSummary(distinct),
        observationRatePerHour = rate,
        axes = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId -> axisCounts(axisId, ordered) },
        emotion = EmotionSamplingCount(
            recordedCount = ordered.count { it.emotion.isNotEmpty() },
            missingCount = ordered.count { it.emotion.isEmpty() },
        ),
        triggers = ordered.groupingBy { it.trigger }.eachCount()
            .map { (trigger, count) -> TriggerCount(trigger, count) }
            .sortedBy { it.trigger },
        occurrenceKeys = ordered.map { StateOccurrenceKey(it.checkInId) },
    )
}

private fun windowMembers(
    focalAt: Long,
    duration: Duration,
    states: List<StateObservationV2>,
): List<StateObservationV2> {
    val start = focalAt - duration.toMillis()
    return states.filter { it.recordedAt >= start && it.recordedAt < focalAt }
}

private fun gapSummary(distinctTimes: List<Long>): GapSummary? {
    if (distinctTimes.size < 2) return null
    val gaps = distinctTimes.zipWithNext { earlier, later -> later - earlier }
    val summary = checkNotNull(populationSummary(gaps.map { it.toDouble() }))
    return GapSummary(
        count = gaps.size,
        meanMillis = summary.mean,
        deviationMillis = summary.deviation,
        medianMillis = median(gaps.map { it.toDouble() }),
        minimumMillis = gaps.min(),
        maximumMillis = gaps.max(),
    )
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private fun axisCounts(axisId: String, states: List<StateObservationV2>): AxisSamplingCount {
    var recorded = 0
    var missing = 0
    var conflicting = 0
    states.forEach { state ->
        when (axisStatus(state, axisId)) {
            FocalAxisStatus.RECORDED -> recorded += 1
            FocalAxisStatus.MISSING -> missing += 1
            FocalAxisStatus.CONFLICTING -> conflicting += 1
        }
    }
    return AxisSamplingCount(axisId, recorded, missing, conflicting)
}

private fun focalCoverage(state: StateObservationV2): List<FocalAxisCoverage> {
    return CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        val status = axisStatus(state, axisId)
        FocalAxisCoverage(
            axisId = axisId,
            status = status,
            raw = if (status == FocalAxisStatus.RECORDED) state.core.inOrder().first { it.first == axisId }.second?.raw else null,
        )
    }
}

private fun axisStatus(state: StateObservationV2, axisId: String): FocalAxisStatus {
    if (axisId in state.conflictingAxisIds) return FocalAxisStatus.CONFLICTING
    val reading = state.core.inOrder().first { it.first == axisId }.second
    return if (reading == null) FocalAxisStatus.MISSING else FocalAxisStatus.RECORDED
}

private const val MILLIS_PER_HOUR = 3_600_000.0
