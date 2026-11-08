package com.orbit.wellness.data

import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind
import com.orbit.wellness.data.local.WearableUnit
import com.orbit.wellness.domain.checkin.TemporalWindowProjector

/**
 * Wearable rows a check-in history card can show.
 * Sleep stays off this card until a later metric is added here.
 * Values are not written onto the check-in.
 */
object CheckInIntervalWearables {
    val METRICS: List<String> = listOf(
        WearableMetric.STEPS,
        WearableMetric.RESTING_HEART_RATE,
        WearableMetric.HEART_RATE_AVERAGE,
        WearableMetric.HEART_RATE_SAMPLE,
    )

    fun forInterval(
        windowStartAt: Long?,
        windowEndAt: Long,
        observations: List<WearableObservation>,
    ): List<WearableObservation> {
        val start = windowStartAt ?: return emptyList()
        if (start >= windowEndAt) return emptyList()
        val window = TemporalWindowProjector.explicit(start, windowEndAt, id = "history:$start:$windowEndAt")
        val placed = TemporalWindowProjector.projectWearables(observations, window)
        val byId = observations.associateBy { it.id }
        val included = (placed.pointObservationIds + placed.containedObservationIds)
            .mapNotNull { byId[it] }
            .filter { it.metric in METRICS }
        return METRICS.flatMap { metric ->
            choose(included.filter { it.metric == metric }, start, windowEndAt)
        }
    }

    /**
     * An aggregate whose stored bounds are the check-in window is the interval
     * capture. It replaces other contained rows of that same metric, such as a
     * shorter steps record. Resting heart rate is one daily point, so a window
     * that contains several keeps the latest. A wider aggregate is already
     * omitted: it overlaps instead of being contained.
     */
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

/** Count stays a number. Other units stay visible, such as "62 bpm". */
fun historyWearableAmount(observation: WearableObservation): String {
    val number = if (observation.value % 1.0 == 0.0) {
        observation.value.toLong().toString()
    } else {
        observation.value.toString()
    }
    return if (observation.unit == WearableUnit.COUNT) number else "$number ${observation.unit}"
}
