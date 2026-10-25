package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MealEventDao {
    @Insert
    suspend fun insert(event: MealEvent)

    @Update
    suspend fun update(event: MealEvent)

    @Query("DELETE FROM meal_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM meal_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MealEvent>>

    @Query("SELECT * FROM meal_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MealEvent?

    @Query(
        """
        SELECT * FROM meal_events
        WHERE timestamp >= :startMillis AND timestamp <= :endMillis
        ORDER BY timestamp DESC
        """,
    )
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<MealEvent>>

    @Query(
        """
        SELECT * FROM meal_events
        WHERE timestamp >= :startMillis AND timestamp <= :endMillis
        ORDER BY timestamp DESC
        """,
    )
    suspend fun getBetween(startMillis: Long, endMillis: Long): List<MealEvent>
}
