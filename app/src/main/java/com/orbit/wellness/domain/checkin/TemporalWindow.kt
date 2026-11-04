package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A derived time range over a [CheckInAnalysisDatasetV2].
 *
 * [startAt] is inclusive and [endAt] is exclusive. [scale] says what kind of
 * range this is, such as [TemporalWindowScaleV1.CALENDAR_DAY] or
 * [TemporalWindowScaleV1.ROLLING]. [boundaryBasis] says how the instants were
 * chosen. A later wake-cycle factory can set its own basis and still produce
 * this same definition. This is not a recorded state and it is not stored.
 */
data class TemporalWindowDefinitionV1(
    val id: String,
    val scale: String,
    val startAt: Long,
    val endAt: Long,
    val boundaryBasis: String,
) {
    init {
        require(startAt < endAt) { "Window start must be earlier than its end" }
    }

    val durationMillis: Long
        get() = endAt - startAt
}

object TemporalWindowScaleV1 {
    const val CALENDAR_DAY = "calendar_day"
    const val ROLLING = "rolling"
}

/**
 * How a window's instants were chosen. [WAKE_CYCLE] is accepted by the
 * definition so a later factory can use the same projection. Nothing in this
 * pass creates a wake-cycle window.
 */
object TemporalBoundaryBasisV1 {
    const val CALENDAR_DAY = "calendar_day"
    const val EXPLICIT = "explicit"
    const val WAKE_CYCLE = "wake_cycle"
}

/**
 * References into an existing dataset. Interval ids are
 * [ContextIntervalObservationV2.endingCheckInId], the stored context-window key.
 * Context rows are not copied or divided across the boundary.
 */
data class TemporalWindowObservationV1(
    val definition: TemporalWindowDefinitionV1,
    val stateIds: List<String>,
    val containedIntervalIds: List<String>,
    val overlappingIntervalIds: List<String>,
)

/**
 * Two windows that meet without a gap or an overlap. The link is only that
 * the earlier window's exclusive end is the later window's start. It does not
 * say an exposure caused an outcome.
 */
data class TemporalWindowAdjacencyV1(
    val earlierWindowId: String,
    val laterWindowId: String,
)

/**
 * Ids of wearable observations that meet a window. The records themselves stay
 * on the wearable stream and are not copied into the window or into a state.
 */
data class WearableWindowReferenceV1(
    val definition: TemporalWindowDefinitionV1,
    val pointObservationIds: List<String>,
    val containedObservationIds: List<String>,
    val overlappingObservationIds: List<String>,
)

data class CalendarDayCoverageV1(
    val windows: List<TemporalWindowObservationV1>,
    val momentObservations: Int,
    val calendarDaysRepresented: Int,
    val fullyContainedIntervals: Int,
    val boundaryOverlappingIntervals: Int,
)

/**
 * Projects existing state and interval ids into caller-built windows.
 * It does not read or write Room, and it does not change [CheckInAnalysisDatasetV2].
 */
object TemporalWindowProjector {
    fun project(
        dataset: CheckInAnalysisDatasetV2,
        definition: TemporalWindowDefinitionV1,
    ): TemporalWindowObservationV1 {
        val stateIds = dataset.states
            .filter { it.recordedAt >= definition.startAt && it.recordedAt < definition.endAt }
            .sortedWith(compareBy({ it.recordedAt }, { it.checkInId }))
            .map { it.checkInId }
        val contained = mutableListOf<ContextIntervalObservationV2>()
        val overlapping = mutableListOf<ContextIntervalObservationV2>()
        for (interval in dataset.intervals) {
            when (
                classifyInterval(
                    intervalStart = interval.windowStartAt,
                    intervalEnd = interval.windowEndAt,
                    windowStart = definition.startAt,
                    windowEnd = definition.endAt,
                )
            ) {
                IntervalPlacement.CONTAINED -> contained += interval
                IntervalPlacement.OVERLAPPING -> overlapping += interval
                IntervalPlacement.OUTSIDE -> Unit
            }
        }
        return TemporalWindowObservationV1(
            definition = definition,
            stateIds = stateIds,
            containedIntervalIds = contained
                .sortedWith(compareBy({ it.windowStartAt }, { it.endingCheckInId }))
                .map { it.endingCheckInId },
            overlappingIntervalIds = overlapping
                .sortedWith(compareBy({ it.windowStartAt }, { it.endingCheckInId }))
                .map { it.endingCheckInId },
        )
    }

    /**
     * One calendar-day window per local date that has a state observation.
     * Days that an interval merely touches are not represented unless a state
     * falls on them. The zone is supplied here because check-ins do not store
     * the timezone they were recorded in.
     */
    fun calendarDays(
        dataset: CheckInAnalysisDatasetV2,
        zone: ZoneId,
    ): List<TemporalWindowObservationV1> {
        val dates = dataset.states
            .map { Instant.ofEpochMilli(it.recordedAt).atZone(zone).toLocalDate() }
            .distinct()
            .sorted()
        return dates.map { date -> project(dataset, calendarDay(date, zone)) }
    }

    fun calendarDayCoverage(
        dataset: CheckInAnalysisDatasetV2,
        zone: ZoneId,
    ): CalendarDayCoverageV1 {
        val windows = calendarDays(dataset, zone)
        val contained = linkedSetOf<String>()
        val overlapping = linkedSetOf<String>()
        for (window in windows) {
            contained += window.containedIntervalIds
            overlapping += window.overlappingIntervalIds
        }
        overlapping.removeAll(contained)
        return CalendarDayCoverageV1(
            windows = windows,
            momentObservations = windows.flatMap { it.stateIds }.distinct().size,
            calendarDaysRepresented = windows.size,
            fullyContainedIntervals = contained.size,
            boundaryOverlappingIntervals = overlapping.size,
        )
    }

    /**
     * Local midnight through the next local midnight. The real duration can be
     * 23 or 25 hours where daylight-saving time begins or ends. This is a
     * calendar day, not a waking day.
     */
    fun calendarDay(date: LocalDate, zone: ZoneId): TemporalWindowDefinitionV1 {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return TemporalWindowDefinitionV1(
            id = "calendar_day:${zone.id}:$date",
            scale = TemporalWindowScaleV1.CALENDAR_DAY,
            startAt = start,
            endAt = end,
            boundaryBasis = TemporalBoundaryBasisV1.CALENDAR_DAY,
        )
    }

    /** [endExclusiveAt] is the anchor. [duration] reaches backward from it. */
    fun rolling(endExclusiveAt: Long, duration: Duration): TemporalWindowDefinitionV1 {
        require(!duration.isZero && !duration.isNegative) { "Rolling duration must be positive" }
        val durationMillis = duration.toMillis()
        return explicit(
            startInclusiveAt = endExclusiveAt - durationMillis,
            endExclusiveAt = endExclusiveAt,
        )
    }

    fun explicit(
        startInclusiveAt: Long,
        endExclusiveAt: Long,
        id: String = "rolling:$startInclusiveAt:$endExclusiveAt",
    ): TemporalWindowDefinitionV1 {
        return TemporalWindowDefinitionV1(
            id = id,
            scale = TemporalWindowScaleV1.ROLLING,
            startAt = startInclusiveAt,
            endAt = endExclusiveAt,
            boundaryBasis = TemporalBoundaryBasisV1.EXPLICIT,
        )
    }

    /**
     * Places wearable observations by measurement time. A point uses
     * [WearableObservation.observedStartAt], not [WearableObservation.importedAt].
     * An interval or cumulative window uses the same contained, overlapping,
     * and outside rules as a context interval. Overlapping windows stay whole.
     */
    fun projectWearables(
        observations: List<WearableObservation>,
        definition: TemporalWindowDefinitionV1,
    ): WearableWindowReferenceV1 {
        val points = mutableListOf<WearableObservation>()
        val contained = mutableListOf<WearableObservation>()
        val overlapping = mutableListOf<WearableObservation>()
        for (observation in observations) {
            if (observation.temporalKind == WearableTemporalKind.POINT) {
                val at = observation.observedStartAt
                if (at >= definition.startAt && at < definition.endAt) {
                    points += observation
                }
            } else {
                when (
                    classifyInterval(
                        intervalStart = observation.observedStartAt,
                        intervalEnd = observation.observedEndAt,
                        windowStart = definition.startAt,
                        windowEnd = definition.endAt,
                    )
                ) {
                    IntervalPlacement.CONTAINED -> contained += observation
                    IntervalPlacement.OVERLAPPING -> overlapping += observation
                    IntervalPlacement.OUTSIDE -> Unit
                }
            }
        }
        return WearableWindowReferenceV1(
            definition = definition,
            pointObservationIds = points
                .sortedWith(compareBy({ it.observedStartAt }, { it.id }))
                .map { it.id },
            containedObservationIds = contained
                .sortedWith(compareBy({ it.observedStartAt }, { it.id }))
                .map { it.id },
            overlappingObservationIds = overlapping
                .sortedWith(compareBy({ it.observedStartAt }, { it.id }))
                .map { it.id },
        )
    }

    /**
     * Links ordered windows whose exclusive end is the next window's start.
     * A gap or an overlap is not a link.
     */
    fun adjacentWindows(
        definitions: List<TemporalWindowDefinitionV1>,
    ): List<TemporalWindowAdjacencyV1> {
        val ordered = definitions.sortedWith(compareBy({ it.startAt }, { it.endAt }, { it.id }))
        return ordered.zipWithNext().mapNotNull { (earlier, later) ->
            if (earlier.endAt == later.startAt) {
                TemporalWindowAdjacencyV1(earlier.id, later.id)
            } else {
                null
            }
        }
    }
}

internal enum class IntervalPlacement {
    CONTAINED,
    OVERLAPPING,
    OUTSIDE,
}

/**
 * The stored interval is [intervalStart, intervalEnd). A zero-length interval
 * is the single instant [intervalStart], using the same rule as a state.
 * Contained means the whole range sits in [windowStart, windowEnd). Crossing
 * either boundary, including a range that covers the window and continues
 * outside it, is overlapping. Context inside the interval is left whole.
 */
internal fun classifyInterval(
    intervalStart: Long,
    intervalEnd: Long,
    windowStart: Long,
    windowEnd: Long,
): IntervalPlacement {
    require(intervalStart <= intervalEnd) { "Interval start must not be later than its end" }
    if (intervalStart == intervalEnd) {
        return if (intervalStart >= windowStart && intervalStart < windowEnd) {
            IntervalPlacement.CONTAINED
        } else {
            IntervalPlacement.OUTSIDE
        }
    }
    val intersects = intervalStart < windowEnd && intervalEnd > windowStart
    if (!intersects) return IntervalPlacement.OUTSIDE
    val contained = intervalStart >= windowStart && intervalEnd <= windowEnd
    return if (contained) IntervalPlacement.CONTAINED else IntervalPlacement.OVERLAPPING
}
