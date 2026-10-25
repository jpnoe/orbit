package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyJournalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(journal: DailyJournal)

    @Update
    suspend fun update(journal: DailyJournal)

    @Query("SELECT * FROM daily_journals WHERE localDate = :localDate LIMIT 1")
    suspend fun getByDate(localDate: String): DailyJournal?

    @Query("SELECT * FROM daily_journals WHERE localDate = :localDate LIMIT 1")
    fun observeByDate(localDate: String): Flow<DailyJournal?>

    @Query("SELECT * FROM daily_journals ORDER BY localDate DESC")
    fun observeAllDescending(): Flow<List<DailyJournal>>

    @Query("DELETE FROM daily_journals WHERE localDate = :localDate")
    suspend fun deleteByDate(localDate: String)
}
