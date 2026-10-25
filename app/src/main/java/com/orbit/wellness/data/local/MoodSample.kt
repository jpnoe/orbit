package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "mood_samples",
    indices = [
        Index(value = ["localDate"]),
        Index(value = ["recordedAt"]),
    ],
)
data class MoodSample(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val localDate: String,
    val moodScore: Int,
    val note: String = "",
    val emotion: String = "",
    val recordedAt: Long,
    val source: String,
) {
    companion object {
        const val SOURCE_CHECK_IN = "check_in"
        const val SOURCE_ACTIVITY_PRE = "activity_pre"
        const val SOURCE_ACTIVITY_POST = "activity_post"
        const val SOURCE_ACTIVITY_LOG = "activity_log"
        const val SOURCE_WORKOUT_PRE = "workout_pre"
        const val SOURCE_WORKOUT_POST = "workout_post"
        const val SOURCE_WORKOUT_LOG = "workout_log"
    }
}
