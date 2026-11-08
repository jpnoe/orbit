package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.MoodSample
import java.time.Instant
import java.time.ZoneId

/**
 * One saved check-in, shown in history at the mood-log time.
 * The stored context window already holds the start. A day-start window is
 * displayed as "Wakeup" because no wake time was reported.
 */
object CheckInIntervalHistory {
    const val WAKEUP_LABEL = "Wakeup"

    fun build(
        checkIns: List<CheckInRecord>,
        windows: List<CheckInContextWindow>,
        activities: List<CheckInActivityItem>,
        axisValues: List<CheckInAxisValue> = emptyList(),
        workouts: List<CheckInWorkoutItem>,
        usage: List<CheckInUsageItem>,
        meals: List<CheckInMeal>,
        foods: List<CheckInMealFoodItem>,
        moods: List<MoodSample>,
    ): List<HistoryItem.CheckInInterval> {
        val windowsById = windows.associateBy { it.checkInId }
        val activitiesById = activities.groupBy { it.checkInId }
        val scoresById = axisValues.groupBy { it.checkInId }
        val workoutsById = workouts.groupBy { it.checkInId }
        val usageById = usage.groupBy { it.checkInId }
        val mealsById = meals.groupBy { it.checkInId }
        val foodsByMeal = foods.groupBy { it.mealId }
        return checkIns.map { checkIn ->
            val window = windowsById[checkIn.id]
            val mood = moods.firstOrNull { sample ->
                sample.source == MoodSample.SOURCE_CHECK_IN &&
                    sample.recordedAt == checkIn.recordedAt &&
                    sample.localDate == checkIn.localDate
            }
            val scores = scoresById[checkIn.id].orEmpty().associate { it.axisId to it.value }
            val checkInMeals = mealsById[checkIn.id].orEmpty()
                .sortedWith(compareBy<CheckInMeal> { it.sortOrder }.thenBy { it.id })
            HistoryItem.CheckInInterval(
                checkInId = checkIn.id,
                recordedAt = checkIn.recordedAt,
                startBasis = window?.startBasis ?: CheckInContextStartBasis.DAY_START,
                windowStartAt = window?.windowStartAt,
                moodScore = scores[DefaultCheckInAxes.MOOD] ?: mood?.moodScore,
                energyScore = scores[DefaultCheckInAxes.ENERGY],
                confidenceScore = scores[DefaultCheckInAxes.CONFIDENCE],
                anxietyScore = scores[DefaultCheckInAxes.ANXIETY],
                emotion = checkIn.emotion.ifBlank { mood?.emotion.orEmpty() },
                note = mood?.note?.ifBlank { checkIn.note } ?: checkIn.note,
                activities = activitiesById[checkIn.id].orEmpty().map { it.activityName },
                workouts = workoutsById[checkIn.id].orEmpty().map { item ->
                    "${item.workoutName} · ${item.durationMinutes} min"
                },
                meals = checkInMeals.map { meal ->
                    mealLine(
                        size = meal.size,
                        foodNames = foodsByMeal[meal.id].orEmpty()
                            .sortedWith(compareBy<CheckInMealFoodItem> { it.sortOrder }.thenBy { it.id })
                            .map { it.foodName },
                    )
                },
                usage = usageById[checkIn.id].orEmpty().map { item ->
                    if (item.amount.isBlank()) item.name else "${item.name} · ${item.amount}"
                },
                moodSampleId = mood?.id,
            )
        }
    }

    /**
     * Day-start rows say "Wakeup" only while the stored start is still local midnight.
     * A corrected start shows that clock time instead.
     */
    fun usesWakeupLabel(
        startBasis: String,
        windowStartAt: Long?,
        recordedAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (windowStartAt == null) return true
        if (startBasis != CheckInContextStartBasis.DAY_START) return false
        val endDate = Instant.ofEpochMilli(recordedAt).atZone(zone).toLocalDate()
        val midnight = endDate.atStartOfDay(zone).toInstant().toEpochMilli()
        return windowStartAt == midnight
    }

    fun absorbedMoodIds(intervals: List<HistoryItem.CheckInInterval>): Set<String> {
        return intervals.mapNotNull { it.moodSampleId }.toSet()
    }

    fun mealLine(size: String, foodNames: List<String>): String {
        val label = when (size) {
            CheckInMeal.SNACK -> "Snack"
            CheckInMeal.SMALL -> "Small"
            CheckInMeal.MEDIUM -> "Medium"
            CheckInMeal.LARGE -> "Large"
            else -> size
        }
        return if (foodNames.isEmpty()) "$label meal" else "$label · ${foodNames.joinToString(", ")}"
    }
}
