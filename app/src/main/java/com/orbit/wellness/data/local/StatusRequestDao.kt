package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StatusRequestDao {
    @Insert
    suspend fun insert(request: StatusRequest)

    @Update
    suspend fun update(request: StatusRequest)

    @Query("SELECT * FROM status_requests WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): StatusRequest?

    @Query(
        """
        SELECT * FROM status_requests
        WHERE resolved = 0
        ORDER BY timestamp DESC
        """,
    )
    fun observePending(): Flow<List<StatusRequest>>

    @Query(
        """
        SELECT * FROM status_requests
        WHERE resolved = 0
        ORDER BY timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestPending(): StatusRequest?
}
