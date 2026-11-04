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

    /**
     * Newest measurement, then newest import, then id. This matches
     * [com.orbit.wellness.data.local.mostRecent] and does not delete older rows.
     */
    @Query(
        """
        SELECT * FROM wearable_observations
        ORDER BY observedStartAt DESC, importedAt DESC, id ASC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<WearableObservation>>
}
