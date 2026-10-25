package com.orbit.wellness.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.JournalRepository
import com.orbit.wellness.data.local.DailyJournal
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class JournalUiState(
    val note: String = "",
    val entries: List<DailyJournal> = emptyList(),
    val isEditing: Boolean = false,
    val editingDate: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val entryPendingDelete: DailyJournal? = null,
) {
    val todayDate: String
        get() = DateUtils.formatLocalDate(LocalDate.now())

    val todayEntry: DailyJournal?
        get() = entries.firstOrNull { it.localDate == todayDate }

    /** Show the compose form when today has no entry, or while editing. */
    val showComposer: Boolean
        get() = isEditing || todayEntry == null
}

class JournalViewModel(
    private val repository: JournalRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JournalUiState())
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    private val _savedFeedback = Channel<Unit>(Channel.BUFFERED)
    val savedFeedback = _savedFeedback.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeAllDescending().collect { entries ->
                _uiState.update { state ->
                    val todayDate = DateUtils.formatLocalDate(LocalDate.now())
                    val todayEntry = entries.firstOrNull { it.localDate == todayDate }
                    val currentNote = if (!state.isEditing) todayEntry?.note.orEmpty() else state.note
                    state.copy(
                        entries = entries,
                        note = currentNote,
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun onNoteChanged(note: String) {
        _uiState.update { it.copy(note = note) }
    }

    fun startEdit(entry: DailyJournal) {
        _uiState.update {
            it.copy(
                isEditing = true,
                editingDate = entry.localDate,
                note = entry.note,
            )
        }
    }

    fun cancelEdit() {
        _uiState.update {
            it.copy(
                isEditing = false,
                editingDate = null,
                note = "",
            )
        }
    }

    fun saveJournal() {
        val current = _uiState.value
        if (current.isSaving) return
        val targetDate = current.editingDate ?: current.todayDate
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            repository.saveForDate(targetDate, current.note)
            val saved = current.note.trim().isNotEmpty()
            _uiState.update {
                it.copy(
                    isSaving = false,
                    isEditing = false,
                    editingDate = null,
                    note = "",
                )
            }
            if (saved) _savedFeedback.trySend(Unit)
        }
    }

    fun requestDelete(entry: DailyJournal) {
        _uiState.update { it.copy(entryPendingDelete = entry) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(entryPendingDelete = null) }
    }

    fun confirmDelete() {
        val entry = _uiState.value.entryPendingDelete ?: return
        viewModelScope.launch {
            repository.deleteByDate(entry.localDate)
            _uiState.update { state ->
                val clearingEdit = state.editingDate == entry.localDate
                state.copy(
                    entryPendingDelete = null,
                    isEditing = if (clearingEdit) false else state.isEditing,
                    editingDate = if (clearingEdit) null else state.editingDate,
                    note = if (clearingEdit) "" else state.note,
                )
            }
        }
    }

    class Factory(
        private val repository: JournalRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return JournalViewModel(repository) as T
        }
    }
}
