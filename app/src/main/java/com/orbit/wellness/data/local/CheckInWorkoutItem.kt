package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A workout the person attributed to a context interval.
 * [reportedAt] is when the report was saved, not when the workout occurred.
 * [workoutName] is kept even if the source [WorkoutOption] is later removed.
 * There is no foreign key to workout options, so option deletion does not
 * block or rewrite these rows. Repeated option ids are separate sessions.
 */
@Entity(
    tableName = "check_in_workout_items",
    foreignKeys = [
        ForeignKey(
            entity = CheckInContextWindow::class,
            parentColumns = ["checkInId"],
            childColumns = ["checkInId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["checkInId"]),
    ],
)
data class CheckInWorkoutItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val checkInId: String,
    val workoutOptionId: String?,
    val workoutName: String,
    val durationMinutes: Int,
    val reportedAt: Long,
) {
    init {
        require(workoutName.isNotBlank()) {
            "Workout name must not be blank"
        }
        require(workoutOptionId == null || workoutOptionId.isNotBlank()) {
            "Workout option id must not be blank"
        }
        require(durationMinutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES) {
            "Workout duration must be from $MIN_DURATION_MINUTES to $MAX_DURATION_MINUTES minutes"
        }
    }

    companion object {
        const val MIN_DURATION_MINUTES = 1
        const val MAX_DURATION_MINUTES = 1_440
    }
}
