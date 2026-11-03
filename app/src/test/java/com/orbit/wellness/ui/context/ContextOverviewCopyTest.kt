package com.orbit.wellness.ui.context

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ContextOverviewCopyTest {

    @Test
    fun headingsMatchTheWindowBasis() {
        val zone = ZoneOffset.UTC
        val atSeven = 7 * 60 * 60 * 1000L
        val previous = ContextOverviewCopy.heading(
            CheckInContextStartBasis.PREVIOUS_CHECK_IN,
            atSeven,
            zone,
        )
        val wake = ContextOverviewCopy.heading(
            CheckInContextStartBasis.WAKE_TIME,
            atSeven,
            zone,
        )
        val day = ContextOverviewCopy.heading(
            CheckInContextStartBasis.DAY_START,
            atSeven,
            zone,
        )
        val formatted = DateUtils.formatTime(atSeven, zone)
        assertEquals(ContextOverviewCopy.SINCE_LAST_CHECK_IN, previous.first)
        assertEquals(formatted, previous.second)
        assertEquals(ContextOverviewCopy.SINCE_WAKING, wake.first)
        assertEquals(formatted, wake.second)
        assertEquals(ContextOverviewCopy.SO_FAR_TODAY, day.first)
        assertNull(day.second)
    }

    @Test
    fun categoryStatusMapsToOverviewText() {
        assertEquals(
            ContextOverviewCopy.NOT_REVIEWED,
            ContextOverviewCopy.activityStatusText(CheckInContextStatus.UNREVIEWED, emptyList()),
        )
        assertEquals(
            ContextOverviewCopy.NONE,
            ContextOverviewCopy.activityStatusText(CheckInContextStatus.NONE, emptyList()),
        )
        assertEquals(
            "Walk",
            ContextOverviewCopy.activityStatusText(CheckInContextStatus.ENTRIES, listOf("Walk")),
        )
        assertEquals(
            "Walk, Read",
            ContextOverviewCopy.activityStatusText(CheckInContextStatus.ENTRIES, listOf("Walk", "Read")),
        )
        assertEquals(
            "3 activities",
            ContextOverviewCopy.activityStatusText(
                CheckInContextStatus.ENTRIES,
                listOf("Walk", "Read", "Cook"),
            ),
        )
    }

    @Test
    fun workoutSummariesCoverNoneOneAndMany() {
        assertEquals(
            ContextOverviewCopy.NOT_REVIEWED,
            ContextOverviewCopy.workoutStatusText(CheckInContextStatus.UNREVIEWED, emptyList()),
        )
        assertEquals(
            ContextOverviewCopy.NONE,
            ContextOverviewCopy.workoutStatusText(CheckInContextStatus.NONE, emptyList()),
        )
        assertEquals(
            "Cardio · 30 min",
            ContextOverviewCopy.workoutStatusText(CheckInContextStatus.ENTRIES, listOf("Cardio" to 30)),
        )
        assertEquals(
            "2 workouts · 75 min",
            ContextOverviewCopy.workoutStatusText(
                CheckInContextStatus.ENTRIES,
                listOf("Cardio" to 30, "Strength" to 45),
            ),
        )
    }

    @Test
    fun activityAndWorkoutAreInteractiveAndDietAndUsageStayComingNext() {
        val window = CheckInContextWindow(
            checkInId = "check-in",
            previousCheckInId = null,
            windowStartAt = 0L,
            windowEndAt = 1_000L,
            startBasis = CheckInContextStartBasis.DAY_START,
        )
        val categories = CheckInContextCategories.ALL.map { category ->
            CheckInContextCategory(
                checkInId = "check-in",
                category = category,
                status = if (
                    category == CheckInContextCategories.ACTIVITY ||
                    category == CheckInContextCategories.WORKOUT
                ) {
                    CheckInContextStatus.ENTRIES
                } else {
                    CheckInContextStatus.UNREVIEWED
                },
            )
        }
        val overview = ContextOverviewCopy.present(
            window = window,
            categories = categories,
            activityItems = listOf(
                CheckInActivityItem(
                    checkInId = "check-in",
                    activityOptionId = "walk",
                    activityName = "Walk",
                    reportedAt = 1_000L,
                ),
            ),
            workoutItems = listOf(
                CheckInWorkoutItem(
                    checkInId = "check-in",
                    workoutOptionId = "cardio",
                    workoutName = "Cardio",
                    durationMinutes = 30,
                    reportedAt = 1_000L,
                ),
            ),
            zone = ZoneOffset.UTC,
        )
        val activity = overview.cards.first { it.category == CheckInContextCategories.ACTIVITY }
        assertTrue(activity.interactive)
        assertFalse(activity.comingNext)
        assertEquals("Walk", activity.statusText)
        val workout = overview.cards.first { it.category == CheckInContextCategories.WORKOUT }
        assertTrue(workout.interactive)
        assertFalse(workout.comingNext)
        assertEquals("Cardio · 30 min", workout.statusText)
        overview.cards.filter {
            it.category != CheckInContextCategories.ACTIVITY &&
                it.category != CheckInContextCategories.WORKOUT
        }.forEach { card ->
            assertFalse(card.interactive)
            assertTrue(card.comingNext)
            assertEquals(ContextOverviewCopy.NOT_REVIEWED, card.statusText)
        }
    }

    @Test
    fun missingWindowIsRecoverable() {
        val overview = ContextOverviewCopy.present(null, emptyList(), emptyList())
        assertTrue(overview.missing)
        assertEquals(ContextOverviewCopy.MISSING, overview.title)
        assertTrue(overview.cards.isEmpty())
    }
}
