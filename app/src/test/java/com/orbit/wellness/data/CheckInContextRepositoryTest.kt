package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStartBasis
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

class CheckInContextRepositoryTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val dayStart = DateUtils.atTimeMillis(day, 0, 0)
    private val wake = DateUtils.atTimeMillis(day, 7, 0)
    private val atEight = DateUtils.atTimeMillis(day, 8, 0)
    private val atTen = DateUtils.atTimeMillis(day, 10, 0)

    @Test
    fun savedCheckInStoresAnUnreviewedWindowAndDoesNotRewriteItWhenBackdated() {
        val dao = InMemoryCheckInDao()
        var wakeTime: Long? = wake
        val repository = CheckInRepository(dao) { _ -> wakeTime }
        val later = runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 6)),
                trigger = "manual",
                recordedAt = atTen,
                localDate = localDate,
                id = "ten",
            )
        }
        val original = runBlocking { repository.getContextWindow(later.record.id) }!!
        assertEquals(wake, original.windowStartAt)
        assertEquals(atTen, original.windowEndAt)
        assertEquals(CheckInContextStartBasis.WAKE_TIME, original.startBasis)
        assertNull(original.previousCheckInId)
        assertNull(original.completedAt)
        val categories = runBlocking { repository.getContextCategories(later.record.id) }
        assertEquals(CheckInContextCategories.ALL, categories.map { it.category })
        assertTrue(categories.all { it.status == CheckInContextStatus.NONE && it.reviewedAt == null })

        wakeTime = null
        runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 4)),
                trigger = "manual",
                recordedAt = atEight,
                localDate = localDate,
                id = "eight",
            )
        }
        val unchanged = runBlocking { repository.getContextWindow("ten") }!!
        assertEquals(original, unchanged)
        val earlier = runBlocking { repository.getContextWindow("eight") }!!
        assertEquals(dayStart, earlier.windowStartAt)
        assertEquals(atEight, earlier.windowEndAt)
        assertEquals(CheckInContextStartBasis.DAY_START, earlier.startBasis)
        assertNull(earlier.previousCheckInId)
    }

    @Test
    fun explicitEmptyActivitySelectionIsNoneWithoutAPlaceholderItem() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveActivitySelections(
                checkInId = "ten",
                selections = emptyList(),
                reportedAt = atTen,
            )
        }
        val activity = runBlocking { repository.getContextCategories("ten") }
            .first { it.category == CheckInContextCategories.ACTIVITY }
        assertEquals(CheckInContextStatus.NONE, activity.status)
        assertEquals(atTen, activity.reviewedAt)
        assertTrue(runBlocking { repository.getActivityItems("ten") }.isEmpty())
        val others = runBlocking { repository.getContextCategories("ten") }
            .filter { it.category != CheckInContextCategories.ACTIVITY }
        assertTrue(others.all { it.status == CheckInContextStatus.NONE })
    }

    @Test
    fun nonemptyActivitySelectionIsEntriesAndReplaceKeepsOnlyTheNewItems() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveActivitySelections(
                checkInId = "ten",
                selections = listOf(
                    CheckInActivitySelection("walk", "Walk"),
                    CheckInActivitySelection(null, " Custom "),
                ),
                reportedAt = atTen,
            )
            repository.saveActivitySelections(
                checkInId = "ten",
                selections = listOf(CheckInActivitySelection("read", "Read")),
                reportedAt = atTen + 1,
            )
        }
        val items = runBlocking { repository.getActivityItems("ten") }
        assertEquals(listOf("read" to "Read"), items.map { it.activityOptionId to it.activityName })
        assertEquals(atTen + 1, items.single().reportedAt)
        val activity = runBlocking { repository.getContextCategories("ten") }
            .first { it.category == CheckInContextCategories.ACTIVITY }
        assertEquals(CheckInContextStatus.ENTRIES, activity.status)
        assertEquals(atTen + 1, activity.reviewedAt)
    }

    @Test
    fun replacingOneCheckInDoesNotRemoveAnotherCheckInsActivityItems() {
        val dao = InMemoryCheckInDao()
        val repository = CheckInRepository(dao) { _ -> wake }
        runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 5)),
                trigger = "manual",
                recordedAt = atEight,
                localDate = localDate,
                id = "eight",
            )
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 6)),
                trigger = "manual",
                recordedAt = atTen,
                localDate = localDate,
                id = "ten",
            )
            repository.saveActivitySelections(
                checkInId = "eight",
                selections = listOf(CheckInActivitySelection("walk", "Walk")),
                reportedAt = atEight,
            )
            repository.saveActivitySelections(
                checkInId = "ten",
                selections = listOf(CheckInActivitySelection("read", "Read")),
                reportedAt = atTen,
            )
        }
        val tenWindow = runBlocking { repository.getContextWindow("ten") }!!
        assertEquals("eight", tenWindow.previousCheckInId)
        assertEquals(atEight, tenWindow.windowStartAt)
        assertEquals(CheckInContextStartBasis.PREVIOUS_CHECK_IN, tenWindow.startBasis)
        assertEquals(
            listOf("walk"),
            runBlocking { repository.getActivityItems("eight") }.map { it.activityOptionId },
        )
        assertEquals(
            listOf("read"),
            runBlocking { repository.getActivityItems("ten") }.map { it.activityOptionId },
        )
    }

    @Test
    fun duplicateActivityOptionsAreRejected() {
        val repository = repositoryWithCheckIn()
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveActivitySelections(
                    checkInId = "ten",
                    selections = listOf(
                        CheckInActivitySelection("walk", "Walk"),
                        CheckInActivitySelection("walk", "Walk again"),
                    ),
                    reportedAt = atTen,
                )
            }
        }
        assertTrue(error.message!!.contains("Duplicate"))
        assertEquals(
            CheckInContextStatus.NONE,
            runBlocking { repository.getContextCategories("ten") }
                .first { it.category == CheckInContextCategories.ACTIVITY }
                .status,
        )
    }

    @Test
    fun blankActivityNamesAreRejected() {
        val repository = repositoryWithCheckIn()
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveActivitySelections(
                    checkInId = "ten",
                    selections = listOf(CheckInActivitySelection("walk", "  ")),
                    reportedAt = atTen,
                )
            }
        }
        assertTrue(error.message!!.contains("blank"))
    }

    @Test
    fun adjustingTimesUpdatesOnlyThatEntry() {
        val dao = InMemoryCheckInDao()
        val moved = mutableListOf<Triple<String, Long, String>>()
        val repository = CheckInRepository(
            dao = dao,
            moodClock = MoodSampleClock { id, recordedAt, date ->
                moved += Triple(id, recordedAt, date)
            },
            wakeTimes = { _ -> wake },
        )
        val atThree = DateUtils.atTimeMillis(day, 15, 0)
        val atEleven = DateUtils.atTimeMillis(day, 11, 0)
        val atNine = DateUtils.atTimeMillis(day, 9, 0)
        runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 6)),
                trigger = "manual",
                recordedAt = atTen,
                localDate = localDate,
                id = "ten",
                completedAt = atTen + 5,
            )
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 4)),
                trigger = "manual",
                recordedAt = atThree,
                localDate = localDate,
                id = "three",
            )
        }
        val beforeNeighbor = runBlocking { repository.getContextWindow("three") }!!
        val endResult = runBlocking {
            repository.adjustIntervalTime(
                checkInId = "ten",
                field = IntervalTimeField.END,
                millis = atEleven,
                moodSampleId = "mood-ten",
            )
        }
        assertEquals(IntervalTimeAdjustment.Updated, endResult)
        val afterEnd = runBlocking { repository.getContextWindow("ten") }!!
        assertEquals(wake, afterEnd.windowStartAt)
        assertEquals(atEleven, afterEnd.windowEndAt)
        assertEquals(CheckInContextStartBasis.WAKE_TIME, afterEnd.startBasis)
        assertNull(afterEnd.previousCheckInId)
        assertEquals(atTen + 5, afterEnd.completedAt)
        assertEquals(atEleven, runBlocking { dao.getCheckIn("ten") }!!.recordedAt)
        assertEquals(localDate, runBlocking { dao.getCheckIn("ten") }!!.localDate)
        assertEquals(listOf(Triple("mood-ten", atEleven, localDate)), moved)
        assertEquals(beforeNeighbor, runBlocking { repository.getContextWindow("three") })

        val startResult = runBlocking {
            repository.adjustIntervalTime(
                checkInId = "ten",
                field = IntervalTimeField.START,
                millis = atNine,
                moodSampleId = "mood-ten",
            )
        }
        assertEquals(IntervalTimeAdjustment.Updated, startResult)
        val afterStart = runBlocking { repository.getContextWindow("ten") }!!
        assertEquals(atNine, afterStart.windowStartAt)
        assertEquals(atEleven, afterStart.windowEndAt)
        assertEquals(CheckInContextStartBasis.WAKE_TIME, afterStart.startBasis)
        assertEquals(1, moved.size)

        val rejected = runBlocking {
            repository.adjustIntervalTime(
                checkInId = "ten",
                field = IntervalTimeField.END,
                millis = atNine - 1,
                moodSampleId = "mood-ten",
            )
        }
        assertEquals(IntervalTimeAdjustment.StartAfterEnd, rejected)
        assertEquals(afterStart, runBlocking { repository.getContextWindow("ten") })
        assertEquals(atEleven, runBlocking { dao.getCheckIn("ten") }!!.recordedAt)
        assertEquals(1, moved.size)
    }

    @Test
    fun activityWritesRequireAContextWindow() {
        val repository = CheckInRepository(InMemoryCheckInDao())
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveActivitySelections(
                    checkInId = "missing",
                    selections = listOf(CheckInActivitySelection("walk", "Walk")),
                    reportedAt = atTen,
                )
            }
        }
        assertTrue(error.message!!.contains("does not exist"))
    }

    private fun repositoryWithCheckIn(): CheckInRepository {
        val repository = CheckInRepository(InMemoryCheckInDao()) { _ -> wake }
        runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 3)),
                trigger = "manual",
                recordedAt = atTen,
                localDate = localDate,
                id = "ten",
            )
        }
        return repository
    }
}
