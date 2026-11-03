package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInActivitySelection
import com.orbit.wellness.data.CheckInMealSelection
import com.orbit.wellness.data.CheckInUsageSelection
import com.orbit.wellness.data.CheckInWorkoutSelection
import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.CheckInWorkoutItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Context reports held until the overview Save writes the check-in.
 * A missing or empty list is none. A nonempty list is entries.
 */
class PendingCheckInSession(
    val window: CheckInContextWindow,
) {
    private val _activity = MutableStateFlow<List<CheckInActivitySelection>?>(null)
    val activity: StateFlow<List<CheckInActivitySelection>?> = _activity.asStateFlow()

    private val _workout = MutableStateFlow<List<CheckInWorkoutSelection>?>(null)
    val workout: StateFlow<List<CheckInWorkoutSelection>?> = _workout.asStateFlow()

    private val _usage = MutableStateFlow<List<CheckInUsageSelection>?>(null)
    val usage: StateFlow<List<CheckInUsageSelection>?> = _usage.asStateFlow()

    private val _diet = MutableStateFlow<List<CheckInMealSelection>?>(null)
    val diet: StateFlow<List<CheckInMealSelection>?> = _diet.asStateFlow()

    fun stageActivity(selections: List<CheckInActivitySelection>) {
        _activity.value = selections
    }

    fun stageWorkout(selections: List<CheckInWorkoutSelection>) {
        _workout.value = selections
    }

    fun stageUsage(selections: List<CheckInUsageSelection>) {
        _usage.value = selections
    }

    fun stageDiet(meals: List<CheckInMealSelection>) {
        _diet.value = meals
    }

    fun categoryRows(): List<CheckInContextCategory> {
        return CheckInContextCategories.ALL.map { category ->
            val reviewed = when (category) {
                CheckInContextCategories.ACTIVITY -> _activity.value
                CheckInContextCategories.WORKOUT -> _workout.value
                CheckInContextCategories.USAGE -> _usage.value
                CheckInContextCategories.DIET -> _diet.value
                else -> null
            }
            CheckInContextCategory(
                checkInId = ID,
                category = category,
                status = if (reviewed.isNullOrEmpty()) {
                    CheckInContextStatus.NONE
                } else {
                    CheckInContextStatus.ENTRIES
                },
            )
        }
    }

    fun activityItems(): List<CheckInActivityItem> {
        return _activity.value.orEmpty().map { selection ->
            CheckInActivityItem(
                checkInId = ID,
                activityOptionId = selection.activityOptionId,
                activityName = selection.activityName,
                reportedAt = window.windowEndAt,
            )
        }
    }

    fun workoutItems(): List<CheckInWorkoutItem> {
        return _workout.value.orEmpty().map { selection ->
            CheckInWorkoutItem(
                checkInId = ID,
                workoutOptionId = selection.workoutOptionId,
                workoutName = selection.workoutName,
                durationMinutes = selection.durationMinutes,
                reportedAt = window.windowEndAt,
            )
        }
    }

    fun dietMeals(): List<DietOverviewMeal> {
        return _diet.value.orEmpty()
            .sortedBy { it.sortOrder }
            .map { meal ->
                DietOverviewMeal(
                    size = meal.size,
                    foodNames = meal.foods.sortedBy { it.sortOrder }.map { it.foodName },
                )
            }
    }

    fun usageItems(): List<CheckInUsageItem> {
        return _usage.value.orEmpty().map { selection ->
            CheckInUsageItem(
                checkInId = ID,
                substanceOptionId = selection.substanceOptionId,
                name = selection.name,
                intent = selection.intent,
                amount = selection.amount,
                reportedAt = window.windowEndAt,
            )
        }
    }

    companion object {
        const val ID = "pending"
    }
}
