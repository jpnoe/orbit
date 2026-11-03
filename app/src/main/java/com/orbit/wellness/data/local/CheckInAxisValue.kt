package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "check_in_axis_values",
    primaryKeys = ["checkInId", "axisId"],
    foreignKeys = [
        ForeignKey(
            entity = CheckInRecord::class,
            parentColumns = ["id"],
            childColumns = ["checkInId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CheckInAxis::class,
            parentColumns = ["id"],
            childColumns = ["axisId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["checkInId"]),
        Index(value = ["axisId"]),
    ],
)
data class CheckInAxisValue(
    val checkInId: String,
    val axisId: String,
    val value: Int,
) {
    init {
        require(value in MIN_VALUE..MAX_VALUE) {
            "Check-in axis value must be in $MIN_VALUE..$MAX_VALUE, was $value"
        }
    }

    companion object {
        const val MIN_VALUE = 0
        const val MAX_VALUE = 10
    }
}
