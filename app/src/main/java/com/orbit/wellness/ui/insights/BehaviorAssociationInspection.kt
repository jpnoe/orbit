package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.domain.checkin.BehaviorAssociationDataset
import com.orbit.wellness.domain.checkin.BehaviorContextBalanceDataset
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.BehaviorExposureEpisodeDataset
import com.orbit.wellness.domain.checkin.projectAlternatingTemporalGraph
import com.orbit.wellness.domain.checkin.projectBehaviorAssociations
import com.orbit.wellness.domain.checkin.projectBehaviorContextBalance
import com.orbit.wellness.domain.checkin.projectBehaviorExposureEpisodes
import com.orbit.wellness.domain.checkin.projectBehavioralEvidence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.time.ZoneId

data class BehaviorResearchProjection(
    val associations: BehaviorAssociationDataset,
    val balance: BehaviorContextBalanceDataset,
    val episodes: BehaviorExposureEpisodeDataset,
)

/**
 * Projects one graph and one evidence dataset, then associations, context
 * balance, and exposure episodes. Later stages do not build the evidence again.
 */
fun projectBehaviorResearch(
    dataset: CheckInAnalysisDatasetV2,
    zone: ZoneId,
): BehaviorResearchProjection {
    val graph = projectAlternatingTemporalGraph(dataset)
    val evidence = projectBehavioralEvidence(graph, dataset, zone)
    val associations = projectBehaviorAssociations(evidence)
    return BehaviorResearchProjection(
        associations = associations,
        balance = projectBehaviorContextBalance(evidence, associations, zone),
        episodes = projectBehaviorExposureEpisodes(evidence, graph, associations),
    )
}

/**
 * Projects one check-in snapshot into association profiles, context balance,
 * and exposure episodes.
 *
 * Equal snapshots are skipped. A newer snapshot cancels an older projection.
 * The work runs on [computation], off the main thread. Selection is not an
 * input. Wearables and the latent map are not inputs. [zone] is the calendar
 * used for day association and clock time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeBehaviorAssociations(
    snapshots: Flow<CheckInAnalysisSnapshot>,
    zone: ZoneId,
    computation: CoroutineDispatcher = Dispatchers.Default,
    project: suspend (CheckInAnalysisDatasetV2, ZoneId) -> BehaviorResearchProjection = { dataset, zoneId ->
        projectBehaviorResearch(dataset, zoneId)
    },
): Flow<BehaviorAssociationContent> {
    return snapshots
        .distinctUntilChanged()
        .mapLatest { snapshot ->
            val research = project(snapshot.dataset, zone)
            research.associations.toBehaviorAssociationContent(research.balance, research.episodes)
        }
        .flowOn(computation)
}
