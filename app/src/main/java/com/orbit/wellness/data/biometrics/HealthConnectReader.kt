package com.orbit.wellness.data.biometrics

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.orbit.wellness.data.local.BiometricSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class BiometricReadResult(
    val snapshot: BiometricSnapshot,
    /** Human-readable notes for empty/missing metrics (shown in Health tab). */
    val notes: List<String>,
) {
    /** Health tab cares about RHR + steps; sleep is for Mood prefill / encoder only. */
    val hasWearableMetrics: Boolean
        get() = snapshot.restingHeartRateBpm != null || snapshot.stepsToday != null
}

/**
 * Thin Health Connect reader → [BiometricSnapshot] fields.
 * Null fields mean that metric was unavailable (permission, no data, or Fitbit not writing).
 */
class HealthConnectReader(
    private val context: Context,
) {
    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context.applicationContext)

    fun isAvailable(): Boolean =
        sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    fun getClientOrNull(): HealthConnectClient? {
        if (!isAvailable()) return null
        return HealthConnectClient.getOrCreate(context.applicationContext)
    }

    suspend fun hasAllReadPermissions(): Boolean {
        val client = getClientOrNull() ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(HealthConnectPermissions.READ_PERMISSIONS)
    }

    suspend fun hasBackgroundReadPermission(): Boolean {
        val client = getClientOrNull() ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(HealthConnectPermissions.BACKGROUND_PERMISSIONS)
    }

    suspend fun readSnapshot(now: Instant = Instant.now()): BiometricReadResult? {
        val client = getClientOrNull() ?: return null
        if (!hasAllReadPermissions()) return null

        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val lookback48h = now.minus(48, ChronoUnit.HOURS)
        val lookback7d = now.minus(7, ChronoUnit.DAYS)
        val notes = mutableListOf<String>()

        val stepsToday = readStepsTotal(client, startOfDay, now, notes)
        val rhr = readRestingOrFallbackHr(client, lookback7d, now, notes)
        // Still captured for encoder; Mood tab owns the sleep UX via [readLatestSleepSession].
        val sleepSession = readLatestSleepSessionInternal(client, lookback48h, now)
        val sleepHours = sleepSession?.durationHours

        if (stepsToday == null && rhr == null) {
            notes += EMPTY_HC_HINT
        }

        return BiometricReadResult(
            snapshot = BiometricSnapshot(
                syncedAt = now.toEpochMilli(),
                restingHeartRateBpm = rhr,
                stepsToday = stepsToday,
                sleepHoursLast = sleepHours,
                source = BiometricSnapshot.SOURCE_HEALTH_CONNECT,
            ),
            notes = notes,
        )
    }

    /**
     * Latest overnight sleep session for Mood-tab prefill.
     * Does not write Room — caller only seeds the widget until the user saves.
     */
    suspend fun readLatestSleepSession(
        now: Instant = Instant.now(),
    ): SleepSessionSuggestion? {
        val client = getClientOrNull() ?: return null
        if (!hasAllReadPermissions()) return null
        val lookback48h = now.minus(48, ChronoUnit.HOURS)
        return readLatestSleepSessionInternal(client, lookback48h, now)
    }

    private suspend fun readStepsTotal(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        notes: MutableList<String>,
    ): Long? {
        try {
            val aggregated = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )[StepsRecord.COUNT_TOTAL]
            if (aggregated != null) return aggregated
        } catch (e: SecurityException) {
            notes += "Steps: permission denied."
            Log.w(TAG, "Steps aggregate security", e)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Steps aggregate failed, trying records", e)
        }

        return try {
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            ).records
            if (records.isEmpty()) {
                notes += "Steps: no records in Health Connect for today."
                null
            } else {
                records.sumOf { it.count }
            }
        } catch (e: SecurityException) {
            notes += "Steps: permission denied."
            Log.w(TAG, "Steps records security", e)
            null
        } catch (e: Exception) {
            notes += "Steps: read failed (${e.message ?: "error"})."
            Log.w(TAG, "Steps records failed", e)
            null
        }
    }

    private suspend fun readRestingOrFallbackHr(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        notes: MutableList<String>,
    ): Double? {
        try {
            val resting = client.readRecords(
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            ).records
            val latest = resting.maxByOrNull { it.time }
            if (latest != null) {
                return latest.beatsPerMinute.toDouble()
            }
        } catch (e: SecurityException) {
            notes += "Resting HR: permission denied."
            Log.w(TAG, "RHR security", e)
        } catch (e: Exception) {
            Log.w(TAG, "RHR read failed", e)
        }

        try {
            val avg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(
                        end.minus(6, ChronoUnit.HOURS),
                        end,
                    ),
                ),
            )[HeartRateRecord.BPM_AVG]
            if (avg != null) {
                notes += "RHR: using 6h heart-rate average (no Resting HR in HC)."
                return avg.toDouble()
            }
        } catch (e: SecurityException) {
            notes += "Heart rate: permission denied — re-grant Health Connect access."
            Log.w(TAG, "HR aggregate security", e)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "HR aggregate failed", e)
        }

        try {
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        end.minus(6, ChronoUnit.HOURS),
                        end,
                    ),
                ),
            ).records
            val samples = records.flatMap { it.samples }
            val latest = samples.maxByOrNull { it.time }
            if (latest != null) {
                notes += "RHR: using latest heart-rate sample (no Resting HR in HC)."
                return latest.beatsPerMinute.toDouble()
            }
        } catch (e: Exception) {
            Log.w(TAG, "HR records failed", e)
        }

        notes += "RHR / HR: no heart-rate data in Health Connect."
        return null
    }

    private suspend fun readLatestSleepSessionInternal(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): SleepSessionSuggestion? {
        try {
            val sessions = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            ).records
            val session = sessions.maxByOrNull { it.endTime } ?: return null
            val millis = ChronoUnit.MILLIS.between(session.startTime, session.endTime)
                .coerceAtLeast(0L)
            return SleepSessionSuggestion(
                bedtimeMillis = session.startTime.toEpochMilli(),
                wakeTimeMillis = session.endTime.toEpochMilli(),
                durationHours = millis / 3_600_000.0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Sleep session read failed", e)
            return null
        }
    }

    companion object {
        private const val TAG = "HealthConnectReader"
        const val EMPTY_HC_HINT =
            "Health Connect has no steps / heart-rate yet. In the Fitbit app, enable writing to Health Connect, then confirm data under Health Connect → Data and access. Sleep is used to prefill the Mood tab, not this panel."
    }
}
