package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CheckInWorkoutRepositoryTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val atTen = DateUtils.atTimeMillis(day, 10, 0)

    @Test
    fun positiveDurationWithinTheDayIsAccepted() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveWorkoutSelections(
                checkInId = "ten",
                selections = listOf(selection("cardio", "Cardio", CheckInWorkoutItem.MAX_DURATION_MINUTES)),
                reportedAt = atTen,
            )
        }
        val saved = runBlocking { repository.getWorkoutItems("ten") }.single()
        assertEquals(CheckInWorkoutItem.MAX_DURATION_MINUTES, saved.durationMinutes)
        assertEquals(atTen, saved.reportedAt)
    }

    @Test
    fun zeroNegativeAndExcessiveDurationsAreRejected() {
        listOf(0, -5, CheckInWorkoutItem.MAX_DURATION_MINUTES + 1).forEach { minutes ->
            assertThrows(IllegalArgumentException::class.java) {
                CheckInWorkoutSelections.validate(
                    selections = listOf(selection("cardio", "Cardio", minutes)),
                    contextWindowExists = true,
                )
            }
        }
    }

    @Test
    fun missingContextBlankNameAndBlankOptionIdAreRejected() {
        val repository = CheckInRepository(InMemoryCheckInDao())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveWorkoutSelections(
                    checkInId = "missing",
                    selections = listOf(selection("cardio", "Cardio", 30)),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckInWorkoutSelections.validate(
                selections = listOf(selection("cardio", "  ", 30)),
                contextWindowExists = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckInWorkoutSelections.validate(
                selections = listOf(selection(" ", "Cardio", 30)),
                contextWindowExists = true,
            )
        }
    }

    @Test
    fun explicitEmptySaveIsNoneWithoutAPlaceholder() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveWorkoutSelections(
                checkInId = "ten",
                selections = listOf(selection("cardio", "Cardio", 30)),
                reportedAt = atTen,
            )
            repository.saveWorkoutSelections(
                checkInId = "ten",
                selections = emptyList(),
                reportedAt = atTen + 1,
            )
        }
        assertTrue(runBlocking { repository.getWorkoutItems("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.NONE, workoutStatus(repository))
    }

    @Test
    fun multipleWorkoutsIncludingTheSameTypeAreStoredAndCanBeReplaced() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveWorkoutSelections(
                checkInId = "ten",
                selections = listOf(
                    selection("cardio", "Cardio", 30),
                    selection("cardio", "Cardio", 20),
                    selection("strength", "Strength", 45),
                ),
                reportedAt = atTen,
            )
        }
        val first = runBlocking { repository.getWorkoutItems("ten") }
        assertEquals(listOf(30, 20, 45), first.map { it.durationMinutes })
        assertEquals(listOf("Cardio", "Cardio", "Strength"), first.map { it.workoutName })
        assertEquals(CheckInContextStatus.ENTRIES, workoutStatus(repository))

        runBlocking {
            repository.saveWorkoutSelections(
                checkInId = "ten",
                selections = listOf(selection("yoga", "Yoga / Balance", 15)),
                reportedAt = atTen + 5,
            )
        }
        val replaced = runBlocking { repository.getWorkoutItems("ten") }.single()
        assertEquals("Yoga / Balance", replaced.workoutName)
        assertEquals(15, replaced.durationMinutes)
        assertEquals(atTen + 5, replaced.reportedAt)
    }

    @Test
    fun rejectedSaveLeavesThePreviousWorkoutListAndDoesNotChangeActivity() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveActivitySelections(
                checkInId = "ten",
                selections = listOf(
                    CheckInActivitySelection(activityOptionId = "walk", activityName = "Walk"),
                ),
                reportedAt = atTen,
            )
            repository.saveWorkoutSelections(
                checkInId = "ten",
                selections = listOf(selection("cardio", "Cardio", 30)),
                reportedAt = atTen,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveWorkoutSelections(
                    checkInId = "ten",
                    selections = listOf(selection("cardio", "Cardio", 0)),
                )
            }
        }
        val workouts = runBlocking { repository.getWorkoutItems("ten") }
        assertEquals(listOf("Cardio" to 30), workouts.map { it.workoutName to it.durationMinutes })
        val activity = runBlocking { repository.getContextCategories("ten") }
            .first { it.category == CheckInContextCategories.ACTIVITY }
        assertEquals(CheckInContextStatus.ENTRIES, activity.status)
        assertEquals(
            listOf("Walk"),
            runBlocking { repository.getActivityItems("ten") }.map { it.activityName },
        )
        val other = runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 4)),
                trigger = "manual",
                recordedAt = atTen + 60_000,
                localDate = localDate,
                id = "later",
            )
            repository.saveWorkoutSelections(
                checkInId = "later",
                selections = listOf(selection("strength", "Strength", 10)),
                reportedAt = atTen,
            )
            repository.getWorkoutItems("ten")
        }
        assertEquals(workouts.map { it.id }, other.map { it.id })
    }

    private fun selection(optionId: String, name: String, minutes: Int) = CheckInWorkoutSelection(
        workoutOptionId = optionId,
        workoutName = name,
        durationMinutes = minutes,
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
