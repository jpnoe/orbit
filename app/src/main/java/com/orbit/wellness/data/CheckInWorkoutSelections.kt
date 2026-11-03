package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInWorkoutItem

data class CheckInWorkoutSelection(
    val workoutOptionId: String?,
    val workoutName: String,
    val durationMinutes: Int,
)

object CheckInWorkoutSelections {
    fun validate(selections: List<CheckInWorkoutSelection>, contextWindowExists: Boolean) {
        require(contextWindowExists) {
            "Workout context window does not exist"
        }
        require(selections.none { it.workoutName.isBlank() }) {
            "Workout name must not be blank"
        }
        require(selections.none { it.workoutOptionId?.isBlank() == true }) {
            "Workout option id must not be blank"
        }
        require(
            selections.all { selection ->
                selection.durationMinutes in CheckInWorkoutItem.MIN_DURATION_MINUTES..CheckInWorkoutItem.MAX_DURATION_MINUTES
            },
        ) {
            "Workout duration must be from ${CheckInWorkoutItem.MIN_DURATION_MINUTES} to ${CheckInWorkoutItem.MAX_DURATION_MINUTES} minutes"
        }
    }
}
