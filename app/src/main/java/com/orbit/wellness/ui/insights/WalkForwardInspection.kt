package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.data.local.SleepEvent
import com.orbit.wellness.data.local.SubstanceUseEvent
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.domain.checkin.BidirectionalEvidenceDataset
import com.orbit.wellness.domain.checkin.CausalPredecessorNeighborhoodDataset
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.ForecastExampleBundle
import com.orbit.wellness.domain.checkin.PersonalSemanticOccurrenceProjection
import com.orbit.wellness.domain.checkin.RetrospectiveSuccessorNeighborhoodDataset
import com.orbit.wellness.domain.checkin.projectCausalPredecessorNeighborhood
import com.orbit.wellness.domain.checkin.projectForecastExamples
import com.orbit.wellness.domain.checkin.ReciprocalSignatureDataset
import com.orbit.wellness.domain.checkin.projectAlternatingTemporalGraph
import com.orbit.wellness.domain.checkin.projectPersonalSemanticOccurrences
import com.orbit.wellness.domain.checkin.DistributionalContextDataset
import com.orbit.wellness.domain.checkin.JointEmbeddingDataset
import com.orbit.wellness.domain.checkin.conceptNodeId
import com.orbit.wellness.domain.checkin.projectDistributionalContext
import com.orbit.wellness.domain.checkin.projectJointSemanticEmbedding
import com.orbit.wellness.domain.checkin.projectReciprocalSignatures
import com.orbit.wellness.domain.checkin.retrospectiveVocabulary
import com.orbit.wellness.domain.checkin.StateShapeDiagnosticDataset
import com.orbit.wellness.domain.checkin.StateShapeEvidenceDataset
import com.orbit.wellness.domain.checkin.projectRetrospectiveMultiscaleBidirectionalEvidence
import com.orbit.wellness.domain.checkin.projectRetrospectiveStateShapeDiagnostics
import com.orbit.wellness.domain.checkin.projectRetrospectiveStateShapeEvidence
import com.orbit.wellness.domain.checkin.projectRetrospectiveSuccessorNeighborhood
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.time.ZoneId

data class PredictionLabSnapshot(
    val examples: ForecastExampleBundle,
    val neighborhoods: CausalPredecessorNeighborhoodDataset,
    val successorNeighborhoods: RetrospectiveSuccessorNeighborhoodDataset,
    val bidirectionalEvidence: BidirectionalEvidenceDataset,
    val stateShapeDiagnostics: StateShapeDiagnosticDataset,
    val stateShapeEvidence: StateShapeEvidenceDataset,
    val occurrences: PersonalSemanticOccurrenceProjection,
    val signatures: ReciprocalSignatureDataset,
    val embedding: JointEmbeddingDataset,
    val distributional: DistributionalContextDataset,
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
    sleep: Flow<List<SleepEvent>> = flowOf(emptyList()),
    substances: Flow<List<SubstanceUseEvent>> = flowOf(emptyList()),
    wearables: Flow<List<WearableObservation>> = flowOf(emptyList()),
    computation: CoroutineDispatcher = Dispatchers.Default,
    zone: ZoneId = ZoneId.systemDefault(),
    project: suspend (CheckInAnalysisDatasetV2) -> ForecastExampleBundle = { dataset ->
        projectForecastExamples(dataset)
    },
): Flow<PredictionLabSnapshot> {
    return combine(snapshots, sleep, substances, wearables) { snapshot, sleepEvents, substanceEvents, wearableRows ->
        OccurrenceAuditInputs(snapshot, sleepEvents, substanceEvents, wearableRows)
    }
        .distinctUntilChanged()
        .mapLatest { input ->
            val snapshot = input.snapshot
            val occurrences = projectPersonalSemanticOccurrences(
                dataset = snapshot.dataset,
                sleep = input.sleep,
                substances = input.substances,
                wearables = input.wearables,
            )
            val graph = projectAlternatingTemporalGraph(snapshot.dataset)
            val signatures = projectReciprocalSignatures(
                projection = occurrences,
                graph = graph,
                vocabulary = retrospectiveVocabulary(occurrences),
            )
            val embedding = projectJointSemanticEmbedding(signatures, graph, occurrences)
            val evidence = projectRetrospectiveMultiscaleBidirectionalEvidence(snapshot.dataset, zone)
            val diagnostics = projectRetrospectiveStateShapeDiagnostics(snapshot.dataset, evidence)
            PredictionLabSnapshot(
                examples = project(snapshot.dataset),
                neighborhoods = projectCausalPredecessorNeighborhood(snapshot.dataset, zone),
                successorNeighborhoods = projectRetrospectiveSuccessorNeighborhood(snapshot.dataset),
                bidirectionalEvidence = evidence,
                stateShapeDiagnostics = diagnostics,
                stateShapeEvidence = projectRetrospectiveStateShapeEvidence(diagnostics),
                occurrences = occurrences,
                signatures = signatures,
                embedding = embedding,
                distributional = projectDistributionalContext(
                    embedding = embedding,
                    supportByConcept = signatures.vocabulary.concepts.associate { concept ->
                        conceptNodeId(concept.key) to concept.backboneSupport
                    },
                ),
            )
        }
        .flowOn(computation)
}

private data class OccurrenceAuditInputs(
    val snapshot: CheckInAnalysisSnapshot,
    val sleep: List<SleepEvent>,
    val substances: List<SubstanceUseEvent>,
    val wearables: List<WearableObservation>,
)
