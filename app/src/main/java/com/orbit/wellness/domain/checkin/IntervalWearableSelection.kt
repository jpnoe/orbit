package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind

/**
 * Wearable rows that belong to one stored context interval.
 *
 * History and the contextual evidence projection both use this selection.
 * Placement comes from [TemporalWindowProjector.projectWearables]: measurement
 * time, not import time, and a wider cumulative window stays overlapping rather
 * than contained. Sleep is not a metric of an ordinary waking interval.
 * An exact aggregate for a metric replaces other contained spans of that
 * metric. Several contained spans with no exact aggregate are all kept and are
 * not added together. Resting heart rate keeps the latest point in the interval.
 */
object IntervalWearableSelection {
    val METRICS: List<String> = listOf(
        WearableMetric.STEPS,
        WearableMetric.RESTING_HEART_RATE,
        WearableMetric.HEART_RATE_AVERAGE,
        WearableMetric.HEART_RATE_SAMPLE,
    )

    fun select(
        windowStartAt: Long,
        windowEndAt: Long,
        observations: List<WearableObservation>,
        byId: Map<String, WearableObservation> = observations.associateBy { it.id },
    ): List<WearableObservation> {
        if (windowStartAt >= windowEndAt) return emptyList()
        val window = TemporalWindowProjector.explicit(
            startInclusiveAt = windowStartAt,
            endExclusiveAt = windowEndAt,
            id = "interval:$windowStartAt:$windowEndAt",
        )
        val placed = TemporalWindowProjector.projectWearables(observations, window)
        val included = (placed.pointObservationIds + placed.containedObservationIds)
            .mapNotNull { byId[it] }
            .filter { it.metric in METRICS }
        return METRICS.flatMap { metric ->
            choose(included.filter { it.metric == metric }, windowStartAt, windowEndAt)
        }
    }

    private fun choose(
        rows: List<WearableObservation>,
        start: Long,
        end: Long,
    ): List<WearableObservation> {
        val points = rows.filter { it.temporalKind == WearableTemporalKind.POINT }
        val spans = rows.filter { it.temporalKind != WearableTemporalKind.POINT }
        val exact = spans.filter { it.observedStartAt == start && it.observedEndAt == end }
        val aggregates = if (exact.isEmpty()) spans else exact
        val keptPoints = if (rows.firstOrNull()?.metric == WearableMetric.RESTING_HEART_RATE) {
            latestPoint(points)
        } else {
            points
        }
        return (aggregates + keptPoints).sortedWith(compareBy({ it.observedStartAt }, { it.id }))
    }

    private fun latestPoint(points: List<WearableObservation>): List<WearableObservation> {
        val latestAt = points.maxOfOrNull { it.observedStartAt } ?: return emptyList()
        val chosen = points
            .filter { it.observedStartAt == latestAt }
            .maxWithOrNull(
                compareBy<WearableObservation> { if (it.sourceRecordId.isNullOrBlank()) 0 else 1 }
                    .thenBy { it.importedAt }
                    .thenBy { it.id },
            )
        return listOfNotNull(chosen)
    }
}
