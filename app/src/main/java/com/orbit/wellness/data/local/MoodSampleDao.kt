package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MoodSampleDao {
    @Insert
    suspend fun insert(sample: MoodSample)

    @Update
    suspend fun update(sample: MoodSample)

    @Query("DELETE FROM mood_samples WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        SELECT * FROM mood_samples
        WHERE localDate >= :startDate AND localDate <= :endDate
        ORDER BY recordedAt ASC
        """,
    )
    fun observeBetween(startDate: String, endDate: String): Flow<List<MoodSample>>

    @Query(
        """
        SELECT * FROM mood_samples
        WHERE localDate = :localDate
        ORDER BY recordedAt DESC
        """,
    )
    fun observeForDate(localDate: String): Flow<List<MoodSample>>

    @Query(
        """
        SELECT * FROM mood_samples
        WHERE localDate = :localDate
        ORDER BY recordedAt DESC
        """,
    )
    suspend fun getForDate(localDate: String): List<MoodSample>

    @Query(
        """
        SELECT * FROM mood_samples
        ORDER BY recordedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatest(): MoodSample?

    @Query(
        """
        SELECT * FROM mood_samples
        WHERE recordedAt <= :atMillis
        ORDER BY recordedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestAtOrBefore(atMillis: Long): MoodSample?

    @Query(
        """
        SELECT * FROM mood_samples
        WHERE recordedAt >= :startMillis AND recordedAt <= :endMillis
        ORDER BY recordedAt ASC
        """,
    )
    suspend fun getBetween(startMillis: Long, endMillis: Long): List<MoodSample>
}
