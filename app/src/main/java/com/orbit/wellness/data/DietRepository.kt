package com.orbit.wellness.data

import androidx.room.withTransaction
import com.orbit.wellness.data.local.FoodOption
import com.orbit.wellness.data.local.FoodOptionDao
import com.orbit.wellness.data.local.MealEvent
import com.orbit.wellness.data.local.MealEventDao
import com.orbit.wellness.data.local.MealItem
import com.orbit.wellness.data.local.MealItemDao
import com.orbit.wellness.data.local.OrbitDatabase
import com.orbit.wellness.data.remote.OpenFoodFactsClient
import com.orbit.wellness.data.remote.OpenFoodFactsProduct
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class MealItemInput(
    val foodName: String,
    val weightGrams: Double,
    val amountValue: Double = weightGrams,
    val amountUnit: String = "g",
    val calories: Double? = null,
    val proteinGrams: Double? = null,
    val fatGrams: Double? = null,
    val carbsGrams: Double? = null,
    val barcode: String? = null,
    val source: String = MealItem.SOURCE_MANUAL,
)

class DietRepository(
    private val database: OrbitDatabase,
    private val mealDao: MealEventDao,
    private val mealItemDao: MealItemDao,
    private val foodOptionDao: FoodOptionDao,
    private val openFoodFactsClient: OpenFoodFactsClient = OpenFoodFactsClient(),
) {
    fun observeMeals(): Flow<List<MealEvent>> = mealDao.observeAll()

    fun observeFoodOptions(): Flow<List<FoodOption>> = foodOptionDao.observeAll()

    suspend fun getMealItems(mealId: String): List<MealItem> = mealItemDao.getForMeal(mealId)

    suspend fun lookupBarcode(barcode: String): Result<OpenFoodFactsProduct> {
        return openFoodFactsClient.fetchProduct(barcode.trim())
    }

    suspend fun searchFoods(query: String): Result<List<OpenFoodFactsProduct>> {
        return openFoodFactsClient.searchProducts(query)
    }

    suspend fun logMeal(
        items: List<MealItemInput>,
        label: String = "",
        timestamp: Long = System.currentTimeMillis(),
    ) {
        require(items.isNotEmpty()) { "Add at least one food item." }
        val mealId = UUID.randomUUID().toString()
        val prepared = items.mapIndexed { index, input ->
            MealItem(
                mealId = mealId,
                foodName = input.foodName.trim(),
                weightGrams = input.weightGrams.coerceAtLeast(0.01),
                amountValue = input.amountValue.coerceAtLeast(0.01),
                amountUnit = input.amountUnit,
                calories = input.calories,
                proteinGrams = input.proteinGrams,
                fatGrams = input.fatGrams,
                carbsGrams = input.carbsGrams,
                barcode = input.barcode,
                source = input.source,
                sortOrder = index,
            )
        }
        val summaryTitle = prepared.joinToString(", ") { it.foodName }
            .let { if (it.length > 80) it.take(77) + "…" else it }

        val meal = MealEvent(
            id = mealId,
            timestamp = timestamp,
            label = label.trim(),
            summaryTitle = summaryTitle,
            itemCount = prepared.size,
            totalWeightGrams = prepared.sumOf { it.weightGrams },
            calories = prepared.mapNotNull { it.calories }.takeIf { it.isNotEmpty() }?.sum(),
            proteinGrams = prepared.mapNotNull { it.proteinGrams }.takeIf { it.isNotEmpty() }?.sum(),
            fatGrams = prepared.mapNotNull { it.fatGrams }.takeIf { it.isNotEmpty() }?.sum(),
            carbsGrams = prepared.mapNotNull { it.carbsGrams }.takeIf { it.isNotEmpty() }?.sum(),
        )

        database.withTransaction {
            mealDao.insert(meal)
            mealItemDao.insertAll(prepared)
        }
    }

    suspend fun addFoodOption(
        name: String,
        defaultWeightGrams: Int = 100,
        caloriesPer100g: Double? = null,
        proteinPer100g: Double? = null,
        fatPer100g: Double? = null,
        carbsPer100g: Double? = null,
    ): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Food name cannot be empty"))
        }
        if (foodOptionDao.countByName(trimmed) > 0) {
            return Result.failure(IllegalArgumentException("\"$trimmed\" is already in your list."))
        }
        val nextOrder = foodOptionDao.maxSortOrder() + 1
        foodOptionDao.insert(
            FoodOption(
                name = trimmed,
                sortOrder = nextOrder,
                isCustom = true,
                caloriesPer100g = caloriesPer100g,
                proteinPer100g = proteinPer100g,
                fatPer100g = fatPer100g,
                carbsPer100g = carbsPer100g,
                defaultWeightGrams = defaultWeightGrams,
            ),
        )
        return Result.success(Unit)
    }

    suspend fun deleteFoodOption(id: String) {
        foodOptionDao.deleteById(id)
    }

    suspend fun updateMeal(event: MealEvent, newLabel: String) {
        mealDao.update(event.copy(label = newLabel.trim()))
    }
}
