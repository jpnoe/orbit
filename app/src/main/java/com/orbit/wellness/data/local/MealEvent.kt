package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A logged meal made of one or more [MealItem]s.
 * Aggregates are stored for history / sharing without joining every time.
 */
@Entity(
    tableName = "meal_events",
    indices = [Index(value = ["timestamp"])],
)
data class MealEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val label: String = "",
    val summaryTitle: String,
    val itemCount: Int,
    val totalWeightGrams: Double,
    val calories: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val carbsGrams: Double?,
)
