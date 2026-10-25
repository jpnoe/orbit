package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "share_events",
    indices = [Index(value = ["timestamp"])],
)
data class ShareEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val contactId: String,
    val contactName: String,
    val phoneE164: String,
    val modulesShared: String,
    val bodyPreview: String,
    val autoApproved: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)
