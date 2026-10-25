package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "topology_edges",
    indices = [
        Index(value = ["fromId"]),
        Index(value = ["toId"]),
        Index(value = ["layer"]),
    ],
)
data class TopologyEdge(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val fromId: String,
    val toId: String,
    val distance: Float,
    val layer: Int,
)
