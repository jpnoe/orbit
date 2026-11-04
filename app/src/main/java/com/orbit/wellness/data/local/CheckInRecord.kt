package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "check_ins",
    indices = [
        Index(value = ["localDate"]),
        Index(value = ["recordedAt"]),
        Index(value = ["trigger"]),
    ],
)
data class CheckInRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val localDate: String,
    val recordedAt: Long,
    val trigger: String,
    val note: String = "",
    /**
     * Selected emotion snapshot. Independent of the numeric mood score.
     * Empty when none was selected. Not a foreign key.
     */
    val emotion: String = "",
) {
    companion object {
        const val TRIGGER_WAKE = "wake"
        const val TRIGGER_SCHEDULED = "scheduled"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_EVENING = "evening"
    }
}
