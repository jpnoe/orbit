package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "trusted_contacts",
    indices = [
        Index(value = ["phoneE164"], unique = true),
        Index(value = ["enabled"]),
    ],
)
data class TrustedContact(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val contactName: String,
    val phoneE164: String,
    val triggerPhrase: String = DEFAULT_TRIGGER,
    val autoApprove: Boolean = false,
    val enabled: Boolean = true,
    val shareMood: Boolean = false,
    val shareJournal: Boolean = false,
    val shareDiet: Boolean = false,
    val shareExercise: Boolean = false,
    val shareSleep: Boolean = false,
    val shareSupportive: Boolean = false,
    val shareCoping: Boolean = false,
    val shareActivity: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_TRIGGER = "Orbit Status"
    }
}
