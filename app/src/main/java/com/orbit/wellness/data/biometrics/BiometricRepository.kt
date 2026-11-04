package com.orbit.wellness.data.biometrics

import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableObservationDao
import java.time.Instant

/**
 * Imports Health Connect observations into Room.
 * Sync does not encode a state vector or ingest topology.
 */
class BiometricRepository(
    private val reader: WearableReadGateway,
    private val wearableObservationDao: WearableObservationDao,
) {
    fun isHealthConnectAvailable(): Boolean = reader.isAvailable()

    fun sdkStatus(): Int = reader.sdkStatus()

    suspend fun hasReadPermissions(): Boolean = reader.hasAllReadPermissions()

    suspend fun sync(now: Instant = Instant.now()): SyncResult {
        when {
            !reader.isAvailable() -> {
                return SyncResult(
                    observations = emptyList(),
                    error = "Health Connect is not available on this device.",
                )
            }
            !reader.hasAllReadPermissions() -> {
                return SyncResult(
                    observations = emptyList(),
                    error = "Health Connect permissions are not granted (or need re-grant for heart rate).",
                )
            }
        }
        return try {
            val read = reader.readObservations(now)
            if (read == null) {
                SyncResult(
                    observations = emptyList(),
                    error = "No wearable data returned from Health Connect.",
                )
            } else {
                if (read.observations.isNotEmpty()) {
                    wearableObservationDao.upsertAll(read.observations)
                }
                SyncResult(
                    observations = read.observations,
                    error = null,
                    notes = read.notes,
                )
            }
        } catch (e: Exception) {
            SyncResult(
                observations = emptyList(),
                error = e.message ?: "Health Connect sync failed.",
            )
        }
    }

    /**
     * Imports summaries for the exact stored context interval.
     * Does not recompute the boundary and does not write the check-in.
     * A null read means Health Connect is unavailable or not permitted.
     * An empty list is a valid read that found nothing yet.
     */
    suspend fun captureInterval(window: CheckInContextWindow): IntervalCaptureOutcome {
        return try {
            if (!reader.isAvailable() || !reader.hasAllReadPermissions()) {
                return IntervalCaptureOutcome.Unavailable
            }
            val read = reader.readInterval(
                start = Instant.ofEpochMilli(window.windowStartAt),
                end = Instant.ofEpochMilli(window.windowEndAt),
            ) ?: return IntervalCaptureOutcome.Unavailable
            if (read.observations.isEmpty()) {
                IntervalCaptureOutcome.Empty
            } else {
                wearableObservationDao.upsertAll(read.observations)
                IntervalCaptureOutcome.Captured(read.observations)
            }
        } catch (e: Exception) {
            IntervalCaptureOutcome.Failed(e.message ?: "Interval import failed.")
        }
    }

    /**
     * Latest overnight sleep from Health Connect for the log-screen prefill.
     * This does not write a manual sleep record or a wearable observation.
     */
    suspend fun suggestSleepFromHealthConnect(): SleepSessionSuggestion? =
        reader.readLatestSleepSession()

    data class SyncResult(
        val observations: List<WearableObservation>,
        val error: String?,
        val notes: List<String> = emptyList(),
    )
}
