package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: SleepEvent)

    @Update
    suspend fun update(event: SleepEvent)

    @Query("DELETE FROM sleep_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM sleep_events WHERE localDate = :localDate LIMIT 1")
    fun observeForDate(localDate: String): Flow<SleepEvent?>

    @Query("SELECT * FROM sleep_events WHERE localDate = :localDate LIMIT 1")
    suspend fun getForDate(localDate: String): SleepEvent?

    @Query(
        """
        SELECT * FROM sleep_events
        WHERE localDate >= :startDate AND localDate <= :endDate
        ORDER BY wakeTime DESC
        """,
    )
    fun observeBetween(startDate: String, endDate: String): Flow<List<SleepEvent>>

    @Query(
        """
        SELECT * FROM sleep_events
        ORDER BY wakeTime DESC
        LIMIT 1
        """,
    )
    suspend fun getLatest(): SleepEvent?
}
