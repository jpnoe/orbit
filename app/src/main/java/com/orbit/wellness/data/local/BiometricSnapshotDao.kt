package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BiometricSnapshotDao {
    @Insert
    suspend fun insert(snapshot: BiometricSnapshot)

    @Query(
        """
        SELECT * FROM biometric_snapshots
        ORDER BY syncedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatest(): BiometricSnapshot?

    @Query(
        """
        SELECT * FROM biometric_snapshots
        ORDER BY syncedAt DESC
        LIMIT 1
        """,
    )
    fun observeLatest(): Flow<BiometricSnapshot?>
}
