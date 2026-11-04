package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInMealFoodSelection
import com.orbit.wellness.data.CheckInMealSelection
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.FoodOption
import java.util.UUID

data class DietFoodDraft(
    val key: String,
    val foodOptionId: String?,
    val foodName: String,
)

data class DietMealDraft(
    val id: String,
    val size: String,
    val foods: List<DietFoodDraft>,
)

sealed interface DietReviewCommit {
    data object Leave : DietReviewCommit
    data class Replace(val meals: List<CheckInMealSelection>) : DietReviewCommit
}

class ContextualDietDraft {
    private var applied = false
    private var optionsReady = false
    private var mealsReady = false
    private var options: List<FoodOption> = emptyList()
    private var savedMeals: List<CheckInMeal> = emptyList()
    private var savedFoods: List<CheckInMealFoodItem> = emptyList()
    private var pendingName: String? = null

    var meals: List<DietMealDraft> = emptyList()
        private set
    var selectedSize: String? = null
        private set
    var selectedFoods: List<DietFoodDraft> = emptyList()
        private set
    var editingId: String? = null
        private set

    val hasApplied: Boolean get() = applied

    fun onOptions(options: List<FoodOption>) {
        this.options = options
        optionsReady = true
        preloadIfReady()
        applyPending()
    }

    fun onSaved(meals: List<CheckInMeal>, foods: List<CheckInMealFoodItem>) {
        savedMeals = meals
        savedFoods = foods
        mealsReady = true
        preloadIfReady()
    }

    fun selectSize(size: String) {
        selectedSize = size
    }

    fun toggleFood(option: FoodOption) {
        val existing = selectedFoods.indexOfFirst { it.foodOptionId == option.id }
        selectedFoods = if (existing >= 0) {
            selectedFoods.filterIndexed { index, _ -> index != existing }
        } else {
            selectedFoods + DietFoodDraft(
                key = option.id,
                foodOptionId = option.id,
                foodName = option.name,
            )
        }
    }

    fun removeFood(key: String) {
        selectedFoods = selectedFoods.filterNot { it.key == key }
    }

    fun selectAdded(name: String) {
        pendingName = name.trim()
        applyPending()
    }

    fun addOrUpdate(): String? {
        val size = selectedSize ?: return "Choose a meal size."
        val draft = DietMealDraft(
            id = editingId ?: UUID.randomUUID().toString(),
            size = size,
            foods = selectedFoods,
        )
        meals = if (editingId == null) {
            meals + draft
        } else {
            meals.map { meal -> if (meal.id == editingId) draft else meal }
        }
        clearComposer()
        return null
    }

    fun beginEdit(id: String) {
        val meal = meals.firstOrNull { it.id == id } ?: return
        editingId = meal.id
        selectedSize = meal.size
        selectedFoods = meal.foods
    }

    fun remove(id: String) {
        meals = meals.filterNot { it.id == id }
        if (editingId == id) clearComposer()
    }

    fun snapshots(): List<CheckInMealSelection> {
        return meals.mapIndexed { index, meal ->
            CheckInMealSelection(
                id = meal.id,
                size = meal.size,
                sortOrder = index,
                foods = meal.foods.mapIndexed { foodIndex, food ->
                    CheckInMealFoodSelection(
                        foodOptionId = food.foodOptionId,
                        foodName = food.foodName,
                        sortOrder = foodIndex,
                    )
                },
            )
        }
    }

    fun commitForSave(): DietReviewCommit = DietReviewCommit.Replace(snapshots())

    fun commitForNone(): DietReviewCommit = DietReviewCommit.Replace(emptyList())

    fun commitForBack(): DietReviewCommit = DietReviewCommit.Leave

    private fun preloadIfReady() {
        if (applied || !optionsReady || !mealsReady) return
        val foodsByMeal = savedFoods.groupBy { it.mealId }
        meals = savedMeals.sortedBy { it.sortOrder }.map { meal ->
            DietMealDraft(
                id = meal.id,
                size = meal.size,
                foods = foodsByMeal[meal.id].orEmpty().sortedBy { it.sortOrder }.map { food ->
                    DietFoodDraft(
                        key = food.foodOptionId ?: food.id,
                        foodOptionId = food.foodOptionId,
                        foodName = food.foodName,
                    )
                },
            )
        }
        applied = true
    }

    private fun applyPending() {
        val name = pendingName ?: return
        val match = options.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
        if (selectedFoods.none { it.foodOptionId == match.id }) {
            selectedFoods = selectedFoods + DietFoodDraft(
                key = match.id,
                foodOptionId = match.id,
                foodName = match.name,
            )
        }
        pendingName = null
    }

    private fun clearComposer() {
        editingId = null
        selectedSize = null
        selectedFoods = emptyList()
    }
}

suspend fun commitDietReview(
    repository: CheckInRepository,
    checkInId: String,
    commit: DietReviewCommit,
) {
    when (commit) {
        DietReviewCommit.Leave -> Unit
        is DietReviewCommit.Replace -> repository.saveDietSelections(
            checkInId = checkInId,
            meals = commit.meals,
        )
    }
}
