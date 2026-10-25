package com.orbit.wellness.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CurrentMoodStore
import com.orbit.wellness.data.ExerciseRepository
import com.orbit.wellness.data.local.WorkoutEvent
import com.orbit.wellness.data.local.WorkoutOption
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.round

enum class WorkoutLogMode {
    Full,
    Session,
}

enum class WorkoutMoodPrompt {
    Pre,
    Post,
}

data class ExerciseUiState(
    val logMode: WorkoutLogMode = WorkoutLogMode.Full,
    val workoutType: String = "Cardio",
    val workoutOptions: List<WorkoutOption> = emptyList(),
    val durationMinutes: String = "",
    val cardioActivity: String = "Walking",
    val customCardioActivities: List<String> = emptyList(),
    val selectedMuscleGroups: Set<String> = emptySet(),
    val notes: String = "",
    val moodScore: Float = 5f,
    val recentWorkouts: List<WorkoutEvent> = emptyList(),
    val activeWorkouts: List<WorkoutEvent> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val moodPrompt: WorkoutMoodPrompt? = null,
    val pendingWorkoutId: String? = null,
    val pendingWorkoutLabel: String? = null,
    val promptMoodScore: Float = 5f,
    val showAddCardioDialog: Boolean = false,
    val newCardioName: String = "",
    val showAddWorkoutTypeDialog: Boolean = false,
    val newWorkoutTypeName: String = "",
    val loggedAtMillis: Long = System.currentTimeMillis(),
)

class ExerciseViewModel(
    private val repository: ExerciseRepository,
    private val currentMoodStore: CurrentMoodStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ExerciseUiState(moodScore = currentMoodStore.committedMood.value),
    )
    val uiState: StateFlow<ExerciseUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _savedFeedback = Channel<Unit>(Channel.BUFFERED)
    val savedFeedback = _savedFeedback.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeWorkouts().collect { workouts ->
                _uiState.update {
                    it.copy(
                        recentWorkouts = workouts.filter { w -> w.endTime != null }.take(8),
                        isLoading = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeActive().collect { active ->
                _uiState.update { it.copy(activeWorkouts = active, isLoading = false) }
            }
        }
        viewModelScope.launch {
            repository.observeOptions().collect { options ->
                _uiState.update { it.copy(workoutOptions = options, isLoading = false) }
            }
        }
    }

    fun setLogMode(mode: WorkoutLogMode) {
        _uiState.update { it.copy(logMode = mode) }
    }

    fun onWorkoutTypeSelected(type: String) {
        _uiState.update { it.copy(workoutType = type) }
    }

    fun onDurationChanged(value: String) {
        _uiState.update {
            it.copy(durationMinutes = value.filter { ch -> ch.isDigit() }.take(4))
        }
    }

    fun onCardioActivitySelected(activity: String) {
        _uiState.update { it.copy(cardioActivity = activity) }
    }

    fun toggleMuscleGroup(group: String) {
        _uiState.update { state ->
            val next = state.selectedMuscleGroups.toMutableSet()
            if (!next.add(group)) next.remove(group)
            state.copy(selectedMuscleGroups = next)
        }
    }

    fun onNotesChanged(value: String) {
        _uiState.update { it.copy(notes = value) }
    }

    fun onMoodChanged(score: Float) {
        _uiState.update { it.copy(moodScore = score.coerceIn(0f, 10f)) }
    }

    fun onLoggedAtChanged(millis: Long) {
        _uiState.update { it.copy(loggedAtMillis = millis) }
    }

    fun saveWorkout() {
        val current = _uiState.value
        val duration = current.durationMinutes.toIntOrNull()
        if (duration == null || duration <= 0) {
            viewModelScope.launch { _messages.trySend("Enter duration in minutes.") }
            return
        }
        if (current.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val isStrength = current.workoutType.equals("Strength", ignoreCase = true)
            val isCardio = current.workoutType.equals("Cardio", ignoreCase = true)
            val score = current.moodScore.roundToInt().coerceIn(0, 10)
            repository.logWorkout(
                workoutType = current.workoutType,
                durationMinutes = duration,
                moodScore = score,
                muscleGroups = if (isStrength) current.selectedMuscleGroups.toList().sorted() else emptyList(),
                cardioActivity = if (isCardio) current.cardioActivity else null,
                notes = current.notes,
                atMillis = current.loggedAtMillis,
            ).onSuccess {
                currentMoodStore.commit(score.toFloat(), current.loggedAtMillis)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        notes = "",
                        selectedMuscleGroups = emptySet(),
                        moodScore = score.toFloat(),
                        loggedAtMillis = System.currentTimeMillis(),
                    )
                }
                _savedFeedback.trySend(Unit)
            }.onFailure { error ->
                _uiState.update { it.copy(isSaving = false) }
                _messages.trySend(error.message ?: "Could not log workout.")
            }
        }
    }

    fun startSessionWorkout() {
        val current = _uiState.value
        val seedMood = currentMoodStore.committedMood.value
        _uiState.update {
            it.copy(
                moodPrompt = WorkoutMoodPrompt.Pre,
                pendingWorkoutId = null,
                pendingWorkoutLabel = current.workoutType,
                promptMoodScore = seedMood,
            )
        }
    }

    fun onActiveWorkoutTapped(event: WorkoutEvent) {
        val seedMood = currentMoodStore.committedMood.value
        _uiState.update {
            it.copy(
                moodPrompt = WorkoutMoodPrompt.Post,
                pendingWorkoutId = event.id,
                pendingWorkoutLabel = event.workoutType,
                promptMoodScore = seedMood,
            )
        }
    }

    fun onPromptMoodChanged(score: Float) {
        _uiState.update { it.copy(promptMoodScore = score.coerceIn(0f, 10f)) }
    }

    fun dismissMoodPrompt() {
        _uiState.update {
            it.copy(moodPrompt = null, pendingWorkoutId = null, pendingWorkoutLabel = null)
        }
    }

    fun confirmMoodPrompt() {
        val current = _uiState.value
        val prompt = current.moodPrompt ?: return
        val score = current.promptMoodScore.roundToInt().coerceIn(0, 10)
        val isStrength = current.workoutType.equals("Strength", ignoreCase = true)
        val isCardio = current.workoutType.equals("Cardio", ignoreCase = true)

        viewModelScope.launch {
            val result = when (prompt) {
                WorkoutMoodPrompt.Pre -> repository.startWorkout(
                    workoutType = current.workoutType,
                    preMood = score,
                    muscleGroups = if (isStrength) current.selectedMuscleGroups.toList().sorted() else emptyList(),
                    cardioActivity = if (isCardio) current.cardioActivity else null,
                    notes = current.notes,
                    atMillis = current.loggedAtMillis,
                )
                WorkoutMoodPrompt.Post -> {
                    val id = current.pendingWorkoutId
                    if (id == null) {
                        _messages.trySend("Could not find that workout.")
                        return@launch
                    }
                    repository.endWorkout(id, score)
                }
            }
            result
                .onSuccess {
                    currentMoodStore.commit(score.toFloat())
                    if (prompt == WorkoutMoodPrompt.Pre) {
                        _uiState.update {
                            it.copy(notes = "", selectedMuscleGroups = emptySet())
                        }
                    }
                    _savedFeedback.trySend(Unit)
                }
                .onFailure { error ->
                    _messages.trySend(error.message ?: "Something went wrong.")
                }
            dismissMoodPrompt()
        }
    }

    fun openAddCardioDialog() {
        _uiState.update { it.copy(showAddCardioDialog = true, newCardioName = "") }
    }

    fun dismissAddCardioDialog() {
        _uiState.update { it.copy(showAddCardioDialog = false) }
    }

    fun onNewCardioNameChanged(value: String) {
        _uiState.update { it.copy(newCardioName = value) }
    }

    fun confirmAddCardio() {
        val name = _uiState.value.newCardioName.trim()
        if (name.isEmpty()) {
            viewModelScope.launch { _messages.trySend("Enter a cardio type.") }
            return
        }
        _uiState.update { state ->
            val customs = (state.customCardioActivities + name).distinct()
            state.copy(
                showAddCardioDialog = false,
                newCardioName = "",
                customCardioActivities = customs,
                cardioActivity = name,
            )
        }
    }

    fun openAddWorkoutTypeDialog() {
        _uiState.update { it.copy(showAddWorkoutTypeDialog = true, newWorkoutTypeName = "") }
    }

    fun dismissAddWorkoutTypeDialog() {
        _uiState.update { it.copy(showAddWorkoutTypeDialog = false) }
    }

    fun onNewWorkoutTypeNameChanged(value: String) {
        _uiState.update { it.copy(newWorkoutTypeName = value) }
    }

    fun confirmAddWorkoutType() {
        val current = _uiState.value
        val name = current.newWorkoutTypeName.trim()
        if (name.isEmpty()) {
            viewModelScope.launch { _messages.trySend("Enter a workout type.") }
            return
        }
        viewModelScope.launch {
            repository.addCustomWorkoutType(name)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            showAddWorkoutTypeDialog = false,
                            newWorkoutTypeName = "",
                            workoutType = name,
                        )
                    }
                }
                .onFailure { error ->
                    _messages.trySend(error.message ?: "Could not add workout type.")
                }
        }
    }

    fun updateWorkout(event: WorkoutEvent, newDurationMinutes: Int, newNotes: String) {
        viewModelScope.launch {
            repository.updateWorkout(event, newDurationMinutes, newNotes)
        }
    }

    private fun Float.roundToInt(): Int = round(this).toInt()

    class Factory(
        private val repository: ExerciseRepository,
        private val currentMoodStore: CurrentMoodStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ExerciseViewModel(repository, currentMoodStore) as T
        }
    }
}
