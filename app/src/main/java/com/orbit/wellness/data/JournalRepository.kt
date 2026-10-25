package com.orbit.wellness.data

import com.orbit.wellness.data.local.DailyJournal
import com.orbit.wellness.data.local.DailyJournalDao
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class JournalRepository(
    private val dao: DailyJournalDao,
) {
    fun observeToday(): Flow<DailyJournal?> {
        return dao.observeByDate(DateUtils.formatLocalDate(LocalDate.now()))
    }

    fun observeAllDescending(): Flow<List<DailyJournal>> = dao.observeAllDescending()

    suspend fun getToday(): DailyJournal? {
        return dao.getByDate(DateUtils.formatLocalDate(LocalDate.now()))
    }

    suspend fun saveToday(note: String) {
        saveForDate(DateUtils.formatLocalDate(LocalDate.now()), note)
    }

    suspend fun saveForDate(localDate: String, note: String) {
        val now = System.currentTimeMillis()
        val existing = dao.getByDate(localDate)
        val trimmed = note.trim()

        if (trimmed.isEmpty()) {
            if (existing != null) {
                dao.deleteByDate(localDate)
            }
            return
        }

        if (existing != null) {
            dao.update(
                existing.copy(
                    note = trimmed,
                    updatedAt = now,
                ),
            )
        } else {
            dao.upsert(
                DailyJournal(
                    localDate = localDate,
                    note = trimmed,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun deleteByDate(localDate: String) {
        dao.deleteByDate(localDate)
    }
}
