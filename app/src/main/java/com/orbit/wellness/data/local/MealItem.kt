package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "meal_items",
    foreignKeys = [
        ForeignKey(
            entity = MealEvent::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mealId"])],
)
data class MealItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val mealId: String,
    val foodName: String,
    val weightGrams: Double,
    /** Entered amount as shown to the user (g, oz, fl oz, or servings). */
    val amountValue: Double = weightGrams,
    /** [com.orbit.wellness.util.VolumeUnits] unit key. */
    val amountUnit: String = "g",
    val calories: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val carbsGrams: Double?,
    val barcode: String? = null,
    val source: String = SOURCE_MANUAL,
    val sortOrder: Int = 0,
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_QUICK_ADD = "quick_add"
        const val SOURCE_BARCODE = "barcode"
        const val SOURCE_SEARCH = "search"
    }
}
