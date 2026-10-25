package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubstanceUseEventDao {
    @Insert
    suspend fun insert(event: SubstanceUseEvent)

    @Update
    suspend fun update(event: SubstanceUseEvent)

    @Query("DELETE FROM substance_use_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM substance_use_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<SubstanceUseEvent>>

    @Query(
        """
        SELECT * FROM substance_use_events
        WHERE timestamp >= :startMillis AND timestamp <= :endMillis
        ORDER BY timestamp DESC
        """,
    )
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<SubstanceUseEvent>>

    @Query(
        """
        SELECT * FROM substance_use_events
        WHERE timestamp >= :startMillis AND timestamp <= :endMillis
        ORDER BY timestamp DESC
        """,
    )
    suspend fun getBetween(startMillis: Long, endMillis: Long): List<SubstanceUseEvent>
}
