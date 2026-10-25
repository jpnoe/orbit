package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityOptionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(options: List<ActivityOption>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(option: ActivityOption)

    @Query("DELETE FROM activity_options WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM activity_options ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<ActivityOption>>

    @Query("SELECT COUNT(*) FROM activity_options")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM activity_options WHERE LOWER(name) = LOWER(:name)")
    suspend fun countByName(name: String): Int

    @Query("SELECT MAX(sortOrder) FROM activity_options")
    suspend fun maxSortOrder(): Int?
}
