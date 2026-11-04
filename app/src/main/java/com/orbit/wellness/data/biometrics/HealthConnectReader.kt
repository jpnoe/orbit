package com.orbit.wellness.data.biometrics

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.orbit.wellness.data.local.WearableMethod
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind
import com.orbit.wellness.data.local.WearableUnit
import com.orbit.wellness.util.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class WearableReadResult(
    val observations: List<WearableObservation>,
    val notes: List<String>,
)

interface WearableReadGateway {
    fun sdkStatus(): Int
    fun isAvailable(): Boolean
    suspend fun hasAllReadPermissions(): Boolean
    suspend fun hasBackgroundReadPermission(): Boolean
    suspend fun readObservations(now: Instant = Instant.now()): WearableReadResult?
    suspend fun readInterval(start: Instant, end: Instant): WearableReadResult?
    suspend fun readLatestSleepSession(now: Instant = Instant.now()): SleepSessionSuggestion?
}

/**
 * Reads Health Connect into provenance-bearing [WearableObservation] rows.
 *
 * An aggregate total has no Health Connect record id. Its window is the range
 * requested here, and a later sync with a later end is a different row.
 * [HeartRateRecord.Sample] has no id of its own; the parent record id is kept
 * and the sample time is the measurement instant.
 * Sleep stages are not copied. The session start and end are.
 */
class HealthConnectReader(
    private val context: Context,
) : WearableReadGateway {
    override fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context.applicationContext)

    override fun isAvailable(): Boolean =
        sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    fun getClientOrNull(): HealthConnectClient? {
        if (!isAvailable()) return null
        return HealthConnectClient.getOrCreate(context.applicationContext)
    }

    override suspend fun hasAllReadPermissions(): Boolean {
        val client = getClientOrNull() ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(HealthConnectPermissions.READ_PERMISSIONS)
    }

    override suspend fun hasBackgroundReadPermission(): Boolean {
        val client = getClientOrNull() ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(HealthConnectPermissions.BACKGROUND_PERMISSIONS)
    }

    override suspend fun readObservations(now: Instant): WearableReadResult? {
        val client = getClientOrNull() ?: return null
        if (!hasAllReadPermissions()) return null

        val zone = ZoneId.systemDefault()
        val startOfDay = DateUtils.localDayStart(now, zone)
        val lookback48h = now.minus(48, ChronoUnit.HOURS)
        val lookback7d = now.minus(7, ChronoUnit.DAYS)
        val notes = mutableListOf<String>()
        val observations = mutableListOf<WearableObservation>()

        observations += readSteps(client, startOfDay, now, notes)
        observations += readHeartRate(client, lookback7d, now, notes)
        readLatestSleepSessionInternal(client, lookback48h, now, notes)?.let { session ->
            observations += session
        }

        val hasStepsOrHeart = observations.any { observation ->
            observation.metric == WearableMetric.STEPS ||
                observation.metric == WearableMetric.RESTING_HEART_RATE ||
                observation.metric == WearableMetric.HEART_RATE_AVERAGE ||
                observation.metric == WearableMetric.HEART_RATE_SAMPLE
        }
        if (!hasStepsOrHeart) notes += EMPTY_HC_HINT

        return WearableReadResult(observations = observations, notes = notes)
    }

    override suspend fun readInterval(start: Instant, end: Instant): WearableReadResult? {
        if (!start.isBefore(end)) {
            return WearableReadResult(
                observations = emptyList(),
                notes = listOf("Interval import skipped because the stored context window has no positive duration."),
            )
        }
        val client = getClientOrNull() ?: return null
        if (!hasAllReadPermissions()) return null
        val notes = mutableListOf<String>()
        val observations = mutableListOf<WearableObservation>()
        observations += readStepsAggregateOnly(client, start, end, notes)
        observations += readIntervalHeartRate(client, start, end, notes)
        return WearableReadResult(observations = observations, notes = notes)
    }

    override suspend fun readLatestSleepSession(now: Instant): SleepSessionSuggestion? {
        val client = getClientOrNull() ?: return null
        if (!hasAllReadPermissions()) return null
        val lookback48h = now.minus(48, ChronoUnit.HOURS)
        return readLatestSleepSessionInternal(client, lookback48h, now, mutableListOf())
            ?.let { observation ->
                SleepSessionSuggestion(
                    bedtimeMillis = observation.observedStartAt,
                    wakeTimeMillis = observation.observedEndAt,
                    durationHours = observation.value,
                )
            }
    }

    private suspend fun readSteps(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        notes: MutableList<String>,
    ): List<WearableObservation> {
        try {
            val aggregated = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )[StepsRecord.COUNT_TOTAL]
            stepsAggregateOrAbsent(
                count = aggregated,
                start = start,
                end = end,
                importedAt = end,
            )?.let { return listOf(it) }
        } catch (e: SecurityException) {
            notes += "Steps: permission denied."
            Log.w(TAG, "Steps aggregate security", e)
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Steps aggregate failed, trying records", e)
        }

        return try {
            val records = readAllRecords(
                client,
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            if (records.isEmpty()) {
                notes += "Steps: no records in Health Connect for today."
                emptyList()
            } else {
                records.mapNotNull { record ->
                    if (!record.endTime.isAfter(record.startTime)) {
                        notes += "Steps: skipped a record with no positive duration."
                        null
                    } else {
                        stepsRecord(record, importedAt = end)
                    }
                }
            }
        } catch (e: SecurityException) {
            notes += "Steps: permission denied."
            Log.w(TAG, "Steps records security", e)
            emptyList()
        } catch (e: Exception) {
            notes += "Steps: read failed (${e.message ?: "error"})."
            Log.w(TAG, "Steps records failed", e)
            emptyList()
        }
    }

    private suspend fun readHeartRate(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        notes: MutableList<String>,
    ): List<WearableObservation> {
        try {
            val resting = readAllRecords(
                client,
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            val latest = resting.maxByOrNull { it.time }
            if (latest != null) {
                return listOf(restingHeartRate(latest, importedAt = end))
            }
        } catch (e: SecurityException) {
            notes += "Resting heart rate: permission denied."
            Log.w(TAG, "RHR security", e)
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "RHR read failed", e)
        }

        val averageStart = end.minus(6, ChronoUnit.HOURS)
        try {
            val avg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(averageStart, end),
                ),
            )[HeartRateRecord.BPM_AVG]
            val average = heartRateAverageOrAbsent(
                beatsPerMinute = avg,
                start = averageStart,
                end = end,
                importedAt = end,
            )
            if (average != null) {
                notes += "Heart rate: no resting-heart-rate record. Stored a 6-hour average."
                return listOf(average)
            }
        } catch (e: SecurityException) {
            notes += "Heart rate: permission denied — re-grant Health Connect access."
            Log.w(TAG, "HR aggregate security", e)
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "HR aggregate failed", e)
        }

        try {
            val records = readAllRecords(
                client,
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(averageStart, end),
                ),
            )
            val latest = records
                .flatMap { record -> record.samples.map { sample -> record to sample } }
                .maxByOrNull { (_, sample) -> sample.time }
            if (latest != null) {
                notes += "Heart rate: no resting-heart-rate record. Stored the latest sample."
                return listOf(heartRateSample(latest.first, latest.second, importedAt = end))
            }
        } catch (e: Exception) {
            Log.w(TAG, "HR records failed", e)
        }

        notes += "Heart rate: no resting, average, or sample data in Health Connect."
        return emptyList()
    }

    private suspend fun readStepsAggregateOnly(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        notes: MutableList<String>,
    ): List<WearableObservation> {
        return try {
            val aggregated = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )[StepsRecord.COUNT_TOTAL]
            val observation = stepsAggregateOrAbsent(aggregated, start, end, end)
            if (observation == null) {
                notes += "Steps: no aggregate total for this interval."
                emptyList()
            } else {
                listOf(observation)
            }
        } catch (e: SecurityException) {
            notes += "Steps: permission denied."
            Log.w(TAG, "Interval steps security", e)
            emptyList()
        } catch (e: Exception) {
            notes += "Steps: aggregate read failed (${e.message ?: "error"})."
            Log.w(TAG, "Interval steps failed", e)
            emptyList()
        }
    }

    private suspend fun readIntervalHeartRate(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        notes: MutableList<String>,
    ): List<WearableObservation> {
        try {
            val resting = readAllRecords(
                client,
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            ).map { restingHeartRate(it, importedAt = end) }
                .filter { observedInside(it.observedStartAt, start.toEpochMilli(), end.toEpochMilli()) }
            val latest = resting.maxByOrNull { it.observedStartAt }
            if (latest != null) return listOf(latest)
        } catch (e: SecurityException) {
            notes += "Resting heart rate: permission denied."
            Log.w(TAG, "Interval resting heart rate security", e)
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Interval resting heart rate failed", e)
        }

        try {
            val avg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )[HeartRateRecord.BPM_AVG]
            val average = heartRateAverageOrAbsent(avg, start, end, end)
            if (average != null) return listOf(average)
        } catch (e: SecurityException) {
            notes += "Heart rate: permission denied — re-grant Health Connect access."
            Log.w(TAG, "Interval heart-rate average security", e)
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Interval heart-rate average failed", e)
        }

        return try {
            val latest = readAllRecords(
                client,
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            ).flatMap { record -> record.samples.map { sample -> record to sample } }
                .filter { (_, sample) ->
                    observedInside(sample.time.toEpochMilli(), start.toEpochMilli(), end.toEpochMilli())
                }
                .maxByOrNull { (_, sample) -> sample.time }
            if (latest == null) {
                notes += "Heart rate: no resting record, average, or sample in this interval."
                emptyList()
            } else {
                notes += "Heart rate: no resting record or average. Stored the latest sample in this interval."
                listOf(heartRateSample(latest.first, latest.second, importedAt = end))
            }
        } catch (e: Exception) {
            notes += "Heart rate: read failed (${e.message ?: "error"})."
            Log.w(TAG, "Interval heart-rate sample failed", e)
            emptyList()
        }
    }

    private suspend fun readLatestSleepSessionInternal(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        notes: MutableList<String>,
    ): WearableObservation? {
        return try {
            val sessions = readAllRecords(
                client,
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            val session = sessions.maxByOrNull { it.endTime } ?: return null
            if (!session.endTime.isAfter(session.startTime)) {
                notes += "Sleep: skipped a session with no positive duration."
                return null
            }
            sleepSession(session, importedAt = end)
        } catch (e: Exception) {
            Log.w(TAG, "Sleep session read failed", e)
            notes += "Sleep: read failed (${e.message ?: "error"})."
            null
        }
    }

    private suspend fun <T : Record> readAllRecords(
        client: HealthConnectClient,
        request: ReadRecordsRequest<T>,
    ): List<T> {
        return readAllPages { token ->
            val pageRequest = if (token.isNullOrEmpty()) {
                request
            } else {
                ReadRecordsRequest(
                    recordType = request.recordType,
                    timeRangeFilter = request.timeRangeFilter,
                    dataOriginFilter = request.dataOriginFilter,
                    ascendingOrder = request.ascendingOrder,
                    pageSize = request.pageSize,
                    pageToken = token,
                )
            }
            val response = client.readRecords(pageRequest)
            RecordPage(response.records, response.pageToken)
        }
    }

    companion object {
        private const val TAG = "HealthConnectReader"
        const val EMPTY_HC_HINT =
            "Health Connect has no steps or heart-rate yet. In the Fitbit app, enable writing to Health Connect, then confirm data under Health Connect → Data and access."
    }
}

internal fun observedInside(at: Long, startInclusive: Long, endExclusive: Long): Boolean {
    return at >= startInclusive && at < endExclusive
}

internal fun stepsAggregateOrAbsent(
    count: Long?,
    start: Instant,
    end: Instant,
    importedAt: Instant,
): WearableObservation? {
    if (count == null) return null
    return stepsAggregate(count, start, end, importedAt)
}

internal fun heartRateAverageOrAbsent(
    beatsPerMinute: Long?,
    start: Instant,
    end: Instant,
    importedAt: Instant,
): WearableObservation? {
    if (beatsPerMinute == null) return null
    return heartRateAverage(beatsPerMinute, start, end, importedAt)
}

internal fun stepsAggregate(
    count: Long,
    start: Instant,
    end: Instant,
    importedAt: Instant,
): WearableObservation {
    return WearableObservation.create(
        metric = WearableMetric.STEPS,
        value = count.toDouble(),
        unit = WearableUnit.COUNT,
        observedStartAt = start.toEpochMilli(),
        observedEndAt = end.toEpochMilli(),
        temporalKind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = WearableMethod.AGGREGATE_TOTAL,
        importedAt = importedAt.toEpochMilli(),
    )
}

internal fun stepsRecord(record: StepsRecord, importedAt: Instant): WearableObservation {
    return WearableObservation.create(
        metric = WearableMetric.STEPS,
        value = record.count.toDouble(),
        unit = WearableUnit.COUNT,
        observedStartAt = record.startTime.toEpochMilli(),
        observedEndAt = record.endTime.toEpochMilli(),
        temporalKind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = WearableMethod.STEPS_RECORD,
        originPackage = record.metadata.dataOrigin.packageName,
        sourceRecordId = stableRecordId(record.metadata),
        recordingMethod = recordingMethodLabel(record.metadata),
        importedAt = importedAt.toEpochMilli(),
    )
}

internal fun restingHeartRate(record: RestingHeartRateRecord, importedAt: Instant): WearableObservation {
    val at = record.time.toEpochMilli()
    return WearableObservation.create(
        metric = WearableMetric.RESTING_HEART_RATE,
        value = record.beatsPerMinute.toDouble(),
        unit = WearableUnit.BEATS_PER_MINUTE,
        observedStartAt = at,
        observedEndAt = at,
        temporalKind = WearableTemporalKind.POINT,
        method = WearableMethod.RESTING_HEART_RATE_RECORD,
        originPackage = record.metadata.dataOrigin.packageName,
        sourceRecordId = stableRecordId(record.metadata),
        recordingMethod = recordingMethodLabel(record.metadata),
        importedAt = importedAt.toEpochMilli(),
    )
}

internal fun heartRateAverage(
    beatsPerMinute: Long,
    start: Instant,
    end: Instant,
    importedAt: Instant,
): WearableObservation {
    return WearableObservation.create(
        metric = WearableMetric.HEART_RATE_AVERAGE,
        value = beatsPerMinute.toDouble(),
        unit = WearableUnit.BEATS_PER_MINUTE,
        observedStartAt = start.toEpochMilli(),
        observedEndAt = end.toEpochMilli(),
        temporalKind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = WearableMethod.HEART_RATE_AVERAGE,
        importedAt = importedAt.toEpochMilli(),
    )
}

internal fun heartRateSample(
    record: HeartRateRecord,
    sample: HeartRateRecord.Sample,
    importedAt: Instant,
): WearableObservation {
    val at = sample.time.toEpochMilli()
    return WearableObservation.create(
        metric = WearableMetric.HEART_RATE_SAMPLE,
        value = sample.beatsPerMinute.toDouble(),
        unit = WearableUnit.BEATS_PER_MINUTE,
        observedStartAt = at,
        observedEndAt = at,
        temporalKind = WearableTemporalKind.POINT,
        method = WearableMethod.HEART_RATE_SAMPLE,
        originPackage = record.metadata.dataOrigin.packageName,
        sourceRecordId = stableRecordId(record.metadata),
        recordingMethod = recordingMethodLabel(record.metadata),
        importedAt = importedAt.toEpochMilli(),
    )
}

internal fun sleepSession(record: SleepSessionRecord, importedAt: Instant): WearableObservation {
    val start = record.startTime.toEpochMilli()
    val end = record.endTime.toEpochMilli()
    val hours = (end - start).coerceAtLeast(0L) / 3_600_000.0
    return WearableObservation.create(
        metric = WearableMetric.SLEEP_DURATION,
        value = hours,
        unit = WearableUnit.HOURS,
        observedStartAt = start,
        observedEndAt = end,
        temporalKind = WearableTemporalKind.MEASURED_INTERVAL,
        method = WearableMethod.SLEEP_SESSION,
        originPackage = record.metadata.dataOrigin.packageName,
        sourceRecordId = stableRecordId(record.metadata),
        recordingMethod = recordingMethodLabel(record.metadata),
        importedAt = importedAt.toEpochMilli(),
    )
}

internal fun stableRecordId(metadata: Metadata): String? {
    return metadata.id.takeIf { it.isNotBlank() }
}

internal fun recordingMethodLabel(metadata: Metadata): String {
    return when (metadata.recordingMethod) {
        Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "automatically_recorded"
        Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "manual_entry"
        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "actively_recorded"
        else -> "unknown"
    }
}
