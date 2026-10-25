package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutEventDao {
    @Insert
    suspend fun insert(event: WorkoutEvent)

    @Update
    suspend fun update(event: WorkoutEvent)

    @Query("DELETE FROM workout_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM workout_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkoutEvent?

    @Query("SELECT * FROM workout_events WHERE endTime IS NULL ORDER BY startTime DESC")
    fun observeActive(): Flow<List<WorkoutEvent>>

    @Query("SELECT * FROM workout_events WHERE endTime IS NULL ORDER BY startTime DESC")
    suspend fun getActive(): List<WorkoutEvent>

    @Query("SELECT * FROM workout_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<WorkoutEvent>>

    @Query(
        """
        SELECT * FROM workout_events
        WHERE COALESCE(endTime, startTime) >= :startMillis
          AND COALESCE(endTime, startTime) <= :endMillis
        ORDER BY COALESCE(endTime, startTime) DESC
        """,
    )
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<WorkoutEvent>>

    @Query(
        """
        SELECT * FROM workout_events
        WHERE COALESCE(endTime, startTime) >= :startMillis
          AND COALESCE(endTime, startTime) <= :endMillis
        ORDER BY COALESCE(endTime, startTime) DESC
        """,
    )
    suspend fun getBetween(startMillis: Long, endMillis: Long): List<WorkoutEvent>
}
