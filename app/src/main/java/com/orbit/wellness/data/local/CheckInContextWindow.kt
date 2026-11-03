package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Interval the person was asked about when [checkInId] was collected.
 * [windowStartAt] is a snapshot. Deleting the previous check-in clears
 * [previousCheckInId] and leaves the stored start unchanged.
 * [windowEndAt] is the ending check-in's recordedAt at collection time.
 */
@Entity(
    tableName = "check_in_context_windows",
    foreignKeys = [
        ForeignKey(
            entity = CheckInRecord::class,
            parentColumns = ["id"],
            childColumns = ["checkInId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CheckInRecord::class,
            parentColumns = ["id"],
            childColumns = ["previousCheckInId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["previousCheckInId"]),
    ],
)
data class CheckInContextWindow(
    @PrimaryKey val checkInId: String,
    val previousCheckInId: String?,
    val windowStartAt: Long,
    val windowEndAt: Long,
    val startBasis: String,
    val completedAt: Long? = null,
) {
    init {
        require(windowStartAt <= windowEndAt) {
            "Context window start must not be later than its end"
        }
        require(startBasis in CheckInContextStartBasis.ALL) {
            "Unknown context start basis: $startBasis"
        }
        when (startBasis) {
            CheckInContextStartBasis.PREVIOUS_CHECK_IN -> require(previousCheckInId != null) {
                "A previous-check-in window requires previousCheckInId"
            }
            else -> require(previousCheckInId == null) {
                "previousCheckInId is only stored for a previous check-in boundary"
            }
        }
    }
}
