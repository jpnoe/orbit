package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInAxisInput
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.InMemoryCheckInDao
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.WorkoutOption
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContextualWorkoutReviewTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val atTen = DateUtils.atTimeMillis(day, 10, 0)

    private val cardio = WorkoutOption(id = "cardio", name = "Cardio", sortOrder = 0, isCustom = false)
    private val strength = WorkoutOption(id = "strength", name = "Strength", sortOrder = 1, isCustom = false)

    @Test
    fun durationPresetsAreTheCommonLengths() {
        assertEquals(listOf(10, 20, 30, 45, 60, 90), CONTEXTUAL_WORKOUT_DURATION_PRESETS)
    }

    @Test
    fun reopeningPreloadsSavedEntriesOnce() {
        val draft = ContextualWorkoutDraft()
        draft.onSavedItems(
            listOf(
                saved("one", "cardio", "Cardio", 30),
                saved("two", "strength", "Strength", 45),
            ),
        )
        assertTrue(draft.entries.isEmpty())
        draft.onOptions(listOf(cardio, strength))
        assertEquals(listOf("Cardio" to 30, "Strength" to 45), draft.entries.map { it.workoutName to it.durationMinutes })

        draft.remove(draft.entries.first().id)
        draft.onSavedItems(listOf(saved("one", "cardio", "Cardio", 30)))
        draft.onOptions(listOf(cardio, strength))
        assertEquals(listOf("Strength" to 45), draft.entries.map { it.workoutName to it.durationMinutes })
    }

    @Test
    fun draftEntriesCanBeAddedEditedAndRemoved() {
        val draft = readyDraft()
        draft.selectOption("cardio")
        draft.selectPreset(30)
        assertNull(draft.addOrUpdate())
        draft.selectOption("cardio")
        draft.onCustomDuration("25")
        assertNull(draft.addOrUpdate())
        assertEquals(listOf(30, 25), draft.entries.map { it.durationMinutes })

        draft.beginEdit(draft.entries.first().id)
        draft.selectPreset(45)
        assertNull(draft.addOrUpdate())
        assertEquals(listOf(45, 25), draft.entries.map { it.durationMinutes })
        assertEquals(listOf("cardio", "cardio"), draft.entries.map { it.workoutOptionId })

        draft.remove(draft.entries.last().id)
        assertEquals(listOf(45), draft.entries.map { it.durationMinutes })
    }

    @Test
    fun backPerformsNoWrite() {
        val repository = repositoryWithCheckIn()
        val draft = readyDraft()
        draft.selectOption("cardio")
        draft.selectPreset(30)
        draft.addOrUpdate()
        runBlocking {
            commitWorkoutReview(repository, "ten", draft.commitForBack())
        }
        assertTrue(runBlocking { repository.getWorkoutItems("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.UNREVIEWED, workoutStatus(repository))
    }

    @Test
    fun explicitNoneUsesTheIntervalNeutralLabelAndClearsEntries() {
        assertEquals("No workouts to report", CONTEXTUAL_WORKOUT_NONE_LABEL)
        val repository = repositoryWithCheckIn()
        val draft = readyDraft()
        draft.selectOption("strength")
        draft.selectPreset(20)
        draft.addOrUpdate()
        runBlocking {
            commitWorkoutReview(repository, "ten", draft.commitForNone())
        }
        assertTrue(runBlocking { repository.getWorkoutItems("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.NONE, workoutStatus(repository))
    }

    @Test
    fun saveFailureLeavesTheDraftEntries() {
        val draft = readyDraft()
        draft.selectOption("cardio")
        draft.selectPreset(30)
        draft.addOrUpdate()
        draft.selectOption("strength")
        draft.selectPreset(45)
        draft.addOrUpdate()
        val beforeFailure = draft.entries
        val commit = try {
            throw IllegalStateException("database unavailable")
        } catch (error: IllegalStateException) {
            error
            draft.commitForSave()
        }
        assertEquals(beforeFailure, draft.entries)
        assertEquals(
            listOf("Cardio" to 30, "Strength" to 45),
            (commit as WorkoutReviewCommit.Replace).selections.map { it.workoutName to it.durationMinutes },
        )
    }

    private fun readyDraft(): ContextualWorkoutDraft {
        val draft = ContextualWorkoutDraft()
        draft.onOptions(listOf(cardio, strength))
        draft.onSavedItems(emptyList())
        return draft
    }

    private fun saved(id: String, optionId: String, name: String, minutes: Int) = CheckInWorkoutItem(
        id = id,
        checkInId = "ten",
        workoutOptionId = optionId,
        workoutName = name,
        durationMinutes = minutes,
        reportedAt = atTen,
    )

    private fun repositoryWithCheckIn(): CheckInRepository {
        val repository = CheckInRepository(InMemoryCheckInDao())
        runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 6)),
                trigger = "manual",
                recordedAt = atTen,
                localDate = localDate,
                id = "ten",
            )
        }
        return repository
    }

    private fun workoutStatus(repository: CheckInRepository): String {
        return runBlocking { repository.getContextCategories("ten") }
            .first { it.category == CheckInContextCategories.WORKOUT }
            .status
    }
}
