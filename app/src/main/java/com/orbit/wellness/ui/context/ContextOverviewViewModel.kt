package com.orbit.wellness.ui.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class ContextOverviewUiState(
    val loaded: Boolean = false,
    val completing: Boolean = false,
    val overview: ContextOverviewModel = ContextOverviewModel(),
)

class ContextOverviewViewModel(
    private val checkInId: String,
    private val checkInRepository: CheckInRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContextOverviewUiState())
    val uiState: StateFlow<ContextOverviewUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _finished = Channel<Unit>(Channel.BUFFERED)
    val finished = _finished.receiveAsFlow()

    /**
     * Explicit Done. Sets [com.orbit.wellness.data.local.CheckInContextWindow.completedAt] once.
     * A missing window leaves without writing. Back never calls this.
     */
    fun finish() {
        val state = _uiState.value
        if (!state.loaded || state.completing) return
        if (state.overview.missing) {
            _finished.trySend(Unit)
            return
        }
        _uiState.update { it.copy(completing = true) }
        viewModelScope.launch {
            try {
                checkInRepository.completeContext(checkInId)
                _finished.trySend(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(completing = false) }
                _messages.trySend(error.message ?: "Could not finish this check-in.")
            }
        }
    }

    init {
        if (checkInId.isBlank()) {
            _uiState.value = ContextOverviewUiState(
                loaded = true,
                overview = ContextOverviewModel(missing = true, title = ContextOverviewCopy.MISSING),
            )
        } else {
            viewModelScope.launch {
                combine(
                    checkInRepository.observeContextWindow(checkInId),
                    checkInRepository.observeContextCategories(checkInId),
                    checkInRepository.observeActivityItems(checkInId),
                    checkInRepository.observeWorkoutItems(checkInId),
                ) { window, categories, activityItems, workoutItems ->
                    ContextOverviewCopy.present(window, categories, activityItems, workoutItems)
                }.collect { overview ->
                    _uiState.update { it.copy(loaded = true, overview = overview) }
                }
            }
        }
    }

    class Factory(
        private val checkInId: String,
        private val checkInRepository: CheckInRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContextOverviewViewModel(checkInId, checkInRepository) as T
        }
    }
}
