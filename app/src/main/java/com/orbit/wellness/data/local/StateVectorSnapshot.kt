package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Persisted normalized [com.orbit.wellness.domain.state.StateVector] (10 axes, 0..1).
 * Written after each successful encode so later kNN can read history from Room.
 */
@Entity(
    tableName = "state_vector_snapshots",
    indices = [Index(value = ["recordedAt"])],
)
data class StateVectorSnapshot(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val recordedAt: Long,
    val moodCurrent: Float,
    val moodTrend1h: Float,
    val sleepHoursLast: Float,
    val rhrCurrent: Float,
    val stepsToday: Float,
    val activityLoad24h: Float,
    val workoutLoad24h: Float,
    val supportiveCount24h: Float,
    val copingCount24h: Float,
    val mealRegularity: Float,
    /** Bitmask of which axes had real source data (see FeatureAxes). */
    val presentMask: Int,
)
