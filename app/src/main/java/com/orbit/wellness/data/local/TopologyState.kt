package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "topology_state")
data class TopologyState(
    @PrimaryKey val id: String = SINGLETON_ID,
    val lambdaCap: Float = Float.POSITIVE_INFINITY,
    val lastCompactedAt: Long = 0L,
) {
    companion object {
        const val SINGLETON_ID = "singleton"
    }
}
