package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "activity_options")
data class ActivityOption(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sortOrder: Int,
    val isCustom: Boolean,
)
