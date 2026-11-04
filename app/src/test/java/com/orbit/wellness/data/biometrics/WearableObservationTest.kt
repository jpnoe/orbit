package com.orbit.wellness.data.biometrics

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.orbit.wellness.data.WearableObservationRepository
import com.orbit.wellness.data.local.SleepEvent
import com.orbit.wellness.data.local.WearableMethod
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableObservationDao
import com.orbit.wellness.data.local.WearableTemporalKind
import com.orbit.wellness.data.local.aggregatedWindows
import com.orbit.wellness.data.wearableCoverage
import com.orbit.wellness.domain.checkin.TemporalWindowProjector
import com.orbit.wellness.ui.wearable.wearableMetricLabelRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

class WearableObservationTest {
    @Test
    fun restingHeartRateStaysDistinctFromAverageAndSample() {
        val imported = Instant.parse("2026-11-04T18:00:00Z")
        val restingAt = Instant.parse("2026-11-04T12:00:00Z")
        val sampleAt = Instant.parse("2026-11-04T17:40:00Z")
        val averageStart = Instant.parse("2026-11-04T12:00:00Z")
        val resting = restingHeartRate(
            record = RestingHeartRateRecord(
                time = restingAt,
                zoneOffset = ZoneOffset.UTC,
                beatsPerMinute = 58,
                metadata = Metadata.manualEntryWithId("rhr-1"),
            ),
            importedAt = imported,
        )
        val average = heartRateAverage(
            beatsPerMinute = 71,
            start = averageStart,
            end = imported,
            importedAt = imported,
        )
        val sample = heartRateSample(
            record = HeartRateRecord(
                startTime = averageStart,
                startZoneOffset = ZoneOffset.UTC,
                endTime = imported,
                endZoneOffset = ZoneOffset.UTC,
                samples = listOf(HeartRateRecord.Sample(sampleAt, 80)),
                metadata = Metadata.manualEntryWithId("hr-parent"),
            ),
            sample = HeartRateRecord.Sample(sampleAt, 80),
            importedAt = imported,
        )

        assertEquals(WearableMetric.RESTING_HEART_RATE, resting.metric)
        assertEquals(WearableMethod.RESTING_HEART_RATE_RECORD, resting.method)
        assertEquals(WearableTemporalKind.POINT, resting.temporalKind)
        assertEquals(restingAt.toEpochMilli(), resting.observedStartAt)
        assertEquals(restingAt.toEpochMilli(), resting.observedEndAt)
        assertEquals("rhr-1", resting.sourceRecordId)

        assertEquals(WearableMetric.HEART_RATE_AVERAGE, average.metric)
        assertEquals(WearableMethod.HEART_RATE_AVERAGE, average.method)
        assertEquals(WearableTemporalKind.AGGREGATED_WINDOW, average.temporalKind)
        assertEquals(averageStart.toEpochMilli(), average.observedStartAt)
        assertEquals(imported.toEpochMilli(), average.observedEndAt)
        assertNull(average.sourceRecordId)

        assertEquals(WearableMetric.HEART_RATE_SAMPLE, sample.metric)
        assertEquals(WearableMethod.HEART_RATE_SAMPLE, sample.method)
        assertEquals(WearableTemporalKind.POINT, sample.temporalKind)
        assertEquals(sampleAt.toEpochMilli(), sample.observedStartAt)
        assertEquals("hr-parent", sample.sourceRecordId)
        assertNotEquals(resting.metric, average.metric)
        assertNotEquals(resting.metric, sample.metric)
        assertNotEquals(
            wearableMetricLabelRes(WearableMetric.RESTING_HEART_RATE),
            wearableMetricLabelRes(WearableMetric.HEART_RATE_AVERAGE),
        )
        assertNotEquals(
            wearableMetricLabelRes(WearableMetric.RESTING_HEART_RATE),
            wearableMetricLabelRes(WearableMetric.HEART_RATE_SAMPLE),
        )
    }

    @Test
    fun sleepKeepsTheSessionIntervalAndDuration() {
        val start = Instant.parse("2026-11-03T06:00:00Z")
        val end = Instant.parse("2026-11-03T14:30:00Z")
        val session = sleepSession(
            record = SleepSessionRecord(
                startTime = start,
                startZoneOffset = ZoneOffset.UTC,
                endTime = end,
                endZoneOffset = ZoneOffset.UTC,
                metadata = Metadata.manualEntryWithId("sleep-1"),
                title = null,
                notes = null,
                stages = emptyList(),
            ),
            importedAt = Instant.parse("2026-11-04T18:00:00Z"),
        )
        assertEquals(WearableMetric.SLEEP_DURATION, session.metric)
        assertEquals(WearableTemporalKind.MEASURED_INTERVAL, session.temporalKind)
        assertEquals(start.toEpochMilli(), session.observedStartAt)
        assertEquals(end.toEpochMilli(), session.observedEndAt)
        assertEquals(8.5, session.value, 0.0001)
        assertEquals("sleep-1", session.sourceRecordId)
    }

    @Test
    fun stepsKeepTheRequestedCumulativeWindow() {
        val start = Instant.parse("2026-11-04T07:00:00Z")
        val end = Instant.parse("2026-11-04T18:00:00Z")
        val steps = stepsAggregate(4_200, start, end, end)
        assertEquals(WearableMetric.STEPS, steps.metric)
        assertEquals(WearableMethod.AGGREGATE_TOTAL, steps.method)
        assertEquals(WearableTemporalKind.AGGREGATED_WINDOW, steps.temporalKind)
        assertEquals(start.toEpochMilli(), steps.observedStartAt)
        assertEquals(end.toEpochMilli(), steps.observedEndAt)
        assertEquals(4_200.0, steps.value, 0.0)
        assertNull(steps.sourceRecordId)
    }

    @Test
    fun overlappingCumulativeStepsAreNotSummed() {
        val midnight = Instant.parse("2026-11-04T07:00:00Z")
        val morning = stepsAggregate(4_000, midnight, Instant.parse("2026-11-04T17:00:00Z"), midnight)
        val afternoon = stepsAggregate(9_000, midnight, Instant.parse("2026-11-04T21:00:00Z"), midnight)
        val windows = aggregatedWindows(listOf(morning, afternoon))
        assertEquals(listOf(4_000.0, 9_000.0), windows.map { it.value })
        assertTrue(windows.none { it.value == 13_000.0 })
        val coverage = wearableCoverage(windows)
        assertEquals(2, coverage.countByMetric.single { it.metric == WearableMetric.STEPS }.count)
    }

    @Test
    fun missingMetricDoesNotBecomeZero() {
        val start = Instant.parse("2026-11-04T07:00:00Z")
        val end = Instant.parse("2026-11-04T18:00:00Z")
        assertNull(stepsAggregateOrAbsent(null, start, end, end))
        assertNull(heartRateAverageOrAbsent(null, start, end, end))
        val zero = stepsAggregateOrAbsent(0, start, end, end)
        assertEquals(0.0, zero!!.value, 0.0)
    }

    @Test
    fun pointMembershipUsesMeasurementTime() {
        val window = TemporalWindowProjector.explicit(1_000, 2_000, id = "window")
        val measuredInside = point(at = 1_500, importedAt = 5_000, recordId = "inside")
        val importedInside = point(at = 500, importedAt = 1_500, recordId = "outside")
        val atEnd = point(at = 2_000, importedAt = 1_500, recordId = "end")
        val before = listOf(measuredInside, importedInside, atEnd)
        val reference = TemporalWindowProjector.projectWearables(before, window)
        assertEquals(listOf(measuredInside.id), reference.pointObservationIds)
        assertTrue(reference.containedObservationIds.isEmpty())
        assertSame(measuredInside, before[0])
    }

    @Test
    fun intervalObservationsAreContainedOverlappingOrOutside() {
        val window = TemporalWindowProjector.explicit(1_000, 2_000, id = "window")
        val contained = interval(1_200, 1_800, "contained")
        val overlapping = interval(800, 1_200, "overlapping")
        val spanning = interval(500, 2_500, "spanning")
        val outside = interval(2_000, 2_400, "outside")
        val exact = interval(1_000, 2_000, "exact")
        val reference = TemporalWindowProjector.projectWearables(
            listOf(contained, overlapping, spanning, outside, exact),
            window,
        )
        assertEquals(listOf(exact.id, contained.id), reference.containedObservationIds)
        assertEquals(listOf(spanning.id, overlapping.id), reference.overlappingObservationIds)
        assertTrue(outside.id !in reference.containedObservationIds)
        assertTrue(outside.id !in reference.overlappingObservationIds)
    }

    @Test
    fun repeatedSourceRecordIdsReplaceTheSameRow() = runBlocking {
        val dao = MemoryWearableDao()
        val first = point(at = 1_500, importedAt = 2_000, recordId = "hc-1", value = 58.0)
        val corrected = point(at = 1_500, importedAt = 3_000, recordId = "hc-1", value = 60.0)
        assertEquals(first.id, corrected.id)
        dao.upsertAll(listOf(first))
        dao.upsertAll(listOf(corrected))
        assertEquals(1, dao.rows.size)
        assertEquals(60.0, dao.rows.getValue(first.id).value, 0.0)
        assertEquals(3_000L, dao.rows.getValue(first.id).importedAt)
    }

    @Test
    fun manualSleepAndWearableSleepStaySeparate() = runBlocking {
        val manual = SleepEvent(
            id = "manual-sleep",
            localDate = "2026-11-04",
            bedtime = 1_000,
            wakeTime = 2_000,
            sleepQuality = 7,
        )
        val wearable = WearableObservation.create(
            metric = WearableMetric.SLEEP_DURATION,
            value = 8.0,
            unit = "h",
            observedStartAt = 1_000,
            observedEndAt = 2_000,
            temporalKind = WearableTemporalKind.MEASURED_INTERVAL,
            method = WearableMethod.SLEEP_SESSION,
            sourceRecordId = "hc-sleep",
            importedAt = 3_000,
        )
        val dao = MemoryWearableDao()
        val reader = FakeReader(WearableReadResult(listOf(wearable), emptyList()))
        val repository = BiometricRepository(reader, dao)
        val result = repository.sync()
        assertNull(result.error)
        assertEquals(0, reader.sleepPrefillReads)
        assertEquals(listOf(wearable.id), dao.rows.keys.toList())
        assertEquals(WearableObservation.SOURCE_HEALTH_CONNECT, dao.rows.getValue(wearable.id).source)
        assertEquals("manual-sleep", manual.id)
        assertTrue(manual.id !in dao.rows)
        assertNull(repository.suggestSleepFromHealthConnect())
        assertEquals(1, reader.sleepPrefillReads)
    }

    @Test
    fun syncDoesNotRunTheEncoderOrTopology() = runBlocking {
        val dao = MemoryWearableDao()
        val observation = point(at = 1_500, importedAt = 2_000, recordId = "hc-1")
        val repository = BiometricRepository(
            reader = FakeReader(WearableReadResult(listOf(observation), emptyList())),
            wearableObservationDao = dao,
            dormantEncoder = null,
            dormantTopology = null,
        )
        val result = repository.sync()
        assertNull(result.error)
        assertEquals(1, dao.rows.size)
        var encodeFailed = false
        try {
            repository.encodeOnly()
        } catch (_: IllegalStateException) {
            encodeFailed = true
        }
        assertTrue(encodeFailed)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun insightsCoverageCountsMeasurementsWithoutAnInterpretation() {
        val resting = point(at = 1_500, importedAt = 9_000, recordId = "rhr")
        val average = WearableObservation.create(
            metric = WearableMetric.HEART_RATE_AVERAGE,
            value = 70.0,
            unit = "bpm",
            observedStartAt = 1_000,
            observedEndAt = 4_000,
            temporalKind = WearableTemporalKind.AGGREGATED_WINDOW,
            method = WearableMethod.HEART_RATE_AVERAGE,
            importedAt = 8_000,
        )
        val coverage = wearableCoverage(listOf(resting, average))
        assertTrue(coverage.hasObservations)
        assertEquals(1, coverage.countByMetric.single { it.metric == WearableMetric.RESTING_HEART_RATE }.count)
        assertEquals(1, coverage.countByMetric.single { it.metric == WearableMetric.HEART_RATE_AVERAGE }.count)
        assertEquals(1_000L, coverage.earliestMeasurementAt)
        assertEquals(4_000L, coverage.latestMeasurementAt)
        assertEquals(9_000L, coverage.latestImportedAt)
        val strings = stringsFile().readText()
        assertTrue(strings.contains(">Resting heart rate<"))
        assertTrue(strings.contains(">Heart-rate average<"))
        assertTrue(strings.contains(">Heart-rate sample<"))
        assertTrue(strings.contains("These counts describe imported measurements."))
        val wearableCopy = strings.substringAfter("insights_wearable_heading")
            .substringBefore("health_connect_tab")
        assertTrue(!wearableCopy.contains("correlat", ignoreCase = true))
        assertTrue(!wearableCopy.contains("recommend", ignoreCase = true))
        assertTrue(!wearableCopy.contains("similar", ignoreCase = true))
        assertTrue(!wearableCopy.contains("effect", ignoreCase = true))
    }

    @Test
    fun repositoryEmitsStoredObservationsWithoutWritingCoverage() = runBlocking {
        val dao = MemoryWearableDao()
        val repository = WearableObservationRepository(dao)
        val observation = point(at = 1_500, importedAt = 2_000, recordId = "hc-1")
        dao.upsertAll(listOf(observation))
        val seen = repository.observe().first()
        assertEquals(listOf(observation.id), seen.map { it.id })
        assertEquals(1, dao.rows.size)
    }

    private fun point(
        at: Long,
        importedAt: Long,
        recordId: String,
        value: Double = 58.0,
    ): WearableObservation {
        return WearableObservation.create(
            metric = WearableMetric.RESTING_HEART_RATE,
            value = value,
            unit = "bpm",
            observedStartAt = at,
            observedEndAt = at,
            temporalKind = WearableTemporalKind.POINT,
            method = WearableMethod.RESTING_HEART_RATE_RECORD,
            sourceRecordId = recordId,
            importedAt = importedAt,
        )
    }

    private fun interval(start: Long, end: Long, recordId: String): WearableObservation {
        return WearableObservation.create(
            metric = WearableMetric.SLEEP_DURATION,
            value = (end - start) / 3_600_000.0,
            unit = "h",
            observedStartAt = start,
            observedEndAt = end,
            temporalKind = WearableTemporalKind.MEASURED_INTERVAL,
            method = WearableMethod.SLEEP_SESSION,
            sourceRecordId = recordId,
            importedAt = end,
        )
    }

    private fun stringsFile(): File {
        return listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }
    }

    private class MemoryWearableDao : WearableObservationDao {
        val rows = linkedMapOf<String, WearableObservation>()
        private val published = MutableStateFlow<List<WearableObservation>>(emptyList())

        override suspend fun upsertAll(observations: List<WearableObservation>) {
            observations.forEach { rows[it.id] = it }
            published.value = rows.values.toList()
        }

        override fun observeAll(): Flow<List<WearableObservation>> = published
    }

    private class FakeReader(
        private val result: WearableReadResult?,
    ) : WearableReadGateway {
        var sleepPrefillReads = 0

        override fun sdkStatus(): Int = 3

        override fun isAvailable(): Boolean = true

        override suspend fun hasAllReadPermissions(): Boolean = true

        override suspend fun hasBackgroundReadPermission(): Boolean = true

        override suspend fun readObservations(now: Instant): WearableReadResult? = result

        override suspend fun readLatestSleepSession(now: Instant): com.orbit.wellness.data.biometrics.SleepSessionSuggestion? {
            sleepPrefillReads += 1
            return null
        }
    }
}
