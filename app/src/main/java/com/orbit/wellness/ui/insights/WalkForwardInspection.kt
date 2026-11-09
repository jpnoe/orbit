package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.WalkForwardProjection
import com.orbit.wellness.domain.checkin.projectWalkForward
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/**
 * Projects one check-in snapshot into recorded walk-forward attempts.
 *
 * Equal snapshots are skipped. A newer snapshot cancels an older projection.
 * The work runs on [computation], off the main thread. Replay position and
 * sandbox edits are not inputs. The projection does not write history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeWalkForward(
    snapshots: Flow<CheckInAnalysisSnapshot>,
    computation: CoroutineDispatcher = Dispatchers.Default,
    project: suspend (CheckInAnalysisDatasetV2) -> WalkForwardProjection = { dataset ->
        projectWalkForward(dataset)
    },
): Flow<WalkForwardProjection> {
    return snapshots
        .distinctUntilChanged()
        .mapLatest { snapshot -> project(snapshot.dataset) }
        .flowOn(computation)
}
