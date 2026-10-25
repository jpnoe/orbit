package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubstanceOptionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(options: List<SubstanceOption>)

    @Insert
    suspend fun insert(option: SubstanceOption)

    @Query("DELETE FROM substance_options WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM substance_options ORDER BY intent ASC, sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<SubstanceOption>>

    @Query("SELECT * FROM substance_options WHERE intent = :intent ORDER BY sortOrder ASC, name ASC")
    fun observeByIntent(intent: String): Flow<List<SubstanceOption>>

    @Query("SELECT COUNT(*) FROM substance_options")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM substance_options WHERE intent = :intent")
    suspend fun maxSortOrder(intent: String): Int
}
