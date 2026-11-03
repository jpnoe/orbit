package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxis
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInDao
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

fun interface CheckInWakeTimeSource {
    suspend fun wakeTimeFor(localDate: String): Long?
}

class CheckInRepository(
    private val dao: CheckInDao,
    private val wakeTimes: CheckInWakeTimeSource = CheckInWakeTimeSource { null },
) {
    fun observeEnabledAxes(): Flow<List<CheckInAxis>> = dao.observeEnabledAxes()

    fun observeRecentCheckIns(limit: Int = RECENT_LIMIT): Flow<List<CheckInRecord>> =
        dao.observeRecent(limit)

    suspend fun getValuesForCheckIn(checkInId: String): List<CheckInAxisValue> =
        dao.getValuesForCheckIn(checkInId)

    suspend fun getLatestCheckIn(): CheckInRecord? = dao.getLatest()

    fun observeContextWindow(checkInId: String): Flow<CheckInContextWindow?> =
        dao.observeContextWindow(checkInId)

    suspend fun getContextWindow(checkInId: String): CheckInContextWindow? =
        dao.getContextWindow(checkInId)

    fun observeContextCategories(checkInId: String): Flow<List<CheckInContextCategory>> =
        dao.observeContextCategories(checkInId).map { rows -> rows.inCategoryOrder() }

    suspend fun getContextCategories(checkInId: String): List<CheckInContextCategory> =
        dao.getContextCategories(checkInId).inCategoryOrder()

    fun observeActivityItems(checkInId: String): Flow<List<CheckInActivityItem>> =
        dao.observeActivityItems(checkInId)

    suspend fun getActivityItems(checkInId: String): List<CheckInActivityItem> =
        dao.getActivityItems(checkInId)

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
        val boundary = CheckInContextBoundary.resolve(
            checkInId = record.id,
            localDate = record.localDate,
            recordedAt = record.recordedAt,
            dayStartAt = DateUtils.atTimeMillis(DateUtils.parseLocalDate(record.localDate), 0, 0),
            sameDayCheckIns = dao.getCheckInsOnDate(record.localDate).map { existing ->
                PriorCheckIn(
                    id = existing.id,
                    localDate = existing.localDate,
                    recordedAt = existing.recordedAt,
                )
            },
            wakeTimeAt = wakeTimes.wakeTimeFor(record.localDate),
        )
        val window = CheckInContextWindow(
            checkInId = record.id,
            previousCheckInId = boundary.previousCheckInId,
            windowStartAt = boundary.windowStartAt,
            windowEndAt = boundary.windowEndAt,
            startBasis = boundary.startBasis,
        )
        dao.insertCheckInWithContext(
            record = record,
            values = rows,
            window = window,
            categories = CheckInContextBoundary.unreviewedCategories(record.id),
        )
        return record
    }

    /**
     * Replaces contextual activity items for one check-in.
     * An empty selection is an explicit "none" and does not insert a placeholder item.
     * This does not read or write detailed activity-event history.
     */
    suspend fun saveActivitySelections(
        checkInId: String,
        selections: List<CheckInActivitySelection>,
        reportedAt: Long = System.currentTimeMillis(),
    ) {
        CheckInActivitySelections.validate(
            selections = selections,
            contextWindowExists = dao.getContextWindow(checkInId) != null,
        )
        val items = selections.map { selection ->
            CheckInActivityItem(
                checkInId = checkInId,
                activityOptionId = selection.activityOptionId,
                activityName = selection.activityName.trim(),
                reportedAt = reportedAt,
            )
        }
        val status = if (items.isEmpty()) {
            CheckInContextStatus.NONE
        } else {
            CheckInContextStatus.ENTRIES
        }
        dao.replaceActivitySelections(
            checkInId = checkInId,
            items = items,
            status = status,
            reviewedAt = reportedAt,
        )
    }

    companion object {
        const val RECENT_LIMIT = 50
    }
}

private fun List<CheckInContextCategory>.inCategoryOrder(): List<CheckInContextCategory> {
    val byCategory = associateBy { it.category }
    return CheckInContextCategories.ALL.mapNotNull { byCategory[it] }
}
