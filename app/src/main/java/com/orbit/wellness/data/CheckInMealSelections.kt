package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInMeal

data class CheckInMealFoodSelection(
    val foodOptionId: String?,
    val foodName: String,
    val sortOrder: Int,
)

data class CheckInMealSelection(
    val id: String,
    val size: String,
    val sortOrder: Int,
    val foods: List<CheckInMealFoodSelection> = emptyList(),
)

object CheckInMealSelections {
    const val NONE_LABEL = "No meals to report"

    fun validate(meals: List<CheckInMealSelection>, contextWindowExists: Boolean) {
        require(contextWindowExists) {
            "Diet context window does not exist"
        }
        require(meals.none { it.id.isBlank() }) {
            "Meal id must not be blank"
        }
        val duplicateMealIds = meals
            .map { it.id }
            .groupingBy { it }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateMealIds.isEmpty()) {
            "Duplicate meals are not allowed: ${duplicateMealIds.sorted().joinToString()}"
        }
        require(meals.all { it.size in CheckInMeal.SIZES }) {
            "Meal size must be ${CheckInMeal.SIZES.sorted().joinToString(", ")}"
        }
        require(meals.none { it.size.equals(NONE_LABEL, ignoreCase = true) }) {
            "A none placeholder is not a meal"
        }
        require(meals.all { it.sortOrder >= 0 }) {
            "Meal sort order must not be negative"
        }
        val foods = meals.flatMap { it.foods }
        require(foods.none { it.foodName.isBlank() }) {
            "Food name must not be blank"
        }
        require(foods.none { it.foodOptionId?.isBlank() == true }) {
            "Food option id must not be blank"
        }
        require(foods.all { it.sortOrder >= 0 }) {
            "Food sort order must not be negative"
        }
        meals.forEach { meal ->
            val duplicateOptionIds = meal.foods
                .mapNotNull { it.foodOptionId }
                .groupingBy { it }
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
            require(duplicateOptionIds.isEmpty()) {
                "Duplicate foods are not allowed in one meal: ${duplicateOptionIds.sorted().joinToString()}"
            }
        }
    }
}
