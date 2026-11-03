package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * An activity the person attributed to a context interval.
 * [reportedAt] is when the report was saved, not when the activity occurred.
 * [activityName] is kept even if the source [ActivityOption] is later removed.
 * There is no foreign key to activity options, so option deletion does not
 * block or rewrite these rows.
 */
@Entity(
    tableName = "check_in_activity_items",
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
data class CheckInActivityItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val checkInId: String,
    val activityOptionId: String?,
    val activityName: String,
    val reportedAt: Long,
) {
    init {
        require(activityName.isNotBlank()) {
            "Activity name must not be blank"
        }
        require(activityOptionId == null || activityOptionId.isNotBlank()) {
            "Activity option id must not be blank"
        }
    }
}
