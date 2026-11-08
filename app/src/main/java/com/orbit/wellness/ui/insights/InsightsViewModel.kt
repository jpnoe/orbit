package com.orbit.wellness.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInAnalysisRepository
import com.orbit.wellness.data.WearableObservationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data class Ready(
        val baseline: InsightsBaseline,
    ) : InsightsUiState
}

class InsightsViewModel(
    analysis: CheckInAnalysisRepository,
    wearableObservations: WearableObservationRepository,
) : ViewModel() {
    val uiState: StateFlow<InsightsUiState> = combine(
        analysis.observeSnapshot(),
        wearableObservations.observeCoverage(),
    ) { snapshot, coverage ->
        InsightsUiState.Ready(
            baseline = snapshot.toInsightsBaseline(
                hasWearableObservations = coverage.hasObservations,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InsightsUiState.Loading,
    )

    class Factory(
        private val repository: CheckInAnalysisRepository,
        private val wearableObservations: WearableObservationRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return InsightsViewModel(repository, wearableObservations) as T
        }
    }
}
