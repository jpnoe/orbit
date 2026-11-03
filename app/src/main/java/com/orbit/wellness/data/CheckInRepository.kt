package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxis
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.CheckInDao
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

fun interface CheckInWakeTimeSource {
    suspend fun wakeTimeFor(localDate: String): Long?
}

class CheckInRepository(
    private val dao: CheckInDao,
    private val moodSamples: MoodSampleWriter = MoodSampleWriter {
        error("Mood sample writer is not configured")
    },
    private val moodRemover: MoodSampleRemover = MoodSampleRemover { },
    private val transaction: DatabaseTransaction = ImmediateDatabaseTransaction(),
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

    fun observeWorkoutItems(checkInId: String): Flow<List<CheckInWorkoutItem>> =
        dao.observeWorkoutItems(checkInId)

    suspend fun getWorkoutItems(checkInId: String): List<CheckInWorkoutItem> =
        dao.getWorkoutItems(checkInId)

    suspend fun saveCheckIn(
        axisValues: List<CheckInAxisInput>,
        trigger: String,
        note: String = "",
        recordedAt: Long = System.currentTimeMillis(),
        localDate: String = DateUtils.formatLocalDate(DateUtils.localDateOf(recordedAt)),
        id: String = UUID.randomUUID().toString(),
        legacyMood: LegacyCheckInMood? = null,
    ): SavedCheckIn {
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
        val moodSample = legacyMood?.toSample(record.localDate)
        transaction.run {
            dao.insertCheckInWithContext(
                record = record,
                values = rows,
                window = window,
                categories = CheckInContextBoundary.unreviewedCategories(record.id),
            )
            if (moodSample != null) {
                moodSamples.insert(moodSample)
            }
        }
        return SavedCheckIn(record = record, legacyMoodSampleId = moodSample?.id)
    }

    /**
     * Removes one check-in and its context rows, plus the legacy mood sample from that same submission.
     */
    suspend fun discardCheckIn(checkInId: String, legacyMoodSampleId: String?) {
        require(checkInId.isNotBlank()) { "Check-in id cannot be blank" }
        transaction.run {
            dao.deleteCheckIn(checkInId)
            if (legacyMoodSampleId != null) {
                moodRemover.delete(legacyMoodSampleId)
            }
        }
    }

    /**
     * Marks this context step finished. A second call keeps the original [completedAt].
     * Category rows stay as they are. A missing window fails instead of reporting success.
     */
    suspend fun completeContext(checkInId: String, completedAt: Long = System.currentTimeMillis()): Long {
        require(checkInId.isNotBlank()) { "Check-in id cannot be blank" }
        return dao.completeContextIfUnset(checkInId, completedAt)
            ?: error("Context window does not exist")
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

    /**
     * Replaces contextual workout items for one check-in.
     * An empty selection is an explicit "none" and does not insert a placeholder item.
     * This does not read or write detailed workout-event history.
     */
    suspend fun saveWorkoutSelections(
        checkInId: String,
        selections: List<CheckInWorkoutSelection>,
        reportedAt: Long = System.currentTimeMillis(),
    ) {
        CheckInWorkoutSelections.validate(
            selections = selections,
            contextWindowExists = dao.getContextWindow(checkInId) != null,
        )
        val items = selections.map { selection ->
            CheckInWorkoutItem(
                checkInId = checkInId,
                workoutOptionId = selection.workoutOptionId,
                workoutName = selection.workoutName.trim(),
                durationMinutes = selection.durationMinutes,
                reportedAt = reportedAt,
            )
        }
        val status = if (items.isEmpty()) {
            CheckInContextStatus.NONE
        } else {
            CheckInContextStatus.ENTRIES
        }
        dao.replaceWorkoutSelections(
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

private fun LegacyCheckInMood.toSample(localDate: String) = MoodSample(
    localDate = localDate,
    moodScore = score.coerceIn(0, 10),
    note = note.trim(),
    emotion = emotion.trim(),
    recordedAt = recordedAt,
    source = source,
)

private fun List<CheckInContextCategory>.inCategoryOrder(): List<CheckInContextCategory> {
    val byCategory = associateBy { it.category }
    return CheckInContextCategories.ALL.mapNotNull { byCategory[it] }
}
