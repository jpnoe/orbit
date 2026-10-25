package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Personal supportive / coping list items. Stays on-device.
 */
@Entity(
    tableName = "substance_options",
    indices = [Index(value = ["intent"])],
)
data class SubstanceOption(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val intent: String,
    val defaultAmount: String = "",
    val sortOrder: Int,
    val isCustom: Boolean,
)
