package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityEventDao {
    @Insert
    suspend fun insert(event: ActivityEvent)

    @Update
    suspend fun update(event: ActivityEvent)

    @Query("DELETE FROM activity_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM activity_events WHERE endTime IS NULL ORDER BY startTime DESC")
    fun observeActive(): Flow<List<ActivityEvent>>

    @Query("SELECT * FROM activity_events WHERE endTime IS NULL ORDER BY startTime DESC")
    suspend fun getActive(): List<ActivityEvent>

    @Query("SELECT * FROM activity_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ActivityEvent?

    @Query("SELECT * FROM activity_events ORDER BY COALESCE(endTime, startTime) DESC")
    fun observeAll(): Flow<List<ActivityEvent>>

    @Query(
        """
        SELECT * FROM activity_events
        WHERE COALESCE(endTime, startTime) >= :startMillis
          AND COALESCE(endTime, startTime) <= :endMillis
        ORDER BY COALESCE(endTime, startTime) DESC
        """,
    )
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<ActivityEvent>>

    @Query(
        """
        SELECT * FROM activity_events
        WHERE COALESCE(endTime, startTime) >= :startMillis
          AND COALESCE(endTime, startTime) <= :endMillis
        ORDER BY COALESCE(endTime, startTime) DESC
        """,
    )
    suspend fun getBetween(startMillis: Long, endMillis: Long): List<ActivityEvent>
}
