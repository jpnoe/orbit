package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContextCompletionTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val atTen = DateUtils.atTimeMillis(day, 10, 0)

    @Test
    fun doneSetsCompletedAt() {
        val repository = savedRepository()
        val completedAt = runBlocking { repository.completeContext("ten", completedAt = 1_000) }
        assertEquals(1_000L, completedAt)
        assertEquals(1_000L, runBlocking { repository.getContextWindow("ten") }!!.completedAt)
    }

    @Test
    fun doneIsIdempotentAndKeepsTheFirstCompletionTime() {
        val repository = savedRepository()
        val first = runBlocking { repository.completeContext("ten", completedAt = 1_000) }
        val second = runBlocking { repository.completeContext("ten", completedAt = 9_000) }
        assertEquals(1_000L, first)
        assertEquals(first, second)
        assertEquals(1_000L, runBlocking { repository.getContextWindow("ten") }!!.completedAt)
    }

    @Test
    fun backLeavesCompletedAtUnset() {
        val repository = savedRepository()
        // Ordinary back only leaves the screen. It does not call completeContext.
        assertNull(runBlocking { repository.getContextWindow("ten") }!!.completedAt)
    }

    @Test
    fun doneLeavesUnreviewedCategoriesUnchanged() {
        val repository = savedRepository()
        val before = runBlocking { repository.getContextCategories("ten") }
        runBlocking { repository.completeContext("ten", completedAt = 1_000) }
        val after = runBlocking { repository.getContextCategories("ten") }
        assertEquals(before, after)
        assertEquals(CheckInContextCategories.ALL, after.map { it.category })
        assertTrue(after.all { it.status == CheckInContextStatus.UNREVIEWED && it.reviewedAt == null })
    }

    @Test
    fun nonexistentContextDoesNotReportSuccessfulCompletion() {
        val repository = CheckInRepository(InMemoryCheckInDao())
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.completeContext("missing", completedAt = 1_000) }
        }
        assertTrue(error.message!!.contains("does not exist"))
        assertNull(runBlocking { repository.getContextWindow("missing") })
    }

    private fun savedRepository(): CheckInRepository {
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
}
