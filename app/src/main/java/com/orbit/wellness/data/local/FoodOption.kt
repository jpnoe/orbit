package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Quick-add foods with macros per 100g for scaling by weight.
 * Defaults intentionally exclude beans and heavy/thick cheeses.
 */
@Entity(tableName = "food_options")
data class FoodOption(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sortOrder: Int,
    val isCustom: Boolean,
    val caloriesPer100g: Double?,
    val proteinPer100g: Double?,
    val fatPer100g: Double?,
    val carbsPer100g: Double?,
    val defaultWeightGrams: Int = 100,
)
