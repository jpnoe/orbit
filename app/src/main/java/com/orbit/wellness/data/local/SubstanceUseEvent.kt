package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "substance_use_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["intent"]),
    ],
)
data class SubstanceUseEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val name: String,
    val amount: String,
    val moodScore: Int,
    val intent: String,
)
