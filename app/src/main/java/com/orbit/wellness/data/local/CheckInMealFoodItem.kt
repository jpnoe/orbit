package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A food the person attached to one contextual meal.
 * [foodName] is kept even if the source [FoodOption] is later removed.
 * There is no foreign key to food options, so option deletion does not
 * rewrite these rows. The same option may appear in another meal.
 */
@Entity(
    tableName = "check_in_meal_food_items",
    foreignKeys = [
        ForeignKey(
            entity = CheckInMeal::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["mealId"]),
    ],
)
data class CheckInMealFoodItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val mealId: String,
    val foodOptionId: String?,
    val foodName: String,
    val sortOrder: Int,
) {
    init {
        require(foodName.isNotBlank()) { "Food name must not be blank" }
        require(foodOptionId == null || foodOptionId.isNotBlank()) {
            "Food option id must not be blank"
        }
        require(sortOrder >= 0) { "Food sort order must not be negative" }
    }
}
