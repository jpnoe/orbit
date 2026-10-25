package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "cluster_meta")
data class ClusterMeta(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val size: Int,
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
    val lastUpdated: Long,
)
