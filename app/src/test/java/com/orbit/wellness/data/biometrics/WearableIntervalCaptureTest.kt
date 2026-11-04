package com.orbit.wellness.data.biometrics

import com.orbit.wellness.data.CheckInAxisInput
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.CheckInWakeTimeSource
import com.orbit.wellness.data.InMemoryCheckInDao
import com.orbit.wellness.data.KeepFirstWearableCapture
import com.orbit.wellness.data.SleepRepository
import com.orbit.wellness.data.WearableCaptureScheduler
import com.orbit.wellness.data.intervalCaptureWorkName
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.worker.intervalToCapture
import com.orbit.wellness.data.local.SleepEvent
import com.orbit.wellness.data.local.SleepEventDao
import com.orbit.wellness.util.DateUtils
import com.orbit.wellness.worker.BiometricSyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WearableIntervalCaptureTest {
    @Test
    fun completedCheckInSchedulesOneCaptureAndDiscardSchedulesNone() = runBlocking {
        val captures = KeepFirstWearableCapture()
        val repository = repository(captures)
        val draft = repository.saveCheckIn(
            axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 4)),
            trigger = "manual",
            recordedAt = 1_000,
            id = "draft",
        )
        repository.discardCheckIn(draft.record.id, null)
        assertTrue(captures.requestedIds.isEmpty())

        val saved = repository.saveCheckIn(
            axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 6)),
            trigger = "manual",
            recordedAt = 2_000,
            id = "saved",
            completedAt = 2_500,
        )
        repository.completeContext(saved.record.id, completedAt = 9_000)
        assertEquals(setOf("saved"), captures.requestedIds)
        assertEquals(intervalCaptureWorkName("saved"), "wearable_interval_capture:saved")
    }

    @Test
    fun schedulerFailureLeavesTheCompletedCheckInIntact() = runBlocking {
        val repository = repository(WearableCaptureScheduler { error("work manager down") })
        val saved = repository.saveCheckIn(
            axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 5)),
            trigger = "manual",
            recordedAt = 3_000,
            id = "kept",
            completedAt = 3_100,
        )
        val window = repository.getContextWindow(saved.record.id)
        assertEquals("kept", window?.checkInId)
        assertEquals(3_100L, window?.completedAt)
    }

    @Test
    fun captureUsesStoredBoundsForEveryStartBasis() = runBlocking {
        val reader = SpyReader()
        val dao = MemoryWearableDao()
        val repository = BiometricRepository(reader, dao)
        val windows = listOf(
            window(10, 20, CheckInContextStartBasis.PREVIOUS_CHECK_IN, previous = "earlier"),
            window(30, 40, CheckInContextStartBasis.WAKE_TIME),
            window(50, 60, CheckInContextStartBasis.DAY_START),
        )
        windows.forEach { repository.captureInterval(it) }
        assertEquals(
            windows.map { it.windowStartAt to it.windowEndAt },
            reader.intervals,
        )
    }

    @Test
    fun missingOrUnfinishedWindowIsNotCaptured() {
        assertNull(intervalToCapture(null))
        val unfinished = window(1, 2, CheckInContextStartBasis.DAY_START)
        assertNull(intervalToCapture(unfinished))
        val completed = unfinished.copy(completedAt = 3)
        assertEquals(completed, intervalToCapture(completed))
        assertEquals(unfinished.windowStartAt, intervalToCapture(completed)?.windowStartAt)
        assertEquals(unfinished.windowEndAt, intervalToCapture(completed)?.windowEndAt)
    }

    @Test
    fun exactAggregatesKeepTheRequestedIntervalAndEmptyResultsWriteNothing() = runBlocking {
        val start = Instant.ofEpochMilli(1_000)
        val end = Instant.ofEpochMilli(5_000)
        val steps = stepsAggregate(0, start, end, end)
        val average = heartRateAverage(64, start, end, end)
        assertEquals(0.0, steps.value, 0.0)
        assertEquals(1_000L, steps.observedStartAt)
        assertEquals(5_000L, steps.observedEndAt)
        assertEquals(1_000L, average.observedStartAt)
        assertEquals(5_000L, average.observedEndAt)
        assertEquals(WearableMetric.STEPS, steps.metric)
        assertEquals(WearableMetric.HEART_RATE_AVERAGE, average.metric)
        assertTrue(observedInside(1_000, 1_000, 5_000))
        assertTrue(!observedInside(5_000, 1_000, 5_000))

        val reader = SpyReader(result = WearableReadResult(emptyList(), emptyList()))
        val dao = MemoryWearableDao()
        val before = window(1_000, 5_000, CheckInContextStartBasis.WAKE_TIME)
        val result = BiometricRepository(reader, dao).captureInterval(before)
        assertTrue(result.observations.isEmpty())
        assertTrue(dao.rows.isEmpty())
        assertEquals(before, before.copy())
    }

    @Test
    fun failedIntervalImportDoesNotWriteRows() = runBlocking {
        val reader = SpyReader(fail = true)
        val dao = MemoryWearableDao()
        val window = window(1_000, 2_000, CheckInContextStartBasis.DAY_START)
        val result = BiometricRepository(reader, dao).captureInterval(window)
        assertTrue(result.error != null)
        assertTrue(dao.rows.isEmpty())
        assertEquals(1_000L, window.windowStartAt)
        assertEquals(2_000L, window.windowEndAt)
    }

    @Test
    fun laterSleepEntryDoesNotChangeTheStoredContextWindow() = runBlocking {
        val checkIns = InMemoryCheckInDao()
        val repository = CheckInRepository(
            dao = checkIns,
            wakeTimes = CheckInWakeTimeSource { null },
        )
        val day = LocalDate.of(2026, 11, 3)
        val localDate = DateUtils.formatLocalDate(day)
        repository.saveCheckIn(
            axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 5)),
            trigger = "manual",
            recordedAt = DateUtils.atTimeMillis(day, 9, 0),
            localDate = localDate,
            id = "morning",
            completedAt = DateUtils.atTimeMillis(day, 9, 1),
        )
        val before = repository.getContextWindow("morning")
        SleepRepository(MemorySleepDao()).logSleep(
            bedtime = DateUtils.atTimeMillis(day.minusDays(1), 23, 0),
            wakeTime = DateUtils.atTimeMillis(day, 7, 0),
            sleepQuality = 6,
            localDate = day,
        )
        assertEquals(before, repository.getContextWindow("morning"))
        assertEquals(CheckInContextStartBasis.DAY_START, before?.startBasis)
    }

    @Test
    fun localDayStartUsesTheAnchorDateRatherThanTheClock() {
        val zone = ZoneId.of("America/Los_Angeles")
        val today = LocalDate.now(zone)
        val anchorDate = today.minusDays(1)
        val anchor = anchorDate.atTime(22, 15).atZone(zone).toInstant()
        val start = DateUtils.localDayStart(anchor, zone)
        assertEquals(anchorDate, start.atZone(zone).toLocalDate())
        assertNotEquals(today, start.atZone(zone).toLocalDate())
        assertEquals(anchorDate.atStartOfDay(zone).toInstant(), start)
    }

    @Test
    fun dailySyncReplacesTheHourlyWorkName() {
        assertEquals("daily_wearable_sync", BiometricSyncWorker.UNIQUE_WORK_NAME)
        assertEquals("hourly_biometric_sync", BiometricSyncWorker.LEGACY_HOURLY_WORK_NAME)
        assertEquals(1, BiometricSyncWorker.REPEAT_INTERVAL.toDays())
        assertNotEquals(BiometricSyncWorker.UNIQUE_WORK_NAME, BiometricSyncWorker.LEGACY_HOURLY_WORK_NAME)
        val worker = source(
            "src/main/java/com/orbit/wellness/worker/BiometricSyncWorker.kt",
            "app/src/main/java/com/orbit/wellness/worker/BiometricSyncWorker.kt",
        ).readText()
        assertTrue(worker.contains("cancelUniqueWork(LEGACY_HOURLY_WORK_NAME)"))
        assertTrue(worker.contains("TimeUnit.DAYS"))
    }

    private fun repository(captures: WearableCaptureScheduler): CheckInRepository {
        return CheckInRepository(
            dao = InMemoryCheckInDao(),
            wakeTimes = CheckInWakeTimeSource { null },
            captures = captures,
        )
    }

    private fun window(
        start: Long,
        end: Long,
        basis: String,
        previous: String? = null,
    ): CheckInContextWindow {
        return CheckInContextWindow(
            checkInId = "check-$start",
            previousCheckInId = previous,
            windowStartAt = start,
            windowEndAt = end,
            startBasis = basis,
        )
    }

    private fun source(vararg paths: String) = paths.map { java.io.File(it) }.first { it.isFile }

    private class SpyReader(
        var result: WearableReadResult? = WearableReadResult(emptyList(), emptyList()),
        private val fail: Boolean = false,
    ) : WearableReadGateway {
        val intervals = mutableListOf<Pair<Long, Long>>()

        override fun sdkStatus(): Int = 3
        override fun isAvailable(): Boolean = true
        override suspend fun hasAllReadPermissions(): Boolean = true
        override suspend fun hasBackgroundReadPermission(): Boolean = true
        override suspend fun readObservations(now: Instant): WearableReadResult? = result
        override suspend fun readInterval(start: Instant, end: Instant): WearableReadResult? {
            intervals += start.toEpochMilli() to end.toEpochMilli()
            if (fail) error("health connect unavailable")
            return result
        }
        override suspend fun readLatestSleepSession(now: Instant): SleepSessionSuggestion? = null
    }

    private class MemoryWearableDao : com.orbit.wellness.data.local.WearableObservationDao {
        val rows = linkedMapOf<String, com.orbit.wellness.data.local.WearableObservation>()
        override suspend fun upsertAll(observations: List<com.orbit.wellness.data.local.WearableObservation>) {
            observations.forEach { rows[it.id] = it }
        }
        override fun observeAll(): Flow<List<com.orbit.wellness.data.local.WearableObservation>> = flowOf(rows.values.toList())
    }

    private class MemorySleepDao : SleepEventDao {
        private val rows = linkedMapOf<String, SleepEvent>()
        override suspend fun insert(event: SleepEvent) {
            rows[event.localDate] = event
        }
        override suspend fun update(event: SleepEvent) {
            rows[event.localDate] = event
        }
        override suspend fun deleteById(id: String) {
            rows.entries.removeIf { it.value.id == id }
        }
        override fun observeForDate(localDate: String): Flow<SleepEvent?> = flowOf(rows[localDate])
        override suspend fun getForDate(localDate: String): SleepEvent? = rows[localDate]
        override fun observeBetween(startDate: String, endDate: String): Flow<List<SleepEvent>> = flowOf(emptyList())
        override suspend fun getLatest(): SleepEvent? = rows.values.maxByOrNull { it.wakeTime }
    }
}
