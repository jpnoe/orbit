package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "status_requests",
    indices = [Index(value = ["timestamp"]), Index(value = ["resolved"])],
)
data class StatusRequest(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val contactId: String,
    val contactName: String,
    val phoneE164: String,
    val generatedBody: String,
    val modulesShared: String,
    val timestamp: Long = System.currentTimeMillis(),
    val resolved: Boolean = false,
)
