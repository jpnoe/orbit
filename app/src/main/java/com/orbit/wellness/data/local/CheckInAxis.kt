package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "check_in_axes",
    indices = [
        Index(value = ["sortOrder"]),
        Index(value = ["isEnabled", "sortOrder"]),
    ],
)
data class CheckInAxis(
    @PrimaryKey val id: String,
    val name: String,
    val lowLabel: String,
    val highLabel: String,
    val sortOrder: Int,
    val isDefault: Boolean,
    val isEnabled: Boolean,
    val createdAt: Long,
)
