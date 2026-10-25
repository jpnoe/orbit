package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "activity_events",
    indices = [
        Index(value = ["activityName"]),
        Index(value = ["startTime"]),
        Index(value = ["endTime"]),
    ],
)
data class ActivityEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val activityName: String,
    val startTime: Long,
    val endTime: Long? = null,
    val preMood: Int? = null,
    val postMood: Int? = null,
    val notes: String = "",
) {
    /** When the activity "counts" for history / status: end if finished, else start. */
    val effectiveAt: Long get() = endTime ?: startTime
}
