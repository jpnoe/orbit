package com.orbit.wellness.ui.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.DietRepository
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.FoodOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContextualDietUiState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val title: String = "",
    val detail: String? = null,
    val options: List<FoodOption> = emptyList(),
    val meals: List<DietMealDraft> = emptyList(),
    val selectedSize: String? = null,
    val selectedFoods: List<DietFoodDraft> = emptyList(),
    val editingId: String? = null,
    val isSaving: Boolean = false,
    val showAddDialog: Boolean = false,
    val newFoodName: String = "",
)

class ContextualDietViewModel(
    private val checkInId: String,
    private val checkInRepository: CheckInRepository,
    private val dietRepository: DietRepository,
    private val pending: PendingCheckInSession? = null,
) : ViewModel() {

    private val draft = ContextualDietDraft()
    private val _uiState = MutableStateFlow(ContextualDietUiState())
    val uiState: StateFlow<ContextualDietUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _finished = Channel<Unit>(Channel.BUFFERED)
    val finished = _finished.receiveAsFlow()

    init {
        if (checkInId.isBlank()) {
            _uiState.value = ContextualDietUiState(loaded = true, missing = true)
        } else if (pending != null && checkInId == PendingCheckInSession.ID) {
            viewModelScope.launch {
                combine(pending.diet, dietRepository.observeFoodOptions()) { selections, options ->
                    selections to options
                }.collect { (selections, options) ->
                    if (!draft.hasApplied) {
                        draft.onSaved(mealsFrom(selections), foodsFrom(selections))
                    }
                    draft.onOptions(options)
                    publish(pending.window, options)
                }
            }
        } else {
            viewModelScope.launch {
                combine(
                    checkInRepository.observeContextWindow(checkInId),
                    checkInRepository.observeMeals(checkInId),
                    checkInRepository.observeMealFoods(checkInId),
                    dietRepository.observeFoodOptions(),
                ) { window, meals, foods, options ->
                    DietObservation(window, meals, foods, options)
                }.collect { observation ->
                    if (!draft.hasApplied) {
                        draft.onSaved(observation.meals, observation.foods)
                    }
                    draft.onOptions(observation.options)
                    publish(observation.window, observation.options)
                }
            }
        }
    }

    fun selectSize(size: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        draft.selectSize(size)
        publishComposer()
    }

    fun toggleFood(option: FoodOption) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        draft.toggleFood(option)
        publishComposer()
    }

    fun removeFood(key: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        draft.removeFood(key)
        publishComposer()
    }

    fun beginEdit(id: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        draft.beginEdit(id)
        publishComposer()
    }

    fun remove(id: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        draft.remove(id)
        publishComposer()
    }

    fun addOrUpdate() {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        val error = draft.addOrUpdate()
        if (error != null) {
            _messages.trySend(error)
            return
        }
        publishComposer()
    }

    fun openAddDialog() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(showAddDialog = true, newFoodName = "") }
    }

    fun dismissAddDialog() {
        _uiState.update { it.copy(showAddDialog = false, newFoodName = "") }
    }

    fun onNewFoodNameChanged(name: String) {
        _uiState.update { it.copy(newFoodName = name) }
    }

    fun confirmAddFood() {
        val name = _uiState.value.newFoodName.trim()
        if (name.isEmpty() || _uiState.value.isSaving) return
        viewModelScope.launch {
            dietRepository.addFoodOption(name)
                .onSuccess {
                    draft.selectAdded(name)
                    _uiState.update {
                        it.copy(
                            showAddDialog = false,
                            newFoodName = "",
                            selectedFoods = draft.selectedFoods,
                        )
                    }
                }
                .onFailure { error ->
                    _messages.trySend(error.message ?: "Could not add food.")
                }
        }
    }

    fun saveMeals() {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        commit(draft.commitForSave())
    }

    private fun commit(commit: DietReviewCommit) {
        if (pending != null && commit is DietReviewCommit.Replace) {
            pending.stageDiet(commit.meals)
            _finished.trySend(Unit)
            return
        }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                commitDietReview(checkInRepository, checkInId, commit)
                _uiState.update { it.copy(isSaving = false) }
                _finished.trySend(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishComposer(isSaving = false)
                _messages.trySend(error.message ?: "Could not save meals.")
            }
        }
    }

    private fun publish(window: CheckInContextWindow?, options: List<FoodOption>) {
        _uiState.update { state ->
            state.copy(
                loaded = true,
                missing = window == null,
                title = window?.let { headingTitle(it) }.orEmpty(),
                detail = window?.let { headingDetail(it) },
                options = options,
                meals = draft.meals,
                selectedSize = draft.selectedSize,
                selectedFoods = draft.selectedFoods,
                editingId = draft.editingId,
            )
        }
    }

    private fun publishComposer(isSaving: Boolean = _uiState.value.isSaving) {
        _uiState.update {
            it.copy(
                meals = draft.meals,
                selectedSize = draft.selectedSize,
                selectedFoods = draft.selectedFoods,
                editingId = draft.editingId,
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
        private val dietRepository: DietRepository,
        private val pending: PendingCheckInSession? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContextualDietViewModel(
                checkInId,
                checkInRepository,
                dietRepository,
                pending,
            ) as T
        }
    }
}

private data class DietObservation(
    val window: CheckInContextWindow?,
    val meals: List<CheckInMeal>,
    val foods: List<CheckInMealFoodItem>,
    val options: List<FoodOption>,
)

private fun mealsFrom(selections: List<com.orbit.wellness.data.CheckInMealSelection>?): List<CheckInMeal> {
    return selections.orEmpty().map { meal ->
        CheckInMeal(
            id = meal.id,
            checkInId = PendingCheckInSession.ID,
            size = meal.size,
            sortOrder = meal.sortOrder,
            reportedAt = 0L,
        )
    }
}

private fun foodsFrom(selections: List<com.orbit.wellness.data.CheckInMealSelection>?): List<CheckInMealFoodItem> {
    return selections.orEmpty().flatMap { meal ->
        meal.foods.map { food ->
            CheckInMealFoodItem(
                mealId = meal.id,
                foodOptionId = food.foodOptionId,
                foodName = food.foodName,
                sortOrder = food.sortOrder,
            )
        }
    }
}
