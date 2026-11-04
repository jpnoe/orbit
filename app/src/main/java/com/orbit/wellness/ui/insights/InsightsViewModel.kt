package com.orbit.wellness.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInAnalysisRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data class Ready(val content: InsightsContent) : InsightsUiState
}

class InsightsViewModel(
    repository: CheckInAnalysisRepository,
) : ViewModel() {
    val uiState: StateFlow<InsightsUiState> = repository.observeSnapshot()
        .map { InsightsUiState.Ready(it.toInsightsContent()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InsightsUiState.Loading,
        )

    class Factory(
        private val repository: CheckInAnalysisRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return InsightsViewModel(repository) as T
        }
    }
}
