package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StateVectorSnapshotDao {
    @Insert
    suspend fun insert(snapshot: StateVectorSnapshot)

    @Insert
    suspend fun insertAll(snapshots: List<StateVectorSnapshot>)

    @Query("DELETE FROM state_vector_snapshots WHERE id LIKE 'synthetic-%'")
    suspend fun deleteSynthetic()

    @Query(
        """
        SELECT * FROM state_vector_snapshots
        ORDER BY recordedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatest(): StateVectorSnapshot?

    @Query(
        """
        SELECT * FROM state_vector_snapshots
        ORDER BY recordedAt DESC
        LIMIT 1
        """,
    )
    fun observeLatest(): Flow<StateVectorSnapshot?>

    @Query("SELECT COUNT(*) FROM state_vector_snapshots")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM state_vector_snapshots
        WHERE recordedAt >= :sinceMillis
        ORDER BY recordedAt ASC
        """,
    )
    suspend fun getSince(sinceMillis: Long): List<StateVectorSnapshot>
}
