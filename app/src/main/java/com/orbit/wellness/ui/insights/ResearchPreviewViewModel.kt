package com.orbit.wellness.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInAnalysisRepository
import com.orbit.wellness.data.WearableObservationRepository
import com.orbit.wellness.domain.checkin.StateOccurrenceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface ResearchPreviewUiState {
    data object Loading : ResearchPreviewUiState

    data class Ready(
        val stateMap: StateMapContent,
        val selectedKey: StateOccurrenceKey?,
    ) : ResearchPreviewUiState
}

/**
 * Debug-only subscriber for the retrospective state map.
 * [selectedKey] is combined after the fitted presentation, so choosing a point
 * does not project the snapshot again. This view model is not used by Insights.
 */
class ResearchPreviewViewModel(
    analysis: CheckInAnalysisRepository,
    wearableObservations: WearableObservationRepository,
) : ViewModel() {
    private val selectedKey = MutableStateFlow<StateOccurrenceKey?>(null)

    private val presentation: StateFlow<InsightsPresentation?> = observeInsightsAnalysis(
        snapshots = analysis.observeSnapshot(),
        wearables = wearableObservations.observe(),
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val uiState: StateFlow<ResearchPreviewUiState> = combine(presentation, selectedKey) { ready, selected ->
        if (ready == null) {
            ResearchPreviewUiState.Loading
        } else {
            ResearchPreviewUiState.Ready(
                stateMap = ready.stateMap,
                selectedKey = selected,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ResearchPreviewUiState.Loading,
    )

    fun selectState(key: StateOccurrenceKey) {
        selectedKey.value = key
    }

    class Factory(
        private val repository: CheckInAnalysisRepository,
        private val wearableObservations: WearableObservationRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ResearchPreviewViewModel(repository, wearableObservations) as T
        }
    }
}
