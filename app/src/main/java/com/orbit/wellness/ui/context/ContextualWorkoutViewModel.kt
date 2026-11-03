package com.orbit.wellness.ui.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.ExerciseRepository
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.WorkoutOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContextualWorkoutUiState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val title: String = "",
    val detail: String? = null,
    val options: List<WorkoutOption> = emptyList(),
    val entries: List<WorkoutDraftEntry> = emptyList(),
    val selectedOptionId: String? = null,
    val presetMinutes: Int? = null,
    val customDurationText: String = "",
    val editingId: String? = null,
    val resolvedDuration: Int? = null,
    val isSaving: Boolean = false,
    val showAddDialog: Boolean = false,
    val newWorkoutName: String = "",
)

class ContextualWorkoutViewModel(
    private val checkInId: String,
    private val checkInRepository: CheckInRepository,
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    private val draft = ContextualWorkoutDraft()
    private val _uiState = MutableStateFlow(ContextualWorkoutUiState())
    val uiState: StateFlow<ContextualWorkoutUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _finished = Channel<Unit>(Channel.BUFFERED)
    val finished = _finished.receiveAsFlow()

    init {
        if (checkInId.isBlank()) {
            _uiState.value = ContextualWorkoutUiState(loaded = true, missing = true)
        } else {
            viewModelScope.launch {
                combine(
                    checkInRepository.observeContextWindow(checkInId),
                    checkInRepository.observeWorkoutItems(checkInId),
                    exerciseRepository.observeOptions(),
                ) { window, items, options ->
                    Triple(window, items, options)
                }.collect { (window, items, options) ->
                    draft.onSavedItems(items)
                    draft.onOptions(options)
                    publish(window, options)
                }
            }
        }
    }

    fun selectOption(optionId: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        draft.selectOption(optionId)
        publishComposer()
    }

    fun selectPreset(minutes: Int) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        draft.selectPreset(minutes)
        publishComposer()
    }

    fun onCustomDurationChanged(text: String) {
        if (_uiState.value.isSaving) return
        draft.onCustomDuration(text)
        publishComposer()
    }

    fun addOrUpdate() {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        val error = draft.addOrUpdate()
        if (error != null) {
            _messages.trySend(error)
        }
        publishComposer()
    }

    fun beginEdit(entryId: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        draft.beginEdit(entryId)
        publishComposer()
    }

    fun remove(entryId: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        draft.remove(entryId)
        publishComposer()
    }

    fun openAddDialog() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(showAddDialog = true, newWorkoutName = "") }
    }

    fun dismissAddDialog() {
        _uiState.update { it.copy(showAddDialog = false, newWorkoutName = "") }
    }

    fun onNewWorkoutNameChanged(name: String) {
        _uiState.update { it.copy(newWorkoutName = name) }
    }

    fun confirmAddWorkoutType() {
        val name = _uiState.value.newWorkoutName.trim()
        if (name.isEmpty() || _uiState.value.isSaving) return
        viewModelScope.launch {
            exerciseRepository.addCustomWorkoutType(name)
                .onSuccess {
                    draft.selectAddedName(name)
                    _uiState.update {
                        it.copy(
                            showAddDialog = false,
                            newWorkoutName = "",
                            selectedOptionId = draft.selectedOptionId,
                        )
                    }
                }
                .onFailure { error ->
                    _messages.trySend(error.message ?: "Could not add workout type.")
                }
        }
    }

    fun saveWorkouts() {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        if (draft.snapshots().isEmpty()) return
        commit(draft.commitForSave())
    }

    fun saveNone() {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        commit(draft.commitForNone())
    }

    private fun commit(commit: WorkoutReviewCommit) {
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                commitWorkoutReview(checkInRepository, checkInId, commit)
                _uiState.update { it.copy(isSaving = false) }
                _finished.trySend(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishComposer(isSaving = false)
                _messages.trySend(error.message ?: "Could not save workouts.")
            }
        }
    }

    private fun publish(window: CheckInContextWindow?, options: List<WorkoutOption>) {
        _uiState.update { state ->
            state.copy(
                loaded = true,
                missing = window == null,
                title = window?.let { headingTitle(it) }.orEmpty(),
                detail = window?.let { headingDetail(it) },
                options = options,
                entries = draft.entries,
                selectedOptionId = draft.selectedOptionId,
                presetMinutes = draft.presetMinutes,
                customDurationText = draft.customDurationText,
                editingId = draft.editingId,
                resolvedDuration = draft.resolvedDuration,
            )
        }
    }

    private fun publishComposer(isSaving: Boolean = _uiState.value.isSaving) {
        _uiState.update { state ->
            state.copy(
                entries = draft.entries,
                selectedOptionId = draft.selectedOptionId,
                presetMinutes = draft.presetMinutes,
                customDurationText = draft.customDurationText,
                editingId = draft.editingId,
                resolvedDuration = draft.resolvedDuration,
                isSaving = isSaving,
            )
        }
    }

    private fun headingTitle(window: CheckInContextWindow): String {
        return ContextOverviewCopy.heading(
            window.startBasis,
            window.windowStartAt,
            java.time.ZoneId.systemDefault(),
        ).first
    }

    private fun headingDetail(window: CheckInContextWindow): String? {
        return ContextOverviewCopy.heading(
            window.startBasis,
            window.windowStartAt,
            java.time.ZoneId.systemDefault(),
        ).second
    }

    class Factory(
        private val checkInId: String,
        private val checkInRepository: CheckInRepository,
        private val exerciseRepository: ExerciseRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContextualWorkoutViewModel(
                checkInId,
                checkInRepository,
                exerciseRepository,
            ) as T
        }
    }
}
