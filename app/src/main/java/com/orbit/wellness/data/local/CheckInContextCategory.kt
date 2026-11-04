package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "check_in_context_categories",
    primaryKeys = ["checkInId", "category"],
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
data class CheckInContextCategory(
    val checkInId: String,
    val category: String,
    val status: String,
    val reviewedAt: Long? = null,
) {
    init {
        require(category in CheckInContextCategories.ALL) {
            "Unknown context category: $category"
        }
        require(status in CheckInContextStatus.ALL) {
            "Unknown context status: $status"
        }
    }
}
