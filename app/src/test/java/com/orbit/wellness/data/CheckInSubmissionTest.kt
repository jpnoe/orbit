package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.ui.checkin.checkInContextNavigationId
import com.orbit.wellness.ui.context.CONTEXTUAL_ACTIVITY_NONE_LABEL
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * These tests check the submission shape with an in-memory DAO.
 * [RestoringCheckInTransaction] restores that fake when the mood insert throws.
 * It does not prove that SQLite would roll back a crash inside Room.withTransaction.
 */
class CheckInSubmissionTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val atEight = DateUtils.atTimeMillis(day, 8, 0)

    @Test
    fun explicitMoodWritesTheCheckInAndTheLegacyMoodSampleTogether() {
        val moods = RecordingMoodSamples()
        val repository = CheckInRepository(InMemoryCheckInDao(), moodSamples = moods)
        val saved = runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(
                    CheckInAxisInput(DefaultCheckInAxes.MOOD, 7),
                    CheckInAxisInput(DefaultCheckInAxes.ENERGY, 4),
                ),
                trigger = "manual",
                note = "  after lunch  ",
                recordedAt = atEight,
                localDate = localDate,
                id = "eight",
                legacyMood = LegacyCheckInMood(
                    score = 7,
                    note = "  after lunch  ",
                    emotion = " calm ",
                    recordedAt = atEight,
                ),
            )
        }
        assertEquals("eight", saved.record.id)
        val sample = moods.samples.single()
        assertEquals(localDate, sample.localDate)
        assertEquals(7, sample.moodScore)
        assertEquals("after lunch", sample.note)
        assertEquals("calm", sample.emotion)
        assertEquals(atEight, sample.recordedAt)
        assertEquals(MoodSample.SOURCE_CHECK_IN, sample.source)
        assertEquals(4, runBlocking { repository.getContextCategories(saved.record.id) }.size)
        assertEquals(7f, committedMoodAfterSubmission(explicitScore = 7, submitted = true))
    }

    @Test
    fun untouchedMoodWritesTheCheckInWithoutAMoodSampleOrCommittedMood() {
        val moods = RecordingMoodSamples()
        val repository = CheckInRepository(InMemoryCheckInDao(), moodSamples = moods)
        val saved = runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 4)),
                trigger = "manual",
                recordedAt = atEight,
                localDate = localDate,
                id = "eight",
            )
        }
        assertTrue(moods.samples.isEmpty())
        assertNull(committedMoodAfterSubmission(explicitScore = null, submitted = true))
        assertEquals("eight", runBlocking { repository.getContextWindow(saved.record.id) }!!.checkInId)
    }

    @Test
    fun moodInsertionFailureLeavesNoPartialSubmissionAndNoNavigation() {
        val dao = InMemoryCheckInDao()
        val moods = RecordingMoodSamples().apply { failNextInsert = true }
        val repository = CheckInRepository(
            dao = dao,
            moodSamples = moods,
            transaction = RestoringCheckInTransaction(dao, moods.samples),
        )
        var navigationId: String? = "not-sent"
        try {
            runBlocking {
                repository.saveCheckIn(
                    axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 7)),
                    trigger = "manual",
                    recordedAt = atEight,
                    localDate = localDate,
                    id = "eight",
                    legacyMood = LegacyCheckInMood(
                        score = 7,
                        note = "",
                        emotion = "",
                        recordedAt = atEight,
                    ),
                )
            }
            navigationId = checkInContextNavigationId(
                validationFailed = false,
                savedCheckInId = "eight",
                persistenceFailed = false,
            )
        } catch (error: IllegalStateException) {
            assertEquals("mood insert failed", error.message)
            navigationId = checkInContextNavigationId(
                validationFailed = false,
                savedCheckInId = "eight",
                persistenceFailed = true,
            )
        }
        assertNull(navigationId)
        assertNull(runBlocking { dao.getLatest() })
        assertNull(runBlocking { dao.getContextWindow("eight") })
        assertTrue(runBlocking { dao.getContextCategories("eight") }.isEmpty())
        assertTrue(moods.samples.isEmpty())
        assertNull(committedMoodAfterSubmission(explicitScore = 7, submitted = false))
    }

    @Test
    fun moodCacheFailureAfterRoomSuccessStillCountsAsSaved() {
        var commits = 0
        val update = updateMoodCacheAfterSubmission(explicitScore = 7) {
            commits += 1
            error("cache failed")
        }
        assertTrue(update is MoodCacheUpdate.Failed)
        assertEquals("cache failed", (update as MoodCacheUpdate.Failed).error.message)
        assertEquals(1, commits)
        assertEquals(
            "eight",
            checkInContextNavigationId(
                validationFailed = false,
                savedCheckInId = "eight",
                persistenceFailed = false,
            ),
        )
    }

    @Test
    fun untouchedMoodDoesNotTouchTheMoodCache() {
        var commits = 0
        val update = updateMoodCacheAfterSubmission(explicitScore = null) {
            commits += 1
        }
        assertTrue(update is MoodCacheUpdate.NotRequested)
        assertEquals(0, commits)
    }

    @Test
    fun noActivitiesToReportKeepsTheExplicitNoneSave() {
        assertEquals("No activities to report", CONTEXTUAL_ACTIVITY_NONE_LABEL)
        val repository = CheckInRepository(InMemoryCheckInDao())
        runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 6)),
                trigger = "manual",
                recordedAt = atEight,
                localDate = localDate,
                id = "eight",
            )
            repository.saveActivitySelections(
                checkInId = "eight",
                selections = emptyList(),
                reportedAt = atEight,
            )
        }
        val activity = runBlocking { repository.getContextCategories("eight") }
            .first { it.category == CheckInContextCategories.ACTIVITY }
        assertEquals(CheckInContextStatus.NONE, activity.status)
        assertTrue(runBlocking { repository.getActivityItems("eight") }.isEmpty())
    }

    @Test
    fun discardRemovesTheCheckInContextAndLegacyMoodSample() {
        val dao = InMemoryCheckInDao()
        val moods = RecordingMoodSamples()
        val repository = CheckInRepository(
            dao = dao,
            moodSamples = moods,
            moodRemover = MoodSampleRemover { id -> moods.delete(id) },
        )
        val saved = runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 7)),
                trigger = "manual",
                note = "note",
                recordedAt = atEight,
                localDate = localDate,
                id = "eight",
                legacyMood = LegacyCheckInMood(
                    score = 7,
                    note = "note",
                    emotion = "Calm",
                    recordedAt = atEight,
                ),
            )
        }
        runBlocking {
            repository.saveActivitySelections(
                checkInId = "eight",
                selections = listOf(CheckInActivitySelection("walk", "Walk")),
                reportedAt = atEight,
            )
            repository.discardCheckIn(saved.record.id, saved.legacyMoodSampleId)
        }
        assertNull(runBlocking { dao.getLatest() })
        assertNull(runBlocking { dao.getContextWindow("eight") })
        assertTrue(runBlocking { dao.getContextCategories("eight") }.isEmpty())
        assertTrue(runBlocking { dao.getActivityItems("eight") }.isEmpty())
        assertTrue(moods.samples.isEmpty())
    }

    @Test
    fun previewWritesNothingAndSaveCanFinishReviewedCategoriesTogether() {
        val dao = InMemoryCheckInDao()
        val repository = CheckInRepository(dao)
        val preview = runBlocking { repository.previewContext(atEight, "pending") }
        assertNull(runBlocking { dao.getLatest() })
        assertEquals(atEight, preview.windowEndAt)
        runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 6)),
                trigger = "manual",
                recordedAt = atEight,
                localDate = localDate,
                id = "eight",
                activitySelections = listOf(CheckInActivitySelection("walk", "Walk")),
                workoutSelections = emptyList(),
                completedAt = atEight + 1,
            )
        }
        val categories = runBlocking { repository.getContextCategories("eight") }
        assertEquals(
            CheckInContextStatus.ENTRIES,
            categories.first { it.category == CheckInContextCategories.ACTIVITY }.status,
        )
        assertEquals(
            CheckInContextStatus.NONE,
            categories.first { it.category == CheckInContextCategories.WORKOUT }.status,
        )
        assertEquals(
            CheckInContextStatus.UNREVIEWED,
            categories.first { it.category == CheckInContextCategories.USAGE }.status,
        )
        assertEquals(
            CheckInContextStatus.UNREVIEWED,
            categories.first { it.category == CheckInContextCategories.DIET }.status,
        )
        assertEquals(atEight + 1, runBlocking { repository.getContextWindow("eight") }!!.completedAt)
        assertEquals(listOf("Walk"), runBlocking { repository.getActivityItems("eight") }.map { it.activityName })
        assertTrue(runBlocking { repository.getWorkoutItems("eight") }.isEmpty())
    }
}

private class RecordingMoodSamples : MoodSampleWriter {
    val samples = mutableListOf<MoodSample>()
    var failNextInsert = false

    override suspend fun insert(sample: MoodSample) {
        if (failNextInsert) error("mood insert failed")
        samples += sample
    }

    suspend fun delete(id: String) {
        samples.removeAll { it.id == id }
    }
}

/**
 * Restores the in-memory check-in DAO and mood list when the block throws.
 * This is a unit-test stand-in for Room.withTransaction, not a SQLite rollback proof.
 */
private class RestoringCheckInTransaction(
    private val dao: InMemoryCheckInDao,
    private val moods: MutableList<MoodSample>,
) : DatabaseTransaction {
    override suspend fun <R> run(block: suspend () -> R): R {
        val snapshot = dao.snapshot()
        val moodSnapshot = moods.toList()
        return try {
            block()
        } catch (error: Throwable) {
            dao.restore(snapshot)
            moods.clear()
            moods.addAll(moodSnapshot)
            throw error
        }
    }
}
