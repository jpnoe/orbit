package com.orbit.wellness.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInAnalysisRepository
import com.orbit.wellness.data.WearableCoverage
import com.orbit.wellness.data.WearableObservationRepository
import com.orbit.wellness.data.wearableCoverage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data class Ready(
        val content: InsightsContent,
        val wearable: WearableCoverage,
    ) : InsightsUiState
}

class InsightsViewModel(
    analysis: CheckInAnalysisRepository,
    wearableObservations: WearableObservationRepository,
) : ViewModel() {
    val uiState: StateFlow<InsightsUiState> = combine(
        analysis.observeSnapshot(),
        wearableObservations.observe(),
    ) { snapshot, observations ->
        InsightsUiState.Ready(
            content = snapshot.toInsightsContent(),
            wearable = wearableCoverage(observations),
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
