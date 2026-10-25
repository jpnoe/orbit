package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShareEventDao {
    @Insert
    suspend fun insert(event: ShareEvent)

    @Query("SELECT * FROM share_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ShareEvent>>
}
