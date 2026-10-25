package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_journals")
data class DailyJournal(
    @PrimaryKey val localDate: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
)
