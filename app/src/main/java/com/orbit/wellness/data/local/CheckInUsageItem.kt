package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Use the person attributed to a context interval.
 * [reportedAt] is when the report was saved, not when the use occurred.
 * [name] is kept even if the source [SubstanceOption] is later removed.
 * There is no foreign key to substance options, so option deletion does not
 * block or rewrite these rows. One row is one option for the interval.
 * [amount] summarizes repeated use and may be blank.
 */
@Entity(
    tableName = "check_in_usage_items",
    foreignKeys = [
        ForeignKey(
            entity = CheckInContextWindow::class,
            parentColumns = ["checkInId"],
            childColumns = ["checkInId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["checkInId"]),
    ],
)
data class CheckInUsageItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val checkInId: String,
    val substanceOptionId: String?,
    val name: String,
    val intent: String,
    val amount: String,
    val reportedAt: Long,
) {
    init {
        require(name.isNotBlank()) {
            "Usage name must not be blank"
        }
        require(substanceOptionId == null || substanceOptionId.isNotBlank()) {
            "Usage option id must not be blank"
        }
        require(intent in ALLOWED_INTENTS) {
            "Usage intent must be ${ALLOWED_INTENTS.joinToString(" or ")}"
        }
    }

    companion object {
        val ALLOWED_INTENTS = SubstanceIntent.entries.map { it.name }.toSet()
    }
}
