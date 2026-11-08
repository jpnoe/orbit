package com.orbit.wellness.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInAnalysisRepository
import com.orbit.wellness.domain.checkin.BehaviorIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

sealed interface BehaviorAssociationUiState {
    data object Loading : BehaviorAssociationUiState

    data class Ready(
        val content: BehaviorAssociationContent,
        val selectedIdentity: BehaviorIdentity?,
    ) : BehaviorAssociationUiState
}

/**
 * Debug-only subscriber for behavior-association profiles.
 *
 * [selectedIdentity] is combined after the projected content, so choosing a
 * behavior does not project the snapshot again. This view model is not used
 * by Insights or by the state-map pane.
 */
class BehaviorAssociationViewModel(
    analysis: CheckInAnalysisRepository,
    zone: ZoneId,
) : ViewModel() {
    private val selectedIdentity = MutableStateFlow<BehaviorIdentity?>(null)

    private val presentation: StateFlow<BehaviorAssociationContent?> = observeBehaviorAssociations(
        snapshots = analysis.observeSnapshot(),
        zone = zone,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val uiState: StateFlow<BehaviorAssociationUiState> = combine(presentation, selectedIdentity) { ready, selected ->
        if (ready == null) {
            BehaviorAssociationUiState.Loading
        } else {
            BehaviorAssociationUiState.Ready(
                content = ready,
                selectedIdentity = retainedBehaviorSelection(ready.profiles, selected),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BehaviorAssociationUiState.Loading,
    )

    fun selectBehavior(identity: BehaviorIdentity) {
        selectedIdentity.value = identity
    }

    class Factory(
        private val repository: CheckInAnalysisRepository,
        private val zone: ZoneId,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BehaviorAssociationViewModel(repository, zone) as T
        }
    }
}
