package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.domain.checkin.BehaviorAssociationDataset
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.projectBehaviorAssociations
import com.orbit.wellness.domain.checkin.projectBehavioralEvidence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.time.ZoneId

/**
 * Projects one check-in snapshot into association profiles.
 *
 * Equal snapshots are skipped. A newer snapshot cancels an older projection.
 * The work runs on [computation], off the main thread. Selection is not an
 * input. Wearables and the latent map are not inputs. [zone] is the calendar
 * used for day association.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeBehaviorAssociations(
    snapshots: Flow<CheckInAnalysisSnapshot>,
    zone: ZoneId,
    computation: CoroutineDispatcher = Dispatchers.Default,
    project: suspend (CheckInAnalysisDatasetV2, ZoneId) -> BehaviorAssociationDataset = { dataset, zoneId ->
        projectBehaviorAssociations(projectBehavioralEvidence(dataset, zoneId))
    },
): Flow<BehaviorAssociationContent> {
    return snapshots
        .distinctUntilChanged()
        .mapLatest { snapshot ->
            project(snapshot.dataset, zone).toBehaviorAssociationContent()
        }
        .flowOn(computation)
}
