package com.orbit.wellness.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInAnalysisRepository
import com.orbit.wellness.domain.checkin.CausalPredecessorNeighborhoodDataset
import com.orbit.wellness.domain.checkin.ForecastExampleBundle
import com.orbit.wellness.domain.checkin.RetrospectiveSuccessorNeighborhoodDataset
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
) : ViewModel() {
    val uiState: StateFlow<WalkForwardUiState> = observeWalkForward(analysis.observeSnapshot())
        .map<PredictionLabSnapshot, WalkForwardUiState> { snapshot ->
            WalkForwardUiState.Ready(
                projection = snapshot.walkForward,
                observationProcess = snapshot.observationProcess,
                examples = snapshot.examples,
                neighborhoods = snapshot.neighborhoods,
                successorNeighborhoods = snapshot.successorNeighborhoods,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WalkForwardUiState.Loading,
        )

    class Factory(
        private val repository: CheckInAnalysisRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return WalkForwardViewModel(repository) as T
        }
    }
}
