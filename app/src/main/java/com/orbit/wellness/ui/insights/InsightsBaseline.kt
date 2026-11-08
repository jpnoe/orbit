package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.domain.checkin.TemporalWindowProjector
import java.time.ZoneId

/** Zero check-ins invite a first entry. Anything stored is still only a baseline. */
enum class InsightsBaselineKind {
    EMPTY,
    COLLECTING,
}

/** Whether that kind of data is in the saved dataset. This is not a connection status. */
enum class RecordedAvailability {
    RECORDED,
    NOT_YET_RECORDED,
}

/**
 * The user-facing Insights summary.
 * It counts stored check-ins and the distinct calendar days they fall on.
 * It does not describe a learned pattern.
 */
data class InsightsBaseline(
    val kind: InsightsBaselineKind,
    val checkInCount: Int,
    val dayCount: Int,
    val checkInState: RecordedAvailability,
    val context: RecordedAvailability,
    val wearables: RecordedAvailability,
)

fun CheckInAnalysisSnapshot.toInsightsBaseline(
    hasWearableObservations: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): InsightsBaseline {
    val checkInCount = summary.stateObservationCount
    val dayCount = TemporalWindowProjector.calendarDayCoverage(dataset, zone).calendarDaysRepresented
    val hasContext = summary.categoryCounts.values.any { it.entries > 0 }
    return InsightsBaseline(
        kind = if (checkInCount == 0) InsightsBaselineKind.EMPTY else InsightsBaselineKind.COLLECTING,
        checkInCount = checkInCount,
        dayCount = dayCount,
        checkInState = recorded(checkInCount > 0),
        context = recorded(hasContext),
        wearables = recorded(hasWearableObservations),
    )
}

private fun recorded(present: Boolean): RecordedAvailability {
    return if (present) RecordedAvailability.RECORDED else RecordedAvailability.NOT_YET_RECORDED
}
