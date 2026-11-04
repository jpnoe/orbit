package com.orbit.wellness.data

import com.orbit.wellness.data.local.RECENT_HEALTH_LIMIT
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableObservationDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Read-only view of imported wearable observations.
 * Coverage counts are derived in memory and are not written back to Room.
 */
class WearableObservationRepository(
    private val dao: WearableObservationDao,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Complete imported history for Insights and later analysis. */
    fun observe(): Flow<List<WearableObservation>> {
        return dao.observeAll()
            .distinctUntilChanged()
            .flowOn(computation)
    }

    /** Bounded list for the Health screen. Older stored rows stay in [observe]. */
    fun observeRecent(limit: Int = RECENT_HEALTH_LIMIT): Flow<List<WearableObservation>> {
        return dao.observeRecent(limit)
            .distinctUntilChanged()
            .flowOn(computation)
    }
}

data class WearableCoverage(
    val hasObservations: Boolean,
    val countByMetric: List<WearableMetricCount>,
    val earliestMeasurementAt: Long?,
    val latestMeasurementAt: Long?,
    val latestImportedAt: Long?,
)

data class WearableMetricCount(
    val metric: String,
    val count: Int,
)

fun wearableCoverage(observations: List<WearableObservation>): WearableCoverage {
    val known = WearableMetric.ALL.toList()
    val extra = observations.map { it.metric }.filter { it !in WearableMetric.ALL }.distinct()
    val counts = (known + extra).map { metric ->
        WearableMetricCount(metric, observations.count { it.metric == metric })
    }.filter { it.count > 0 }
    return WearableCoverage(
        hasObservations = observations.isNotEmpty(),
        countByMetric = counts,
        earliestMeasurementAt = observations.minOfOrNull { it.observedStartAt },
        latestMeasurementAt = observations.maxOfOrNull { it.observedEndAt },
        latestImportedAt = observations.maxOfOrNull { it.importedAt },
    )
}
