package com.orbit.wellness.data

import com.orbit.wellness.data.local.SleepEvent
import com.orbit.wellness.data.local.SleepEventDao
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class SleepRepository(
    private val dao: SleepEventDao,
) {
    fun observeToday(): Flow<SleepEvent?> {
        return dao.observeForDate(DateUtils.formatLocalDate(LocalDate.now()))
    }

    fun observeBetween(startDate: String, endDate: String): Flow<List<SleepEvent>> {
        return dao.observeBetween(startDate, endDate)
    }

    suspend fun logSleep(
        bedtime: Long,
        wakeTime: Long,
        sleepQuality: Int,
        localDate: LocalDate = LocalDate.now(),
    ) {
        require(wakeTime > bedtime) { "Wake time must be after bedtime." }
        val dateStr = DateUtils.formatLocalDate(localDate)
        val quality = sleepQuality.coerceIn(0, 10)
        val existing = dao.getForDate(dateStr)
        if (existing != null) {
            dao.update(
                existing.copy(
                    bedtime = bedtime,
                    wakeTime = wakeTime,
                    sleepQuality = quality,
                ),
            )
        } else {
            dao.insert(
                SleepEvent(
                    localDate = dateStr,
                    bedtime = bedtime,
                    wakeTime = wakeTime,
                    sleepQuality = quality,
                ),
            )
        }
    }
}
