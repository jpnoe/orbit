package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxis
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.util.DateUtils
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.CheckInDao
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.MoodSample
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
    private val captures: WearableCaptureScheduler = NoWearableCapture,
    private val wakeTimes: CheckInWakeTimeSource = CheckInWakeTimeSource { null },
) {
    fun observeEnabledAxes(): Flow<List<CheckInAxis>> = dao.observeEnabledAxes()

    fun observeRecentCheckIns(limit: Int = RECENT_LIMIT): Flow<List<CheckInRecord>> =
        dao.observeRecent(limit)

    suspend fun getValuesForCheckIn(checkInId: String): List<CheckInAxisValue> =
        dao.getValuesForCheckIn(checkInId)

    fun observeAxisValues(): Flow<List<CheckInAxisValue>> = dao.observeAxisValues()

    fun observeContextWindows(): Flow<List<CheckInContextWindow>> = dao.observeContextWindows()

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

    fun observeUsageItems(checkInId: String): Flow<List<CheckInUsageItem>> =
        dao.observeUsageItems(checkInId)

    suspend fun getUsageItems(checkInId: String): List<CheckInUsageItem> =
        dao.getUsageItems(checkInId)

    fun observeMeals(checkInId: String): Flow<List<CheckInMeal>> =
        dao.observeMeals(checkInId)

    suspend fun getMeals(checkInId: String): List<CheckInMeal> =
        dao.getMeals(checkInId)

    fun observeMealFoods(checkInId: String): Flow<List<CheckInMealFoodItem>> =
        dao.observeMealFoods(checkInId)

    suspend fun getMealFoods(checkInId: String): List<CheckInMealFoodItem> =
        dao.getMealFoods(checkInId)

    suspend fun saveCheckIn(
        axisValues: List<CheckInAxisInput>,
        trigger: String,
        note: String = "",
        emotion: String = "",
        recordedAt: Long = System.currentTimeMillis(),
        localDate: String = DateUtils.formatLocalDate(DateUtils.localDateOf(recordedAt)),
        id: String = UUID.randomUUID().toString(),
        legacyMood: LegacyCheckInMood? = null,
        activitySelections: List<CheckInActivitySelection>? = null,
        workoutSelections: List<CheckInWorkoutSelection>? = null,
        usageSelections: List<CheckInUsageSelection>? = null,
        mealSelections: List<CheckInMealSelection>? = null,
        completedAt: Long? = null,
    ): SavedCheckIn {
        activitySelections?.let { CheckInActivitySelections.validate(it, contextWindowExists = true) }
        workoutSelections?.let { CheckInWorkoutSelections.validate(it, contextWindowExists = true) }
        usageSelections?.let { CheckInUsageSelections.validate(it, contextWindowExists = true) }
        mealSelections?.let { CheckInMealSelections.validate(it, contextWindowExists = true) }
        val normalizedTrigger = trigger.trim()
        require(normalizedTrigger.isNotBlank()) {
            "Check-in trigger must not be blank"
        }
        val storedEmotion = emotion.trim()
        if (axisValues.isEmpty()) {
            require(storedEmotion.isNotEmpty()) {
                "A check-in needs an axis value or an emotion"
            }
        } else {
            CheckInValidator.validate(axisValues)
        }
        val record = CheckInRecord(
            id = id,
            localDate = localDate,
            recordedAt = recordedAt,
            trigger = normalizedTrigger,
            note = note.trim(),
            emotion = storedEmotion,
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
        val moodSample = legacyMood?.toSample(record.localDate, storedEmotion)
        transaction.run {
            dao.insertCheckInWithContext(
                record = record,
                values = rows,
                window = window,
                categories = CheckInContextBoundary.defaultCategories(record.id),
            )
            if (moodSample != null) {
                moodSamples.insert(moodSample)
            }
            if (activitySelections != null) {
                saveActivitySelections(record.id, activitySelections, recordedAt)
            }
            if (workoutSelections != null) {
                saveWorkoutSelections(record.id, workoutSelections, recordedAt)
            }
            if (usageSelections != null) {
                saveUsageSelections(record.id, usageSelections, recordedAt)
            }
            if (mealSelections != null) {
                saveDietSelections(record.id, mealSelections, recordedAt)
            }
            if (completedAt != null) {
                dao.completeContextIfUnset(record.id, completedAt)
            }
        }
        if (completedAt != null) {
            scheduleIntervalCapture(record.id)
        }
        return SavedCheckIn(record = record, legacyMoodSampleId = moodSample?.id)
    }

    suspend fun previewContext(recordedAt: Long, checkInId: String): ResolvedContextWindow {
        val localDate = DateUtils.formatLocalDate(DateUtils.localDateOf(recordedAt))
        return CheckInContextBoundary.resolve(
            checkInId = checkInId,
            localDate = localDate,
            recordedAt = recordedAt,
            dayStartAt = DateUtils.atTimeMillis(DateUtils.parseLocalDate(localDate), 0, 0),
            sameDayCheckIns = dao.getCheckInsOnDate(localDate).map { existing ->
                PriorCheckIn(
                    id = existing.id,
                    localDate = existing.localDate,
                    recordedAt = existing.recordedAt,
                )
            },
            wakeTimeAt = wakeTimes.wakeTimeFor(localDate),
        )
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
        val stored = dao.completeContextIfUnset(checkInId, completedAt)
            ?: error("Context window does not exist")
        scheduleIntervalCapture(checkInId)
        return stored
    }

    /**
     * Enqueues one interval import after the completion transaction has returned.
     * A scheduler failure is logged and does not change the saved check-in.
     */
    private fun scheduleIntervalCapture(checkInId: String) {
        try {
            captures.scheduleIntervalCapture(checkInId)
        } catch (_: Exception) {
            // The check-in is already stored. Scheduling must not fail that save.
        }
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

    /**
     * Replaces contextual usage items for one check-in.
     * An empty selection is an explicit "none" and does not insert a placeholder item.
     * This does not read or write detailed substance-use history.
     */
    suspend fun saveUsageSelections(
        checkInId: String,
        selections: List<CheckInUsageSelection>,
        reportedAt: Long = System.currentTimeMillis(),
    ) {
        CheckInUsageSelections.validate(
            selections = selections,
            contextWindowExists = dao.getContextWindow(checkInId) != null,
        )
        val items = selections.map { selection ->
            CheckInUsageItem(
                checkInId = checkInId,
                substanceOptionId = selection.substanceOptionId,
                name = selection.name.trim(),
                intent = selection.intent,
                amount = selection.amount.trim(),
                reportedAt = reportedAt,
            )
        }
        val status = if (items.isEmpty()) {
            CheckInContextStatus.NONE
        } else {
            CheckInContextStatus.ENTRIES
        }
        dao.replaceUsageSelections(
            checkInId = checkInId,
            items = items,
            status = status,
            reviewedAt = reportedAt,
        )
    }

    /**
     * Replaces contextual meals for one check-in.
     * An empty list is an explicit "none" and does not insert a placeholder meal.
     * Food items are replaced with their meals. This does not read or write detailed meal history.
     */
    suspend fun saveDietSelections(
        checkInId: String,
        meals: List<CheckInMealSelection>,
        reportedAt: Long = System.currentTimeMillis(),
    ) {
        CheckInMealSelections.validate(
            meals = meals,
            contextWindowExists = dao.getContextWindow(checkInId) != null,
        )
        val mealRows = meals.map { meal ->
            CheckInMeal(
                id = meal.id,
                checkInId = checkInId,
                size = meal.size,
                sortOrder = meal.sortOrder,
                reportedAt = reportedAt,
            )
        }
        val foodRows = meals.flatMap { meal ->
            meal.foods.map { food ->
                CheckInMealFoodItem(
                    mealId = meal.id,
                    foodOptionId = food.foodOptionId,
                    foodName = food.foodName.trim(),
                    sortOrder = food.sortOrder,
                )
            }
        }
        val status = if (mealRows.isEmpty()) {
            CheckInContextStatus.NONE
        } else {
            CheckInContextStatus.ENTRIES
        }
        dao.replaceDietSelections(
            checkInId = checkInId,
            meals = mealRows,
            foods = foodRows,
            status = status,
            reviewedAt = reportedAt,
        )
    }

    companion object {
        const val RECENT_LIMIT = 50
    }
}

private fun LegacyCheckInMood.toSample(localDate: String, emotion: String) = MoodSample(
    localDate = localDate,
    moodScore = score.coerceIn(0, 10),
    note = note.trim(),
    emotion = emotion,
    recordedAt = recordedAt,
    source = source,
)

private fun List<CheckInContextCategory>.inCategoryOrder(): List<CheckInContextCategory> {
    val byCategory = associateBy { it.category }
    return CheckInContextCategories.ALL.mapNotNull { byCategory[it] }
}
