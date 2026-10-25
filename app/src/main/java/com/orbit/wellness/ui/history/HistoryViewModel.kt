package com.orbit.wellness.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.HistoryItem
import com.orbit.wellness.data.HistoryRepository
import com.orbit.wellness.data.JournalRepository
import com.orbit.wellness.data.local.ActivityEvent
import com.orbit.wellness.data.local.MealEvent
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.data.local.SleepEvent
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceUseEvent
import com.orbit.wellness.data.local.WorkoutEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryFeedState(
    val items: List<HistoryItem> = emptyList(),
    val isLoading: Boolean = true,
)

data class HistoryUiState(
    val journalNote: String = "",
    val isJournalLoading: Boolean = true,
    val isJournalSaving: Boolean = false,
    val selectedItemForOptions: HistoryItem? = null,
    val itemToDelete: HistoryItem? = null,
    val itemToEdit: HistoryItem? = null,
)

class HistoryViewModel(
    private val historyRepository: HistoryRepository,
    private val journalRepository: JournalRepository,
) : ViewModel() {

    val feed: StateFlow<HistoryFeedState> = historyRepository.observeRecentHistory()
        .map { HistoryFeedState(items = it, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryFeedState(),
        )

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val _savedFeedback = Channel<Unit>(Channel.BUFFERED)
    val savedFeedback = _savedFeedback.receiveAsFlow()

    init {
        viewModelScope.launch {
            val existing = journalRepository.getToday()
            _uiState.update {
                it.copy(
                    journalNote = existing?.note.orEmpty(),
                    isJournalLoading = false,
                )
            }
        }
    }

    fun onJournalChanged(note: String) {
        _uiState.update { it.copy(journalNote = note) }
    }

    fun saveJournal() {
        val current = _uiState.value
        if (current.isJournalSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isJournalSaving = true) }
            journalRepository.saveToday(current.journalNote)
            _uiState.update { it.copy(isJournalSaving = false) }
            _savedFeedback.trySend(Unit)
        }
    }

    fun onSelectEdit(item: HistoryItem) {
        _uiState.update { it.copy(itemToEdit = item, selectedItemForOptions = null) }
    }

    fun onLongPressItem(item: HistoryItem) {
        _uiState.update { it.copy(selectedItemForOptions = item) }
    }

    fun dismissOptions() {
        _uiState.update { it.copy(selectedItemForOptions = null) }
    }

    fun onSelectDeleteFromOptions() {
        val selected = _uiState.value.selectedItemForOptions
        _uiState.update {
            it.copy(
                selectedItemForOptions = null,
                itemToDelete = selected,
            )
        }
    }

    fun confirmDelete() {
        val item = _uiState.value.itemToDelete ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(itemToDelete = null) }
            historyRepository.deleteItem(item)
        }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(itemToDelete = null) }
    }

    fun onSelectEditFromOptions() {
        val selected = _uiState.value.selectedItemForOptions
        _uiState.update {
            it.copy(
                selectedItemForOptions = null,
                itemToEdit = selected,
            )
        }
    }

    fun dismissEditDialog() {
        _uiState.update { it.copy(itemToEdit = null) }
    }

    fun updateMood(sample: MoodSample, newScore: Int, newNote: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(itemToEdit = null) }
            historyRepository.updateMood(sample, newScore, newNote)
        }
    }

    fun updateActivity(event: ActivityEvent, newPreMood: Int?, newPostMood: Int?) {
        viewModelScope.launch {
            _uiState.update { it.copy(itemToEdit = null) }
            historyRepository.updateActivity(event, newPreMood, newPostMood)
        }
    }

    fun updateMeal(event: MealEvent, newLabel: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(itemToEdit = null) }
            historyRepository.updateMeal(event, newLabel)
        }
    }

    fun updateWorkout(event: WorkoutEvent, newDurationMinutes: Int, newNotes: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(itemToEdit = null) }
            historyRepository.updateWorkout(event, newDurationMinutes, newNotes)
        }
    }

    fun updateSubstanceUse(
        event: SubstanceUseEvent,
        name: String,
        amount: String,
        moodScore: Int,
        intent: SubstanceIntent,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(itemToEdit = null) }
            historyRepository.updateSubstanceUse(
                event = event,
                name = name,
                amount = amount,
                moodScore = moodScore,
                intent = intent.name,
            )
        }
    }

    fun updateSleep(
        event: SleepEvent,
        bedtime: Long,
        wakeTime: Long,
        sleepQuality: Int,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(itemToEdit = null) }
            historyRepository.updateSleep(event, bedtime, wakeTime, sleepQuality)
        }
    }

    class Factory(
        private val historyRepository: HistoryRepository,
        private val journalRepository: JournalRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(historyRepository, journalRepository) as T
        }
    }
}
