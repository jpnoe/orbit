package com.orbit.wellness.ui.use

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CurrentMoodStore
import com.orbit.wellness.data.SubstanceRepository
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceOption
import com.orbit.wellness.data.local.SubstanceUseEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class UseUiState(
    val intent: SubstanceIntent = SubstanceIntent.SUPPORTIVE,
    val name: String = "",
    val amount: String = "",
    val moodScore: Float = 5f,
    val allOptions: List<SubstanceOption> = emptyList(),
    val recentEvents: List<SubstanceUseEvent> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val loggedAtMillis: Long = System.currentTimeMillis(),
) {
    val options: List<SubstanceOption>
        get() = allOptions.filter { it.intent == intent.name }
}

class UseViewModel(
    private val repository: SubstanceRepository,
    private val currentMoodStore: CurrentMoodStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        UseUiState(moodScore = currentMoodStore.committedMood.value),
    )
    val uiState: StateFlow<UseUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _savedFeedback = Channel<Unit>(Channel.BUFFERED)
    val savedFeedback = _savedFeedback.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeOptions().collect { options ->
                _uiState.update { it.copy(allOptions = options, isLoading = false) }
            }
        }
        viewModelScope.launch {
            repository.observeEvents().collect { events ->
                _uiState.update { it.copy(recentEvents = events.take(8), isLoading = false) }
            }
        }
    }

    fun syncFromCommitted() {
        _uiState.update { it.copy(moodScore = currentMoodStore.committedMood.value) }
    }

    fun onIntentSelected(intent: SubstanceIntent) {
        _uiState.update {
            it.copy(
                intent = intent,
                name = "",
                amount = "",
            )
        }
    }

    fun applyOption(option: SubstanceOption) {
        _uiState.update {
            it.copy(
                name = option.name,
                amount = option.defaultAmount,
                intent = runCatching { SubstanceIntent.valueOf(option.intent) }
                    .getOrDefault(it.intent),
            )
        }
    }

    fun onNameChanged(value: String) {
        _uiState.update { it.copy(name = value) }
    }

    fun onAmountChanged(value: String) {
        _uiState.update { it.copy(amount = value) }
    }

    fun onMoodChanged(value: Float) {
        _uiState.update { it.copy(moodScore = value.coerceIn(0f, 10f)) }
    }

    fun onLoggedAtChanged(millis: Long) {
        _uiState.update { it.copy(loggedAtMillis = millis) }
    }

    fun saveUse() {
        val current = _uiState.value
        val name = current.name.trim()
        if (name.isEmpty()) {
            _messages.trySend("Enter a name.")
            return
        }
        if (current.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val score = current.moodScore.roundToInt()
            val recordedAt = current.loggedAtMillis
            repository.logUse(
                name = name,
                amount = current.amount,
                moodScore = score,
                intent = current.intent,
                timestamp = recordedAt,
            )
            currentMoodStore.commit(score.toFloat(), recordedAt)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    name = "",
                    amount = "",
                    moodScore = score.toFloat(),
                    loggedAtMillis = System.currentTimeMillis(),
                )
            }
            _savedFeedback.trySend(Unit)
        }
    }

    fun updateUse(
        event: SubstanceUseEvent,
        name: String,
        amount: String,
        moodScore: Int,
        intent: SubstanceIntent,
    ) {
        viewModelScope.launch {
            repository.updateUse(event, name, amount, moodScore, intent)
        }
    }

    class Factory(
        private val repository: SubstanceRepository,
        private val currentMoodStore: CurrentMoodStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return UseViewModel(repository, currentMoodStore) as T
        }
    }
}
