package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MealItemDao {
    @Insert
    suspend fun insertAll(items: List<MealItem>)

    @Query("SELECT * FROM meal_items WHERE mealId = :mealId ORDER BY sortOrder ASC")
    suspend fun getForMeal(mealId: String): List<MealItem>

    @Query("SELECT * FROM meal_items WHERE mealId = :mealId ORDER BY sortOrder ASC")
    fun observeForMeal(mealId: String): Flow<List<MealItem>>

    @Query("SELECT * FROM meal_items ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<MealItem>>

    @Query("DELETE FROM meal_items WHERE mealId = :mealId")
    suspend fun deleteForMeal(mealId: String)
}
