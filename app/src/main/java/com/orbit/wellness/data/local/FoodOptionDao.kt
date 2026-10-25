package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodOptionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(options: List<FoodOption>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(option: FoodOption)

    @Query("DELETE FROM food_options WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM food_options ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<FoodOption>>

    @Query("SELECT COUNT(*) FROM food_options")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM food_options WHERE LOWER(name) = LOWER(:name)")
    suspend fun countByName(name: String): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM food_options")
    suspend fun maxSortOrder(): Int
}
