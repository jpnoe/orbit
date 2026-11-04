package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WearableObservationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(observations: List<WearableObservation>)

    @Query(
        """
        SELECT * FROM wearable_observations
        ORDER BY observedStartAt ASC, id ASC
        """,
    )
    fun observeAll(): Flow<List<WearableObservation>>
}
