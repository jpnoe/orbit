package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * An eating occasion the person attributed to a context interval.
 * [size] is a subjective description, not a calorie or portion measurement.
 * [reportedAt] is when the report was saved, not when the meal occurred.
 * [sortOrder] is the order the person entered, not a clock time.
 * A meal may have no food items.
 */
@Entity(
    tableName = "check_in_meals",
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
data class CheckInMeal(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val checkInId: String,
    val size: String,
    val sortOrder: Int,
    val reportedAt: Long,
) {
    init {
        require(id.isNotBlank()) { "Meal id must not be blank" }
        require(size in SIZES) {
            "Meal size must be ${SIZES.joinToString(", ")}"
        }
        require(sortOrder >= 0) { "Meal sort order must not be negative" }
    }

    companion object {
        const val SNACK = "snack"
        const val SMALL = "small"
        const val MEDIUM = "medium"
        const val LARGE = "large"
        val SIZES = setOf(SNACK, SMALL, MEDIUM, LARGE)
    }
}
