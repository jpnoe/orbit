package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * On-device cache of Health Connect (or future Fitbit API) biometrics.
 * One row per successful sync; encoder always reads the latest.
 */
@Entity(
    tableName = "biometric_snapshots",
    indices = [Index(value = ["syncedAt"])],
)
data class BiometricSnapshot(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val syncedAt: Long,
    val restingHeartRateBpm: Double? = null,
    val stepsToday: Long? = null,
    /** Duration of the most recent overnight sleep session, in hours. */
    val sleepHoursLast: Double? = null,
    val source: String = SOURCE_HEALTH_CONNECT,
) {
    companion object {
        const val SOURCE_HEALTH_CONNECT = "health_connect"
    }
}
