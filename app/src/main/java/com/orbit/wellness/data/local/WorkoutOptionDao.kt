package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutOptionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(options: List<WorkoutOption>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(option: WorkoutOption)

    @Query("DELETE FROM workout_options WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM workout_options ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<WorkoutOption>>

    @Query("SELECT COUNT(*) FROM workout_options")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM workout_options WHERE LOWER(name) = LOWER(:name)")
    suspend fun countByName(name: String): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM workout_options")
    suspend fun maxSortOrder(): Int
}
