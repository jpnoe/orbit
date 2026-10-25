package com.orbit.wellness.ui.use

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.ActivityRepository
import com.orbit.wellness.data.DietRepository
import com.orbit.wellness.data.ExerciseRepository
import com.orbit.wellness.data.MoodRepository
import com.orbit.wellness.data.SubstanceRepository
import com.orbit.wellness.data.local.ActivityOption
import com.orbit.wellness.data.local.EmotionOption
import com.orbit.wellness.data.local.FoodOption
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceOption
import com.orbit.wellness.data.local.WorkoutOption
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ListCategory {
    SUPPORTIVE,
    COPING,
    DIET,
    ACTIVITY,
    WORKOUT,
    EMOTION,
}

data class MyListsUiState(
    val supportive: List<SubstanceOption> = emptyList(),
    val coping: List<SubstanceOption> = emptyList(),
    val foodOptions: List<FoodOption> = emptyList(),
    val activityOptions: List<ActivityOption> = emptyList(),
    val workoutOptions: List<WorkoutOption> = emptyList(),
    val emotionOptions: List<EmotionOption> = emptyList(),
    val newName: String = "",
    val newAmount: String = "",
    val addToCategory: ListCategory = ListCategory.SUPPORTIVE,
    val isLoading: Boolean = true,
)

class MyListsViewModel(
    private val substanceRepository: SubstanceRepository,
    private val dietRepository: DietRepository,
    private val activityRepository: ActivityRepository,
    private val exerciseRepository: ExerciseRepository,
    private val moodRepository: MoodRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyListsUiState())
    val uiState: StateFlow<MyListsUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            substanceRepository.observeOptions().collect { options ->
                _uiState.update {
                    it.copy(
                        supportive = options.filter { o -> o.intent == SubstanceIntent.SUPPORTIVE.name },
                        coping = options.filter { o -> o.intent == SubstanceIntent.COPING.name },
                        isLoading = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            dietRepository.observeFoodOptions().collect { foods ->
                _uiState.update { it.copy(foodOptions = foods, isLoading = false) }
            }
        }
        viewModelScope.launch {
            activityRepository.observeOptions().collect { activities ->
                _uiState.update { it.copy(activityOptions = activities, isLoading = false) }
            }
        }
        viewModelScope.launch {
            exerciseRepository.observeOptions().collect { workouts ->
                _uiState.update { it.copy(workoutOptions = workouts, isLoading = false) }
            }
        }
        viewModelScope.launch {
            moodRepository.observeEmotionOptions().collect { emotions ->
                _uiState.update { it.copy(emotionOptions = emotions, isLoading = false) }
            }
        }
    }

    fun onNewNameChanged(value: String) {
        _uiState.update { it.copy(newName = value) }
    }

    fun onNewAmountChanged(value: String) {
        _uiState.update { it.copy(newAmount = value) }
    }

    fun onAddToCategorySelected(category: ListCategory) {
        _uiState.update { it.copy(addToCategory = category) }
    }

    fun addItem() {
        val current = _uiState.value
        val name = current.newName.trim()
        if (name.isEmpty()) {
            viewModelScope.launch { _messages.trySend("Enter a name for the list item.") }
            return
        }
        viewModelScope.launch {
            when (current.addToCategory) {
                ListCategory.SUPPORTIVE -> {
                    substanceRepository.addOption(
                        name = name,
                        intent = SubstanceIntent.SUPPORTIVE,
                        defaultAmount = current.newAmount,
                    )
                }
                ListCategory.COPING -> {
                    substanceRepository.addOption(
                        name = name,
                        intent = SubstanceIntent.COPING,
                        defaultAmount = current.newAmount,
                    )
                }
                ListCategory.DIET -> {
                    val weight = current.newAmount.toIntOrNull() ?: 100
                    dietRepository.addFoodOption(name = name, defaultWeightGrams = weight)
                }
                ListCategory.ACTIVITY -> {
                    activityRepository.addCustomActivity(name)
                }
                ListCategory.WORKOUT -> {
                    exerciseRepository.addCustomWorkoutType(name)
                }
                ListCategory.EMOTION -> {
                    moodRepository.addCustomEmotion(name)
                }
            }
            _uiState.update { it.copy(newName = "", newAmount = "") }
        }
    }

    fun deleteSubstanceItem(id: String) {
        viewModelScope.launch {
            substanceRepository.deleteOption(id)
        }
    }

    fun deleteFoodItem(id: String) {
        viewModelScope.launch {
            dietRepository.deleteFoodOption(id)
        }
    }

    fun deleteActivityItem(id: String) {
        viewModelScope.launch {
            activityRepository.deleteActivityOption(id)
        }
    }

    fun deleteWorkoutItem(id: String) {
        viewModelScope.launch {
            exerciseRepository.deleteWorkoutOption(id)
        }
    }

    fun deleteEmotionItem(id: String) {
        viewModelScope.launch {
            moodRepository.deleteEmotionOption(id)
        }
    }

    class Factory(
        private val substanceRepository: SubstanceRepository,
        private val dietRepository: DietRepository,
        private val activityRepository: ActivityRepository,
        private val exerciseRepository: ExerciseRepository,
        private val moodRepository: MoodRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MyListsViewModel(
                substanceRepository,
                dietRepository,
                activityRepository,
                exerciseRepository,
                moodRepository,
            ) as T
        }
    }
}
