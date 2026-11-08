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

sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data class Ready(
        val baseline: InsightsBaseline,
        val stateMap: StateMapContent,
        val selectedKey: StateOccurrenceKey?,
    ) : InsightsUiState
}

class InsightsViewModel(
    analysis: CheckInAnalysisRepository,
    wearableObservations: WearableObservationRepository,
) : ViewModel() {
    private val selectedKey = MutableStateFlow<StateOccurrenceKey?>(null)

    /**
     * The fit is cached on this flow. [selectedKey] is combined afterwards,
     * so choosing a point does not project the snapshot again.
     */
    private val presentation: StateFlow<InsightsPresentation?> = observeInsightsAnalysis(
        snapshots = analysis.observeSnapshot(),
        wearables = wearableObservations.observe(),
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val uiState: StateFlow<InsightsUiState> = combine(presentation, selectedKey) { ready, selected ->
        if (ready == null) {
            InsightsUiState.Loading
        } else {
            InsightsUiState.Ready(
                baseline = ready.baseline,
                stateMap = ready.stateMap,
                selectedKey = selected,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InsightsUiState.Loading,
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
            return InsightsViewModel(repository, wearableObservations) as T
        }
    }
}
