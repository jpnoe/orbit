package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A sleep record the person confirmed. Wearable sleep stays in
 * [WearableObservation] and is not written here.
 */
@Entity(
    tableName = "sleep_events",
    indices = [
        Index(value = ["localDate"], unique = true),
        Index(value = ["wakeTime"]),
    ],
)
data class SleepEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val localDate: String,
    val bedtime: Long,
    val wakeTime: Long,
    val sleepQuality: Int,
)
