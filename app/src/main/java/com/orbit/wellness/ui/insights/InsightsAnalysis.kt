package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.StateMapAnalysis
import com.orbit.wellness.domain.checkin.projectStateMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

data class InsightsPresentation(
    val baseline: InsightsBaseline,
    val stateMap: StateMapContent,
)

data class InsightsAnalysisInput(
    val snapshot: CheckInAnalysisSnapshot,
    val wearables: List<WearableObservation>,
)

/**
 * Joins one check-in snapshot with the wearable rows observed alongside it.
 * Equal inputs are skipped. A newer input cancels a projection that is still
 * running, and the projection runs on [computation], off the main thread.
 * Selection is not an input, so choosing a point does not fit again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeInsightsAnalysis(
    snapshots: Flow<CheckInAnalysisSnapshot>,
    wearables: Flow<List<WearableObservation>>,
    computation: CoroutineDispatcher = Dispatchers.Default,
    project: suspend (CheckInAnalysisDatasetV2, List<WearableObservation>) -> StateMapAnalysis = ::projectStateMap,
): Flow<InsightsPresentation> {
    return combine(snapshots, wearables) { snapshot, rows ->
        InsightsAnalysisInput(
            snapshot = snapshot,
            wearables = rows.sortedWith(compareBy({ it.observedStartAt }, { it.id })),
        )
    }
        .distinctUntilChanged()
        .mapLatest { input ->
            val analysis = project(input.snapshot.dataset, input.wearables)
            InsightsPresentation(
                baseline = input.snapshot.toInsightsBaseline(input.wearables.isNotEmpty()),
                stateMap = analysis.toStateMapContent(),
            )
        }
        .flowOn(computation)
}
