package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "workout_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["startTime"]),
        Index(value = ["endTime"]),
    ],
)
data class WorkoutEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** Sort / history key: end if finished, else start. */
    val timestamp: Long,
    val workoutType: String,
    val durationMinutes: Int,
    val muscleGroups: String = "",
    val cardioActivity: String? = null,
    val notes: String = "",
    val startTime: Long = timestamp,
    /** Null while a tracked session is in progress. */
    val endTime: Long? = timestamp,
    val preMood: Int? = null,
    val postMood: Int? = null,
) {
    val effectiveAt: Long get() = endTime ?: startTime

    val isActive: Boolean get() = endTime == null
}
