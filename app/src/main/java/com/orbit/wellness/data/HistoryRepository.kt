package com.orbit.wellness.data

import com.orbit.wellness.data.local.ActivityEvent
import com.orbit.wellness.data.local.ActivityEventDao
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
}

class HistoryRepository(
    private val moodSampleDao: MoodSampleDao,
    private val activityEventDao: ActivityEventDao,
    private val mealEventDao: MealEventDao,
    private val mealItemDao: MealItemDao,
    private val workoutEventDao: WorkoutEventDao,
    private val substanceUseEventDao: SubstanceUseEventDao,
    private val sleepEventDao: SleepEventDao,
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

        val coreFlow = combine(
            moodSampleDao.observeBetween(startDate, endDate),
            activityEventDao.observeBetween(startMillis, endMillis),
            mealsFlow,
            workoutEventDao.observeBetween(startMillis, endMillis),
            substanceUseEventDao.observeBetween(startMillis, endMillis),
        ) { moods, activities, meals, workouts, uses ->
            buildList {
                moods.filter {
                    it.source != MoodSample.SOURCE_ACTIVITY_PRE &&
                        it.source != MoodSample.SOURCE_ACTIVITY_POST &&
                        it.source != MoodSample.SOURCE_ACTIVITY_LOG &&
                        it.source != MoodSample.SOURCE_WORKOUT_PRE &&
                        it.source != MoodSample.SOURCE_WORKOUT_POST &&
                        it.source != MoodSample.SOURCE_WORKOUT_LOG
                }.forEach { add(HistoryItem.Mood(it)) }
                activities.forEach { add(HistoryItem.Activity(it)) }
                addAll(meals)
                workouts.forEach { add(HistoryItem.Workout(it)) }
                uses.forEach { add(HistoryItem.SubstanceUse(it)) }
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
}
