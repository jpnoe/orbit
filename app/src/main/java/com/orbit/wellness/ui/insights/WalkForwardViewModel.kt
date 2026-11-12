package com.orbit.wellness.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInAnalysisRepository
import com.orbit.wellness.data.SleepRepository
import com.orbit.wellness.data.SubstanceRepository
import com.orbit.wellness.data.WearableObservationRepository
import com.orbit.wellness.domain.checkin.BidirectionalEvidenceDataset
import com.orbit.wellness.domain.checkin.CausalPredecessorNeighborhoodDataset
import com.orbit.wellness.domain.checkin.ForecastExampleBundle
import com.orbit.wellness.domain.checkin.RetrospectiveSuccessorNeighborhoodDataset
import com.orbit.wellness.domain.checkin.PersonalSemanticOccurrenceProjection
import com.orbit.wellness.domain.checkin.DistributionalContextDataset
import com.orbit.wellness.domain.checkin.JointEmbeddingDataset
import com.orbit.wellness.domain.checkin.MaskedConceptReconstructionDataset
import com.orbit.wellness.domain.checkin.ReciprocalSignatureDataset
import com.orbit.wellness.domain.checkin.StateShapeDiagnosticDataset
import com.orbit.wellness.domain.checkin.StateShapeEvidenceDataset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface WalkForwardUiState {
    data object Loading : WalkForwardUiState

    data class Ready(
        val projection: com.orbit.wellness.domain.checkin.WalkForwardProjection,
        val observationProcess: com.orbit.wellness.domain.checkin.ObservationProcessDataset,
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
        val reconstruction: MaskedConceptReconstructionDataset,
    ) : WalkForwardUiState
}

/**
 * Debug-only subscriber for recorded walk-forward attempts.
 *
 * Sandbox edits are not held here. They live in the sandbox pane and leave
 * with it. This view model is not used by Insights.
 */
class WalkForwardViewModel(
    analysis: CheckInAnalysisRepository,
    sleep: SleepRepository,
    substances: SubstanceRepository,
    wearables: WearableObservationRepository,
) : ViewModel() {
    val uiState: StateFlow<WalkForwardUiState> = observeWalkForward(
        snapshots = analysis.observeSnapshot(),
        sleep = sleep.observeAll(),
        substances = substances.observeEvents(),
        wearables = wearables.observe(),
    )
        .map<PredictionLabSnapshot, WalkForwardUiState> { snapshot ->
            WalkForwardUiState.Ready(
                projection = snapshot.walkForward,
                observationProcess = snapshot.observationProcess,
                examples = snapshot.examples,
                neighborhoods = snapshot.neighborhoods,
                successorNeighborhoods = snapshot.successorNeighborhoods,
                bidirectionalEvidence = snapshot.bidirectionalEvidence,
                stateShapeDiagnostics = snapshot.stateShapeDiagnostics,
                stateShapeEvidence = snapshot.stateShapeEvidence,
                occurrences = snapshot.occurrences,
                signatures = snapshot.signatures,
                embedding = snapshot.embedding,
                distributional = snapshot.distributional,
                reconstruction = snapshot.reconstruction,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WalkForwardUiState.Loading,
        )

    class Factory(
        private val repository: CheckInAnalysisRepository,
        private val sleep: SleepRepository,
        private val substances: SubstanceRepository,
        private val wearables: WearableObservationRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return WalkForwardViewModel(repository, sleep, substances, wearables) as T
        }
    }
}
