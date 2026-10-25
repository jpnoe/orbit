package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "topology_nodes",
    foreignKeys = [
        ForeignKey(
            entity = StateVectorSnapshot::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["recordedAt"]),
        Index(value = ["clusterId"]),
    ],
)
data class TopologyNode(
    @PrimaryKey val snapshotId: String,
    val recordedAt: Long,
    val coreDistance: Float,
    /** Null means exhaust / noise. */
    val clusterId: String? = null,
    val isExhaust: Boolean = true,
)
