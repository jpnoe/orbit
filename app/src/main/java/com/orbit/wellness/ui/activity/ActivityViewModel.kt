package com.orbit.wellness.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.ActivityRepository
import com.orbit.wellness.data.CurrentMoodStore
import com.orbit.wellness.data.local.ActivityEvent
import com.orbit.wellness.data.local.ActivityOption
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.round

enum class ActivityLogMode {
    Full,
    Session,
}

enum class ActivityMoodPrompt {
    Pre,
    Post,
}

data class ActivityUiState(
    val logMode: ActivityLogMode = ActivityLogMode.Session,
    val activityOptions: List<ActivityOption> = emptyList(),
    val activeActivities: List<ActivityEvent> = emptyList(),
    val recentActivities: List<ActivityEvent> = emptyList(),
    val selectedName: String = "",
    val moodScore: Float = 5f,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val moodPrompt: ActivityMoodPrompt? = null,
    val pendingActivityName: String? = null,
    val pendingActivityId: String? = null,
    val promptMoodScore: Float = 5f,
    val showAddActivityDialog: Boolean = false,
    val newActivityName: String = "",
    val loggedAtMillis: Long = System.currentTimeMillis(),
)

class ActivityViewModel(
    private val activityRepository: ActivityRepository,
    private val currentMoodStore: CurrentMoodStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ActivityUiState(moodScore = currentMoodStore.committedMood.value),
    )
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _savedFeedback = Channel<Unit>(Channel.BUFFERED)
    val savedFeedback = _savedFeedback.receiveAsFlow()

    init {
        viewModelScope.launch {
            activityRepository.observeOptions().collect { options ->
                _uiState.update { it.copy(activityOptions = options, isLoading = false) }
            }
        }
        viewModelScope.launch {
            activityRepository.observeActive().collect { active ->
                _uiState.update { it.copy(activeActivities = active, isLoading = false) }
            }
        }
        viewModelScope.launch {
            activityRepository.observeAll().collect { events ->
                _uiState.update {
                    it.copy(
                        recentActivities = events.filter { event -> event.endTime != null }.take(8),
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun setLogMode(mode: ActivityLogMode) {
        _uiState.update { it.copy(logMode = mode) }
    }

    fun onActivitySelected(name: String) {
        _uiState.update {
            it.copy(selectedName = if (it.selectedName.equals(name, ignoreCase = true)) "" else name)
        }
    }

    fun onMoodChanged(score: Float) {
        _uiState.update { it.copy(moodScore = score.coerceIn(0f, 10f)) }
    }

    fun onLoggedAtChanged(millis: Long) {
        _uiState.update { it.copy(loggedAtMillis = millis) }
    }

    fun logFullActivity() {
        val current = _uiState.value
        val name = current.selectedName.trim()
        if (name.isEmpty()) {
            _messages.trySend("Pick an activity to log.")
            return
        }
        if (current.isSaving) return
        val score = current.moodScore.roundToInt().coerceIn(0, 10)
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            activityRepository.logActivity(name, score, current.loggedAtMillis)
                .onSuccess {
                    currentMoodStore.commit(score.toFloat(), current.loggedAtMillis)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            moodScore = score.toFloat(),
                            loggedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    _savedFeedback.trySend(Unit)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSaving = false) }
                    _messages.trySend(error.message ?: "Could not log activity.")
                }
        }
    }

    fun onActivityChipTapped(activityName: String) {
        if (_uiState.value.logMode != ActivityLogMode.Session) {
            onActivitySelected(activityName)
            return
        }
        val active = _uiState.value.activeActivities
        val matching = active.firstOrNull { it.activityName.equals(activityName, ignoreCase = true) }
        val seedMood = currentMoodStore.committedMood.value
        when {
            matching != null -> {
                _uiState.update {
                    it.copy(
                        pendingActivityName = activityName,
                        pendingActivityId = matching.id,
                        promptMoodScore = seedMood,
                        moodPrompt = ActivityMoodPrompt.Post,
                    )
                }
            }
            active.size >= ActivityRepository.MAX_CONCURRENT -> {
                _messages.trySend(
                    "You can run up to ${ActivityRepository.MAX_CONCURRENT} activities at once. Finish one first.",
                )
            }
            else -> {
                _uiState.update {
                    it.copy(
                        pendingActivityName = activityName,
                        pendingActivityId = null,
                        promptMoodScore = seedMood,
                        moodPrompt = ActivityMoodPrompt.Pre,
                    )
                }
            }
        }
    }

    fun onPromptMoodChanged(score: Float) {
        _uiState.update { it.copy(promptMoodScore = score.coerceIn(0f, 10f)) }
    }

    fun dismissMoodPrompt() {
        _uiState.update {
            it.copy(moodPrompt = null, pendingActivityName = null, pendingActivityId = null)
        }
    }

    fun confirmMoodPrompt() {
        val current = _uiState.value
        val prompt = current.moodPrompt ?: return
        val name = current.pendingActivityName ?: return
        val score = current.promptMoodScore.roundToInt().coerceIn(0, 10)

        viewModelScope.launch {
            val result = when (prompt) {
                ActivityMoodPrompt.Pre -> activityRepository.startActivity(name, score, current.loggedAtMillis)
                ActivityMoodPrompt.Post -> {
                    val id = current.pendingActivityId
                    if (id == null) {
                        _messages.trySend("Could not find that activity.")
                        return@launch
                    }
                    activityRepository.endActivity(id, score)
                }
            }
            result
                .onSuccess {
                    currentMoodStore.commit(score.toFloat())
                    _savedFeedback.trySend(Unit)
                }
                .onFailure { error ->
                    _messages.trySend(error.message ?: "Something went wrong.")
                }
            dismissMoodPrompt()
        }
    }

    fun openAddActivityDialog() {
        _uiState.update {
            it.copy(showAddActivityDialog = true, newActivityName = "")
        }
    }

    fun dismissAddActivityDialog() {
        _uiState.update {
            it.copy(showAddActivityDialog = false, newActivityName = "")
        }
    }

    fun onNewActivityNameChanged(name: String) {
        _uiState.update { it.copy(newActivityName = name) }
    }

    fun confirmAddActivity() {
        val name = _uiState.value.newActivityName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            activityRepository.addCustomActivity(name)
                .onSuccess {
                    dismissAddActivityDialog()
                    _messages.trySend("Added \"$name\" to your activities.")
                }
                .onFailure { error ->
                    _messages.trySend(error.message ?: "Could not add activity.")
                }
        }
    }

    fun updateActivity(event: ActivityEvent, newPreMood: Int?, newPostMood: Int?) {
        viewModelScope.launch {
            activityRepository.updateActivity(event, newPreMood, newPostMood)
        }
    }

    private fun Float.roundToInt(): Int = round(this).toInt()

    class Factory(
        private val activityRepository: ActivityRepository,
        private val currentMoodStore: CurrentMoodStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ActivityViewModel(activityRepository, currentMoodStore) as T
        }
    }
}
