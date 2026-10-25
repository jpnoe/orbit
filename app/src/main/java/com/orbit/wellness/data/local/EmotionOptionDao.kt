package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EmotionOptionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(options: List<EmotionOption>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(option: EmotionOption)

    @Query("DELETE FROM emotion_options WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM emotion_options ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<EmotionOption>>

    @Query("SELECT COUNT(*) FROM emotion_options")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM emotion_options WHERE LOWER(name) = LOWER(:name)")
    suspend fun countByName(name: String): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM emotion_options")
    suspend fun maxSortOrder(): Int
}
