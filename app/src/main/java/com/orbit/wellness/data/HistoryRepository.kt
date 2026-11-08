package com.orbit.wellness.data

import com.orbit.wellness.data.local.ActivityEvent
import com.orbit.wellness.data.local.ActivityEventDao
import com.orbit.wellness.data.local.CheckInDao
import com.orbit.wellness.data.local.MealEvent
import com.orbit.wellness.data.local.MealEventDao
import com.orbit.wellness.data.local.MealItem
import com.orbit.wellness.data.local.MealItemDao
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.data.local.MoodSampleDao
import com.orbit.wellness.data.local.SleepEvent
import com.orbit.wellness.data.local.SleepEventDao
import com.orbit.wellness.data.local.SubstanceUseEvent
import com.orbit.wellness.data.local.SubstanceUseEventDao
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableObservationDao
import com.orbit.wellness.data.local.WorkoutEvent
import com.orbit.wellness.data.local.WorkoutEventDao
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

sealed class HistoryItem {
    abstract val timestamp: Long
    abstract val id: String

    data class Mood(
        val sample: MoodSample,
    ) : HistoryItem() {
        override val timestamp: Long get() = sample.recordedAt
        override val id: String get() = "mood-${sample.id}"
    }

    data class Activity(
        val event: ActivityEvent,
    ) : HistoryItem() {
        override val timestamp: Long get() = event.effectiveAt
        override val id: String get() = "activity-${event.id}"
    }

    data class Meal(
        val event: MealEvent,
        val items: List<MealItem> = emptyList(),
    ) : HistoryItem() {
        override val timestamp: Long get() = event.timestamp
        override val id: String get() = "meal-${event.id}"
    }

    data class Workout(
        val event: WorkoutEvent,
    ) : HistoryItem() {
        override val timestamp: Long get() = event.effectiveAt
        override val id: String get() = "workout-${event.id}"
    }

    data class SubstanceUse(
        val event: SubstanceUseEvent,
    ) : HistoryItem() {
        override val timestamp: Long get() = event.timestamp
        override val id: String get() = "use-${event.id}"
    }

    data class Sleep(
        val event: SleepEvent,
    ) : HistoryItem() {
        override val timestamp: Long get() = event.wakeTime
        override val id: String get() = "sleep-${event.id}"
    }

    /**
     * Contextual reports for one check-in, all dated at that mood log.
     * [windowStartAt] is the previous mood log or the reported wake time.
     * A day-start basis means no wake was reported, so the range begins at "Wakeup".
     */
    data class CheckInInterval(
        val checkInId: String,
        val recordedAt: Long,
        val startBasis: String,
        val windowStartAt: Long?,
        val moodScore: Int?,
        val energyScore: Int?,
        val confidenceScore: Int?,
        val anxietyScore: Int?,
        val emotion: String,
        val note: String,
        val activities: List<String>,
        val workouts: List<String>,
        val meals: List<String>,
        val usage: List<String>,
        val moodSampleId: String?,
        /** Wearable rows contained in this interval. Not part of the check-in state. */
        val wearables: List<WearableObservation> = emptyList(),
    ) : HistoryItem() {
        override val timestamp: Long get() = recordedAt
        override val id: String get() = "check-in-$checkInId"
    }
}

class HistoryRepository(
    private val moodSampleDao: MoodSampleDao,
    private val activityEventDao: ActivityEventDao,
    private val mealEventDao: MealEventDao,
    private val mealItemDao: MealItemDao,
    private val workoutEventDao: WorkoutEventDao,
    private val substanceUseEventDao: SubstanceUseEventDao,
    private val sleepEventDao: SleepEventDao,
    private val checkInDao: CheckInDao,
    private val wearableObservationDao: WearableObservationDao,
) {
    fun observeRecentHistory(daysBack: Long = 14): Flow<List<HistoryItem>> {
        val today = LocalDate.now()
        val start = today.minusDays(daysBack)
        val end = today.plusDays(daysBack)
        val startMillis = DateUtils.startOfDayMillis(start)
        val endMillis = DateUtils.endOfDayMillis(end)
        val startDate = DateUtils.formatLocalDate(start)
        val endDate = DateUtils.formatLocalDate(end)

        val mealsFlow = combine(
            mealEventDao.observeBetween(startMillis, endMillis),
            mealItemDao.observeAll(),
        ) { meals, allItems ->
            val itemsByMeal = allItems.groupBy { it.mealId }
            meals.map { meal ->
                HistoryItem.Meal(
                    event = meal,
                    items = itemsByMeal[meal.id].orEmpty().sortedBy { it.sortOrder },
                )
            }
        }

        val moodsFlow = moodSampleDao.observeBetween(startDate, endDate)
        val intervalsFlow = observeCheckInIntervals(startMillis, endMillis, moodsFlow)
        val detailedFlow = combine(
            activityEventDao.observeBetween(startMillis, endMillis),
            mealsFlow,
            workoutEventDao.observeBetween(startMillis, endMillis),
            substanceUseEventDao.observeBetween(startMillis, endMillis),
        ) { activities, meals, workouts, uses ->
            DetailedHistory(activities, meals, workouts, uses)
        }
        val coreFlow = combine(
            moodsFlow,
            intervalsFlow,
            detailedFlow,
        ) { moods, intervals, detailed ->
            val absorbed = CheckInIntervalHistory.absorbedMoodIds(intervals)
            buildList {
                addAll(intervals)
                moods.filter {
                    it.id !in absorbed &&
                        it.source != MoodSample.SOURCE_ACTIVITY_PRE &&
                        it.source != MoodSample.SOURCE_ACTIVITY_POST &&
                        it.source != MoodSample.SOURCE_ACTIVITY_LOG &&
                        it.source != MoodSample.SOURCE_WORKOUT_PRE &&
                        it.source != MoodSample.SOURCE_WORKOUT_POST &&
                        it.source != MoodSample.SOURCE_WORKOUT_LOG
                }.forEach { add(HistoryItem.Mood(it)) }
                detailed.activities.forEach { add(HistoryItem.Activity(it)) }
                addAll(detailed.meals)
                detailed.workouts.forEach { add(HistoryItem.Workout(it)) }
                detailed.uses.forEach { add(HistoryItem.SubstanceUse(it)) }
            }
        }

        return combine(
            coreFlow,
            sleepEventDao.observeBetween(startDate, endDate),
        ) { core, sleeps ->
            (core + sleeps.map { HistoryItem.Sleep(it) })
                .sortedByDescending { it.timestamp }
        }
    }

    suspend fun deleteItem(item: HistoryItem) {
        when (item) {
            is HistoryItem.Mood -> moodSampleDao.deleteById(item.sample.id)
            is HistoryItem.Activity -> activityEventDao.deleteById(item.event.id)
            is HistoryItem.Meal -> mealEventDao.deleteById(item.event.id)
            is HistoryItem.Workout -> workoutEventDao.deleteById(item.event.id)
            is HistoryItem.SubstanceUse -> substanceUseEventDao.deleteById(item.event.id)
            is HistoryItem.Sleep -> sleepEventDao.deleteById(item.event.id)
            is HistoryItem.CheckInInterval -> Unit
        }
    }

    suspend fun updateMood(sample: MoodSample, newScore: Int, newNote: String) {
        moodSampleDao.update(
            sample.copy(
                moodScore = newScore.coerceIn(0, 10),
                note = newNote.trim(),
            ),
        )
    }

    suspend fun updateActivity(event: ActivityEvent, newPreMood: Int?, newPostMood: Int?) {
        activityEventDao.update(
            event.copy(
                preMood = newPreMood?.coerceIn(0, 10),
                postMood = newPostMood?.coerceIn(0, 10),
            ),
        )
    }

    suspend fun updateMeal(event: MealEvent, newLabel: String) {
        mealEventDao.update(event.copy(label = newLabel.trim()))
    }

    suspend fun updateWorkout(event: WorkoutEvent, newDurationMinutes: Int, newNotes: String) {
        workoutEventDao.update(
            event.copy(
                durationMinutes = newDurationMinutes.coerceAtLeast(1),
                notes = newNotes.trim(),
            ),
        )
    }

    suspend fun updateSubstanceUse(
        event: SubstanceUseEvent,
        name: String,
        amount: String,
        moodScore: Int,
        intent: String,
    ) {
        substanceUseEventDao.update(
            event.copy(
                name = name.trim(),
                amount = amount.trim(),
                moodScore = moodScore.coerceIn(0, 10),
                intent = intent,
            ),
        )
    }

    suspend fun updateSleep(
        event: SleepEvent,
        bedtime: Long,
        wakeTime: Long,
        sleepQuality: Int,
    ) {
        require(wakeTime > bedtime) { "Wake time must be after bedtime." }
        sleepEventDao.update(
            event.copy(
                bedtime = bedtime,
                wakeTime = wakeTime,
                sleepQuality = sleepQuality.coerceIn(0, 10),
            ),
        )
    }

    private fun observeCheckInIntervals(
        startMillis: Long,
        endMillis: Long,
        moods: Flow<List<MoodSample>>,
    ): Flow<List<HistoryItem.CheckInInterval>> {
        val recordsFlow = combine(
            checkInDao.observeCheckInsBetween(startMillis, endMillis),
            checkInDao.observeContextWindows(),
            checkInDao.observeActivityItems(),
            checkInDao.observeAxisValues(),
        ) { checkIns, windows, activities, axisValues ->
            IntervalRecords(checkIns, windows, activities, axisValues)
        }
        val detailsFlow = combine(
            checkInDao.observeWorkoutItems(),
            checkInDao.observeUsageItems(),
            checkInDao.observeMeals(),
            checkInDao.observeMealFoods(),
        ) { workouts, usage, meals, foods ->
            IntervalDetails(workouts, usage, meals, foods)
        }
        return combine(
            recordsFlow,
            detailsFlow,
            moods,
            wearableObservationDao.observeAll(),
        ) { records, details, moodSamples, wearables ->
            CheckInIntervalHistory.build(
                checkIns = records.checkIns,
                windows = records.windows,
                activities = records.activities,
                axisValues = records.axisValues,
                workouts = details.workouts,
                usage = details.usage,
                meals = details.meals,
                foods = details.foods,
                moods = moodSamples,
            ).map { interval ->
                interval.copy(
                    wearables = CheckInIntervalWearables.forInterval(
                        windowStartAt = interval.windowStartAt,
                        windowEndAt = interval.recordedAt,
                        observations = wearables,
                    ),
                )
            }
        }
    }
}

private data class DetailedHistory(
    val activities: List<com.orbit.wellness.data.local.ActivityEvent>,
    val meals: List<HistoryItem.Meal>,
    val workouts: List<com.orbit.wellness.data.local.WorkoutEvent>,
    val uses: List<com.orbit.wellness.data.local.SubstanceUseEvent>,
)

private data class IntervalRecords(
    val checkIns: List<com.orbit.wellness.data.local.CheckInRecord>,
    val windows: List<com.orbit.wellness.data.local.CheckInContextWindow>,
    val activities: List<com.orbit.wellness.data.local.CheckInActivityItem>,
    val axisValues: List<com.orbit.wellness.data.local.CheckInAxisValue>,
)

private data class IntervalDetails(
    val workouts: List<com.orbit.wellness.data.local.CheckInWorkoutItem>,
    val usage: List<com.orbit.wellness.data.local.CheckInUsageItem>,
    val meals: List<com.orbit.wellness.data.local.CheckInMeal>,
    val foods: List<com.orbit.wellness.data.local.CheckInMealFoodItem>,
)
