package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.CheckInAxisInput
import com.orbit.wellness.data.InMemoryCheckInDao
import com.orbit.wellness.data.local.ActivityOption
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContextualActivityReviewTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val atTen = DateUtils.atTimeMillis(day, 10, 0)

    private val walk = ActivityOption(id = "walk", name = "Walk", sortOrder = 0, isCustom = false)
    private val read = ActivityOption(id = "read", name = "Read", sortOrder = 1, isCustom = false)
    private val cook = ActivityOption(id = "cook", name = "Cook", sortOrder = 2, isCustom = true)

    @Test
    fun revisitingPreloadsSavedOptionIdsOnce() {
        val selection = ContextualActivitySelection()
        selection.onSavedItems(listOf("read", null, "missing"))
        assertTrue(selection.selectedIds.isEmpty())
        selection.onOptions(listOf(walk, read))
        assertEquals(setOf("read"), selection.selectedIds)

        selection.toggle("walk")
        selection.onOptions(listOf(walk, read, cook))
        selection.onSavedItems(listOf("walk"))
        assertEquals(setOf("read", "walk"), selection.selectedIds)
    }

    @Test
    fun multipleSelectionsSaveOptionIdsAndNameSnapshots() {
        val repository = repositoryWithCheckIn()
        val selection = ContextualActivitySelection()
        selection.onOptions(listOf(read, walk))
        selection.onSavedItems(emptyList())
        selection.toggle("read")
        selection.toggle("walk")
        runBlocking {
            commitActivityReview(
                repository,
                "ten",
                selection.commitForSave(),
            )
        }
        val items = runBlocking { repository.getActivityItems("ten") }
        assertEquals(
            setOf("walk" to "Walk", "read" to "Read"),
            items.map { it.activityOptionId to it.activityName }.toSet(),
        )
        assertEquals(
            CheckInContextStatus.ENTRIES,
            activityStatus(repository),
        )
    }

    @Test
    fun explicitNoneSavesAnEmptySelection() {
        val repository = repositoryWithCheckIn()
        val selection = ContextualActivitySelection()
        selection.onOptions(listOf(walk))
        selection.onSavedItems(emptyList())
        selection.toggle("walk")
        runBlocking {
            commitActivityReview(repository, "ten", selection.commitForNone())
        }
        assertTrue(runBlocking { repository.getActivityItems("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.NONE, activityStatus(repository))
    }

    @Test
    fun backingOutPerformsNoWrite() {
        val repository = repositoryWithCheckIn()
        val selection = ContextualActivitySelection()
        selection.onOptions(listOf(walk))
        selection.onSavedItems(emptyList())
        selection.toggle("walk")
        runBlocking {
            commitActivityReview(repository, "ten", selection.commitForBack())
        }
        assertTrue(runBlocking { repository.getActivityItems("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.UNREVIEWED, activityStatus(repository))
    }

    @Test
    fun saveFailureLeavesTheDraftSelected() {
        val selection = ContextualActivitySelection()
        selection.onOptions(listOf(walk, read))
        selection.onSavedItems(emptyList())
        selection.toggle("walk")
        selection.toggle("read")
        val beforeFailure = selection.selectedIds
        val commit = try {
            throw IllegalStateException("database unavailable")
        } catch (error: IllegalStateException) {
            error
            selection.commitForSave()
        }
        assertEquals(beforeFailure, selection.selectedIds)
        assertEquals(
            listOf("walk" to "Walk", "read" to "Read"),
            (commit as ActivityReviewCommit.Replace).selections.map { it.activityOptionId to it.activityName },
        )
    }

    private fun repositoryWithCheckIn(): CheckInRepository {
        val repository = CheckInRepository(InMemoryCheckInDao())
        runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 5)),
                trigger = "manual",
                recordedAt = atTen,
                localDate = localDate,
                id = "ten",
            )
        }
        return repository
    }

    private fun activityStatus(repository: CheckInRepository): String {
        return runBlocking { repository.getContextCategories("ten") }
            .first { it.category == CheckInContextCategories.ACTIVITY }
            .status
    }
}
