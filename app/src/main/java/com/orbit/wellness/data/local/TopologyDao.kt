package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TopologyNodeDao {
    @Upsert
    suspend fun upsert(node: TopologyNode)

    @Upsert
    suspend fun upsertAll(nodes: List<TopologyNode>)

    @Query("SELECT * FROM topology_nodes WHERE recordedAt >= :sinceMillis ORDER BY recordedAt ASC")
    suspend fun getSince(sinceMillis: Long): List<TopologyNode>

    @Query("SELECT * FROM topology_nodes WHERE snapshotId = :snapshotId LIMIT 1")
    suspend fun getById(snapshotId: String): TopologyNode?

    @Query("DELETE FROM topology_nodes WHERE recordedAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}

@Dao
interface TopologyEdgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(edges: List<TopologyEdge>)

    @Query("SELECT * FROM topology_edges")
    suspend fun getAll(): List<TopologyEdge>

    @Query("DELETE FROM topology_edges WHERE fromId = :snapshotId OR toId = :snapshotId")
    suspend fun deleteForNode(snapshotId: String)

    @Query("DELETE FROM topology_edges")
    suspend fun deleteAll()
}

@Dao
interface ClusterMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(clusters: List<ClusterMeta>)

    @Query("SELECT * FROM cluster_meta")
    suspend fun getAll(): List<ClusterMeta>

    @Query("DELETE FROM cluster_meta")
    suspend fun deleteAll()
}

@Dao
interface TopologyStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: TopologyState)

    @Query("SELECT * FROM topology_state WHERE id = :id LIMIT 1")
    suspend fun get(id: String = TopologyState.SINGLETON_ID): TopologyState?
}
