package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CheckInUsageRepositoryTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val atTen = DateUtils.atTimeMillis(day, 10, 0)

    @Test
    fun supportiveAndCopingItemsAreAcceptedAndAmountsAreTrimmed() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveUsageSelections(
                checkInId = "ten",
                selections = listOf(
                    selection("med", "  Medication  ", SubstanceIntent.SUPPORTIVE.name, "  10 mg  "),
                    selection("alcohol", "Alcohol", SubstanceIntent.COPING.name, "2  drinks"),
                ),
                reportedAt = atTen,
            )
        }
        val saved = runBlocking { repository.getUsageItems("ten") }
        assertEquals(listOf("Medication", "Alcohol"), saved.map { it.name })
        assertEquals(listOf(SubstanceIntent.SUPPORTIVE.name, SubstanceIntent.COPING.name), saved.map { it.intent })
        assertEquals(listOf("10 mg", "2  drinks"), saved.map { it.amount })
        assertEquals(listOf(atTen, atTen), saved.map { it.reportedAt })
        assertEquals(CheckInContextStatus.ENTRIES, usageStatus(repository))
    }

    @Test
    fun unknownIntentIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CheckInUsageSelections.validate(
                selections = listOf(selection("med", "Medication", "RECREATIONAL", "10 mg")),
                contextWindowExists = true,
            )
        }
    }

    @Test
    fun blankAmountIsAccepted() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveUsageSelections(
                checkInId = "ten",
                selections = listOf(selection("med", "Medication", SubstanceIntent.SUPPORTIVE.name, "   ")),
                reportedAt = atTen,
            )
        }
        assertEquals("", runBlocking { repository.getUsageItems("ten") }.single().amount)
    }

    @Test
    fun duplicateOptionIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CheckInUsageSelections.validate(
                selections = listOf(
                    selection("med", "Medication", SubstanceIntent.SUPPORTIVE.name, "10 mg"),
                    selection("med", "Medication", SubstanceIntent.SUPPORTIVE.name, "20 mg"),
                ),
                contextWindowExists = true,
            )
        }
    }

    @Test
    fun missingContextBlankNameBlankOptionIdAndNonePlaceholderAreRejected() {
        val repository = CheckInRepository(InMemoryCheckInDao())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveUsageSelections(
                    checkInId = "missing",
                    selections = listOf(selection("med", "Medication", SubstanceIntent.SUPPORTIVE.name, "")),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckInUsageSelections.validate(
                selections = listOf(selection("med", "  ", SubstanceIntent.SUPPORTIVE.name, "")),
                contextWindowExists = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckInUsageSelections.validate(
                selections = listOf(selection(" ", "Medication", SubstanceIntent.SUPPORTIVE.name, "")),
                contextWindowExists = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckInUsageSelections.validate(
                selections = listOf(
                    selection(null, CheckInUsageSelections.NONE_LABEL, SubstanceIntent.SUPPORTIVE.name, ""),
                ),
                contextWindowExists = true,
            )
        }
    }

    @Test
    fun explicitEmptySaveIsNoneWithoutAPlaceholder() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveUsageSelections(
                checkInId = "ten",
                selections = listOf(selection("med", "Medication", SubstanceIntent.SUPPORTIVE.name, "10 mg")),
                reportedAt = atTen,
            )
            repository.saveUsageSelections(
                checkInId = "ten",
                selections = emptyList(),
                reportedAt = atTen + 1,
            )
        }
        assertTrue(runBlocking { repository.getUsageItems("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.NONE, usageStatus(repository))
    }

    @Test
    fun replacingASavedListTouchesOnlyUsage() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveActivitySelections(
                checkInId = "ten",
                selections = listOf(CheckInActivitySelection("walk", "Walk")),
                reportedAt = atTen,
            )
            repository.saveWorkoutSelections(
                checkInId = "ten",
                selections = listOf(
                    CheckInWorkoutSelection(
                        workoutOptionId = "cardio",
                        workoutName = "Cardio",
                        durationMinutes = 30,
                    ),
                ),
                reportedAt = atTen,
            )
            repository.saveUsageSelections(
                checkInId = "ten",
                selections = listOf(
                    selection("med", "Medication", SubstanceIntent.SUPPORTIVE.name, "10 mg"),
                    selection("alcohol", "Alcohol", SubstanceIntent.COPING.name, "1"),
                ),
                reportedAt = atTen,
            )
            repository.saveUsageSelections(
                checkInId = "ten",
                selections = listOf(selection("magnesium", "Magnesium", SubstanceIntent.SUPPORTIVE.name, "")),
                reportedAt = atTen + 5,
            )
        }
        val usage = runBlocking { repository.getUsageItems("ten") }.single()
        assertEquals("Magnesium", usage.name)
        assertEquals("", usage.amount)
        assertEquals(atTen + 5, usage.reportedAt)
        assertEquals(CheckInContextStatus.ENTRIES, usageStatus(repository))
        assertEquals(
            listOf("Walk"),
            runBlocking { repository.getActivityItems("ten") }.map { it.activityName },
        )
        assertEquals(
            listOf("Cardio" to 30),
            runBlocking { repository.getWorkoutItems("ten") }.map { it.workoutName to it.durationMinutes },
        )
        val categories = runBlocking { repository.getContextCategories("ten") }
        assertEquals(
            CheckInContextStatus.ENTRIES,
            categories.first { it.category == CheckInContextCategories.ACTIVITY }.status,
        )
        assertEquals(
            CheckInContextStatus.ENTRIES,
            categories.first { it.category == CheckInContextCategories.WORKOUT }.status,
        )
        assertEquals(
            CheckInContextStatus.UNREVIEWED,
            categories.first { it.category == CheckInContextCategories.DIET }.status,
        )
    }

    private fun selection(optionId: String?, name: String, intent: String, amount: String) = CheckInUsageSelection(
        substanceOptionId = optionId,
        name = name,
        intent = intent,
        amount = amount,
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

    private fun usageStatus(repository: CheckInRepository): String {
        return runBlocking { repository.getContextCategories("ten") }
            .first { it.category == CheckInContextCategories.USAGE }
            .status
    }
}
