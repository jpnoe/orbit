package com.orbit.wellness.data.biometrics

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord

object HealthConnectPermissions {
    /** Required for foreground Sync + sleep prefill. */
    val READ_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    /** Requested with grants so hourly WorkManager sync can read HC in background. */
    val BACKGROUND_PERMISSIONS: Set<String> = setOf(
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

    val ALL_REQUESTED: Set<String> = READ_PERMISSIONS + BACKGROUND_PERMISSIONS
}

/** Overnight sleep session for Mood-tab prefill (not auto-logged). */
data class SleepSessionSuggestion(
    val bedtimeMillis: Long,
    val wakeTimeMillis: Long,
    val durationHours: Double,
)
