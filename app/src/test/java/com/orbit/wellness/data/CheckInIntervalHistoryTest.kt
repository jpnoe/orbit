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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInIntervalHistoryTest {
    private val day = "2026-11-03"
    private val wake = 1_000L
    private val firstAt = 2_000L
    private val secondAt = 3_000L

    @Test
    fun firstLogUsesWakeTimeAndLaterLogUsesThePreviousMoodTime() {
        val intervals = CheckInIntervalHistory.build(
            checkIns = listOf(
                checkIn("second", secondAt),
                checkIn("first", firstAt),
            ),
            windows = listOf(
                window("first", CheckInContextStartBasis.WAKE_TIME, wake),
                window("second", CheckInContextStartBasis.PREVIOUS_CHECK_IN, firstAt),
            ),
            activities = emptyList(),
            workouts = emptyList(),
            usage = emptyList(),
            meals = emptyList(),
            foods = emptyList(),
            moods = emptyList(),
        )
        val first = intervals.first { it.checkInId == "first" }
        val second = intervals.first { it.checkInId == "second" }
        assertEquals(firstAt, first.timestamp)
        assertEquals(CheckInContextStartBasis.WAKE_TIME, first.startBasis)
        assertEquals(wake, first.windowStartAt)
        assertEquals(secondAt, second.timestamp)
        assertEquals(CheckInContextStartBasis.PREVIOUS_CHECK_IN, second.startBasis)
        assertEquals(firstAt, second.windowStartAt)
    }

    @Test
    fun missingWakeUsesTheWakeupPlaceholder() {
        val interval = CheckInIntervalHistory.build(
            checkIns = listOf(checkIn("only", firstAt)),
            windows = listOf(window("only", CheckInContextStartBasis.DAY_START, 0L)),
            activities = emptyList(),
            workouts = emptyList(),
            usage = emptyList(),
            meals = emptyList(),
            foods = emptyList(),
            moods = emptyList(),
        ).single()
        assertEquals(CheckInContextStartBasis.DAY_START, interval.startBasis)
        assertEquals(CheckInIntervalHistory.WAKEUP_LABEL, "Wakeup")
        assertTrue(interval.activities.isEmpty())
        assertTrue(interval.workouts.isEmpty())
        assertTrue(interval.meals.isEmpty())
        assertTrue(interval.usage.isEmpty())
    }

    @Test
    fun contextListsShareTheMoodLogTimestamp() {
        val mood = MoodSample(
            id = "mood-1",
            localDate = day,
            moodScore = 6,
            note = "steady",
            emotion = "Calm",
            recordedAt = firstAt,
            source = MoodSample.SOURCE_CHECK_IN,
        )
        val interval = CheckInIntervalHistory.build(
            checkIns = listOf(checkIn("first", firstAt)),
            windows = listOf(window("first", CheckInContextStartBasis.WAKE_TIME, wake)),
            activities = listOf(
                CheckInActivityItem(
                    id = "walk",
                    checkInId = "first",
                    activityOptionId = "walk",
                    activityName = "Walk",
                    reportedAt = firstAt,
                ),
            ),
            workouts = listOf(
                CheckInWorkoutItem(
                    id = "cardio",
                    checkInId = "first",
                    workoutOptionId = "cardio",
                    workoutName = "Cardio",
                    durationMinutes = 30,
                    reportedAt = firstAt,
                ),
            ),
            usage = listOf(
                CheckInUsageItem(
                    id = "tea",
                    checkInId = "first",
                    substanceOptionId = "tea",
                    name = "Tea",
                    intent = "SUPPORTIVE",
                    amount = "1 cup",
                    reportedAt = firstAt,
                ),
                CheckInUsageItem(
                    id = "water",
                    checkInId = "first",
                    substanceOptionId = "water",
                    name = "Water",
                    intent = "SUPPORTIVE",
                    amount = "",
                    reportedAt = firstAt,
                ),
            ),
            meals = listOf(
                CheckInMeal("lunch", "first", CheckInMeal.MEDIUM, 1, firstAt),
                CheckInMeal("snack", "first", CheckInMeal.SNACK, 0, firstAt),
            ),
            foods = listOf(
                CheckInMealFoodItem("chicken", "lunch", "chicken", "Chicken", 1),
                CheckInMealFoodItem("rice", "lunch", "rice", "Rice", 0),
            ),
            moods = listOf(
                mood,
                mood.copy(id = "other", recordedAt = 9_000L, source = "manual"),
            ),
        ).single()

        assertEquals(firstAt, interval.timestamp)
        assertEquals(6, interval.moodScore)
        assertEquals("Calm", interval.emotion)
        assertEquals("steady", interval.note)
        assertEquals(listOf("Walk"), interval.activities)
        assertEquals(listOf("Cardio · 30 min"), interval.workouts)
        assertEquals(
            listOf("Snack meal", "Medium · Rice, Chicken"),
            interval.meals,
        )
        assertEquals(listOf("Tea · 1 cup", "Water"), interval.usage)
        assertEquals(setOf("mood-1"), CheckInIntervalHistory.absorbedMoodIds(listOf(interval)))
        assertNull(interval.energyScore)
        assertNull(interval.confidenceScore)
        assertNull(interval.anxietyScore)
        assertNull(
            CheckInIntervalHistory.build(
                checkIns = emptyList(),
                windows = emptyList(),
                activities = emptyList(),
                workouts = emptyList(),
                usage = emptyList(),
                meals = emptyList(),
                foods = emptyList(),
                moods = listOf(mood),
            ).firstOrNull(),
        )
    }

    @Test
    fun axisScoresStayWithThatCheckIn() {
        val interval = CheckInIntervalHistory.build(
            checkIns = listOf(checkIn("first", firstAt)),
            windows = listOf(window("first", CheckInContextStartBasis.WAKE_TIME, wake)),
            activities = emptyList(),
            axisValues = listOf(
                CheckInAxisValue("first", DefaultCheckInAxes.MOOD, 7),
                CheckInAxisValue("first", DefaultCheckInAxes.ENERGY, 4),
                CheckInAxisValue("first", DefaultCheckInAxes.CONFIDENCE, 8),
                CheckInAxisValue("other", DefaultCheckInAxes.ANXIETY, 9),
            ),
            workouts = emptyList(),
            usage = emptyList(),
            meals = emptyList(),
            foods = emptyList(),
            moods = listOf(
                MoodSample(
                    id = "mood-1",
                    localDate = day,
                    moodScore = 6,
                    emotion = "Calm",
                    recordedAt = firstAt,
                    source = MoodSample.SOURCE_CHECK_IN,
                ),
            ),
        ).single()
        assertEquals(7, interval.moodScore)
        assertEquals("Calm", interval.emotion)
        assertEquals(4, interval.energyScore)
        assertEquals(8, interval.confidenceScore)
        assertNull(interval.anxietyScore)
    }

    private fun checkIn(id: String, at: Long) = CheckInRecord(
        id = id,
        localDate = day,
        recordedAt = at,
        trigger = CheckInRecord.TRIGGER_MANUAL,
    )

    private fun window(id: String, basis: String, start: Long) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = if (basis == CheckInContextStartBasis.PREVIOUS_CHECK_IN) "first" else null,
        windowStartAt = start,
        windowEndAt = if (id == "second") secondAt else firstAt,
        startBasis = basis,
        completedAt = start + 1,
    )
}
