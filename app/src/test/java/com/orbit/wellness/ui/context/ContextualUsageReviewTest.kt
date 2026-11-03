package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInAxisInput
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.CheckInUsageSelection
import com.orbit.wellness.data.InMemoryCheckInDao
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceOption
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContextualUsageReviewTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val atTen = DateUtils.atTimeMillis(day, 10, 0)

    private val medication = SubstanceOption(
        id = "med",
        name = "Medication",
        intent = SubstanceIntent.SUPPORTIVE.name,
        defaultAmount = "10 mg",
        sortOrder = 0,
        isCustom = false,
    )
    private val alcohol = SubstanceOption(
        id = "alcohol",
        name = "Alcohol",
        intent = SubstanceIntent.COPING.name,
        defaultAmount = "1 drink",
        sortOrder = 0,
        isCustom = false,
    )

    @Test
    fun reopeningPreloadsSavedItemsAndAmountsOnce() {
        val draft = ContextualUsageSelection()
        draft.onSavedItems(
            listOf(
                saved("med", "Medication", SubstanceIntent.SUPPORTIVE.name, "3"),
                saved("alcohol", "Alcohol", SubstanceIntent.COPING.name, "2 drinks"),
            ),
        )
        assertTrue(draft.lines.isEmpty())
        draft.onOptions(listOf(medication, alcohol))
        assertEquals(
            listOf("Medication" to "3", "Alcohol" to "2 drinks"),
            draft.lines.map { it.name to it.amount },
        )

        draft.onAmountChanged(draft.lines.first().key, "4")
        draft.onSavedItems(
            listOf(saved("med", "Medication", SubstanceIntent.SUPPORTIVE.name, "3")),
        )
        draft.onOptions(listOf(medication, alcohol))
        assertEquals(listOf("Medication" to "4", "Alcohol" to "2 drinks"), draft.lines.map { it.name to it.amount })
    }

    @Test
    fun defaultAmountStaysADraftUntilSave() {
        val repository = repositoryWithCheckIn()
        val draft = ContextualUsageSelection()
        draft.onOptions(listOf(medication))
        draft.onSavedItems(emptyList())
        draft.toggle(medication)
        assertEquals("10 mg", draft.lines.single().amount)
        runBlocking {
            commitUsageReview(repository, "ten", draft.commitForBack())
        }
        assertTrue(runBlocking { repository.getUsageItems("ten") }.isEmpty())
        assertEquals(
            CheckInContextStatus.UNREVIEWED,
            usageStatus(repository),
        )

        draft.onAmountChanged(draft.lines.single().key, "")
        runBlocking {
            commitUsageReview(repository, "ten", draft.commitForSave())
        }
        val saved = runBlocking { repository.getUsageItems("ten") }.single()
        assertEquals("Medication", saved.name)
        assertEquals("", saved.amount)
        assertEquals(CheckInContextStatus.ENTRIES, usageStatus(repository))
    }

    @Test
    fun backPerformsNoWrite() {
        val repository = repositoryWithCheckIn()
        val draft = ContextualUsageSelection()
        draft.onOptions(listOf(medication, alcohol))
        draft.onSavedItems(emptyList())
        draft.toggle(alcohol)
        runBlocking {
            commitUsageReview(repository, "ten", draft.commitForBack())
        }
        assertTrue(runBlocking { repository.getUsageItems("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.UNREVIEWED, usageStatus(repository))
        assertEquals(listOf("Alcohol"), draft.lines.map { it.name })
    }

    @Test
    fun saveFailurePreservesTheDraftAndThePreviousList() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveUsageSelections(
                checkInId = "ten",
                selections = listOf(
                    CheckInUsageSelection(
                        substanceOptionId = "med",
                        name = "Medication",
                        intent = SubstanceIntent.SUPPORTIVE.name,
                        amount = "10 mg",
                    ),
                ),
                reportedAt = atTen,
            )
        }
        val draft = ContextualUsageSelection()
        draft.onSavedItems(runBlocking { repository.getUsageItems("ten") })
        draft.onOptions(listOf(medication, alcohol))
        draft.toggle(alcohol)
        draft.onAmountChanged("alcohol", "2 drinks")
        val before = draft.lines.map { it.name to it.amount }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                commitUsageReview(repository, "missing", draft.commitForSave())
            }
        }
        assertEquals(before, draft.lines.map { it.name to it.amount })
        val stored = runBlocking { repository.getUsageItems("ten") }.single()
        assertEquals("Medication", stored.name)
        assertEquals("10 mg", stored.amount)
        assertEquals(CheckInContextStatus.ENTRIES, usageStatus(repository))
    }

    private fun saved(optionId: String, name: String, intent: String, amount: String) = CheckInUsageItem(
        checkInId = "ten",
        substanceOptionId = optionId,
        name = name,
        intent = intent,
        amount = amount,
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

    private fun usageStatus(repository: CheckInRepository): String {
        return runBlocking { repository.getContextCategories("ten") }
            .first { it.category == CheckInContextCategories.USAGE }
            .status
    }
}
