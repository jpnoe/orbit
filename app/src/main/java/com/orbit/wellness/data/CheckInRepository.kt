package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInAxis
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInDao
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class CheckInRepository(
    private val dao: CheckInDao,
) {
    fun observeEnabledAxes(): Flow<List<CheckInAxis>> = dao.observeEnabledAxes()

    fun observeRecentCheckIns(limit: Int = RECENT_LIMIT): Flow<List<CheckInRecord>> =
        dao.observeRecent(limit)

    suspend fun getValuesForCheckIn(checkInId: String): List<CheckInAxisValue> =
        dao.getValuesForCheckIn(checkInId)

    suspend fun getLatestCheckIn(): CheckInRecord? = dao.getLatest()

    suspend fun saveCheckIn(
        axisValues: List<CheckInAxisInput>,
        trigger: String,
        note: String = "",
        recordedAt: Long = System.currentTimeMillis(),
        localDate: String = DateUtils.formatLocalDate(DateUtils.localDateOf(recordedAt)),
        id: String = UUID.randomUUID().toString(),
    ): CheckInRecord {
        CheckInValidator.validate(axisValues)
        val normalizedTrigger = trigger.trim()
        require(normalizedTrigger.isNotBlank()) {
            "Check-in trigger must not be blank"
        }
        val record = CheckInRecord(
            id = id,
            localDate = localDate,
            recordedAt = recordedAt,
            trigger = normalizedTrigger,
            note = note.trim(),
        )
        val rows = axisValues.map { input ->
            CheckInAxisValue(
                checkInId = record.id,
                axisId = input.axisId,
                value = input.value,
            )
        }
        dao.insertCheckInWithValues(record, rows)
        return record
    }

    companion object {
        const val RECENT_LIMIT = 50
    }
}
