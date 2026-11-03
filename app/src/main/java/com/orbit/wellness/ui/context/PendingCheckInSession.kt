package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInActivitySelection
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
 * A null list is still unreviewed. An empty list is an explicit none.
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

    fun stageActivity(selections: List<CheckInActivitySelection>) {
        _activity.value = selections
    }

    fun stageWorkout(selections: List<CheckInWorkoutSelection>) {
        _workout.value = selections
    }

    fun stageUsage(selections: List<CheckInUsageSelection>) {
        _usage.value = selections
    }

    fun categoryRows(): List<CheckInContextCategory> {
        return CheckInContextCategories.ALL.map { category ->
            val reviewed = when (category) {
                CheckInContextCategories.ACTIVITY -> _activity.value
                CheckInContextCategories.WORKOUT -> _workout.value
                CheckInContextCategories.USAGE -> _usage.value
                else -> null
            }
            CheckInContextCategory(
                checkInId = ID,
                category = category,
                status = when {
                    reviewed == null -> CheckInContextStatus.UNREVIEWED
                    reviewed.isEmpty() -> CheckInContextStatus.NONE
                    else -> CheckInContextStatus.ENTRIES
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
