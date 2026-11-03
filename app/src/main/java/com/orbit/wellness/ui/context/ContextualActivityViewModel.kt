package com.orbit.wellness.ui.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.ActivityRepository
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.local.ActivityOption
import com.orbit.wellness.data.local.CheckInContextWindow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ContextualActivityUiState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val title: String = "",
    val detail: String? = null,
    val options: List<ActivityOption> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val showAddDialog: Boolean = false,
    val newActivityName: String = "",
)

class ContextualActivityViewModel(
    private val checkInId: String,
    private val checkInRepository: CheckInRepository,
    private val activityRepository: ActivityRepository,
) : ViewModel() {

    private val selection = ContextualActivitySelection()
    private val _uiState = MutableStateFlow(ContextualActivityUiState())
    val uiState: StateFlow<ContextualActivityUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _finished = Channel<Unit>(Channel.BUFFERED)
    val finished = _finished.receiveAsFlow()

    init {
        if (checkInId.isBlank()) {
            _uiState.value = ContextualActivityUiState(loaded = true, missing = true)
        } else {
            viewModelScope.launch {
                combine(
                    checkInRepository.observeContextWindow(checkInId),
                    checkInRepository.observeActivityItems(checkInId),
                    activityRepository.observeOptions(),
                ) { window, items, options ->
                    Triple(window, items, options)
                }.collect { (window, items, options) ->
                    selection.onSavedItems(items.map { it.activityOptionId })
                    selection.onOptions(options)
                    _uiState.update { state ->
                        state.copy(
                            loaded = true,
                            missing = window == null,
                            title = window?.let { headingTitle(it) }.orEmpty(),
                            detail = window?.let { headingDetail(it) },
                            options = options,
                            selectedIds = selection.selectedIds,
                        )
                    }
                }
            }
        }
    }

    fun toggle(optionId: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        selection.toggle(optionId)
        _uiState.update { it.copy(selectedIds = selection.selectedIds) }
    }

    fun openAddDialog() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(showAddDialog = true, newActivityName = "") }
    }

    fun dismissAddDialog() {
        _uiState.update { it.copy(showAddDialog = false, newActivityName = "") }
    }

    fun onNewActivityNameChanged(name: String) {
        _uiState.update { it.copy(newActivityName = name) }
    }

    fun confirmAddActivity() {
        val name = _uiState.value.newActivityName.trim()
        if (name.isEmpty() || _uiState.value.isSaving) return
        viewModelScope.launch {
            activityRepository.addCustomActivity(name)
                .onSuccess {
                    selection.selectAddedName(name)
                    _uiState.update {
                        it.copy(
                            showAddDialog = false,
                            newActivityName = "",
                            selectedIds = selection.selectedIds,
                        )
                    }
                }
                .onFailure { error ->
                    _messages.trySend(error.message ?: "Could not add activity.")
                }
        }
    }

    fun saveActivities() {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        if (selection.snapshots().isEmpty()) return
        commit(selection.commitForSave())
    }

    fun saveNone() {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        commit(selection.commitForNone())
    }

    private fun commit(commit: ActivityReviewCommit) {
        _uiState.update { it.copy(isSaving = true, selectedIds = selection.selectedIds) }
        viewModelScope.launch {
            try {
                commitActivityReview(checkInRepository, checkInId, commit)
                _uiState.update { it.copy(isSaving = false) }
                _finished.trySend(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isSaving = false, selectedIds = selection.selectedIds) }
                _messages.trySend(error.message ?: "Could not save activities.")
            }
        }
    }

    private fun headingTitle(window: CheckInContextWindow): String {
        return ContextOverviewCopy.heading(window.startBasis, window.windowStartAt, java.time.ZoneId.systemDefault()).first
    }

    private fun headingDetail(window: CheckInContextWindow): String? {
        return ContextOverviewCopy.heading(window.startBasis, window.windowStartAt, java.time.ZoneId.systemDefault()).second
    }

    class Factory(
        private val checkInId: String,
        private val checkInRepository: CheckInRepository,
        private val activityRepository: ActivityRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContextualActivityViewModel(
                checkInId,
                checkInRepository,
                activityRepository,
            ) as T
        }
    }
}
