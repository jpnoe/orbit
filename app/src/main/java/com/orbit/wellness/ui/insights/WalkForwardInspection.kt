package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.domain.checkin.CausalPredecessorNeighborhoodDataset
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.ForecastExampleBundle
import com.orbit.wellness.domain.checkin.RetrospectiveSuccessorNeighborhoodDataset
import com.orbit.wellness.domain.checkin.projectCausalPredecessorNeighborhood
import com.orbit.wellness.domain.checkin.projectForecastExamples
import com.orbit.wellness.domain.checkin.projectRetrospectiveSuccessorNeighborhood
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.time.ZoneId

data class PredictionLabSnapshot(
    val examples: ForecastExampleBundle,
    val neighborhoods: CausalPredecessorNeighborhoodDataset,
    val successorNeighborhoods: RetrospectiveSuccessorNeighborhoodDataset,
) {
    val walkForward get() = examples.walkForward
    val observationProcess get() = examples.observationProcess
}

/**
 * Projects one check-in snapshot into recorded walk-forward attempts and the
 * separate observation-process contexts.
 *
 * Equal snapshots are skipped. A newer snapshot cancels an older projection.
 * The work runs on [computation], off the main thread. Replay position and
 * sandbox edits are not inputs. The projection does not write history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeWalkForward(
    snapshots: Flow<CheckInAnalysisSnapshot>,
    computation: CoroutineDispatcher = Dispatchers.Default,
    zone: ZoneId = ZoneId.systemDefault(),
    project: suspend (CheckInAnalysisDatasetV2) -> ForecastExampleBundle = { dataset ->
        projectForecastExamples(dataset)
    },
): Flow<PredictionLabSnapshot> {
    return snapshots
        .distinctUntilChanged()
        .mapLatest { snapshot ->
            PredictionLabSnapshot(
                examples = project(snapshot.dataset),
                neighborhoods = projectCausalPredecessorNeighborhood(snapshot.dataset, zone),
                successorNeighborhoods = projectRetrospectiveSuccessorNeighborhood(snapshot.dataset),
            )
        }
        .flowOn(computation)
}
