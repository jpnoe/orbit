package com.orbit.wellness.domain.topology

import android.os.SystemClock
import android.util.Log
import com.orbit.wellness.data.local.ClusterMeta
import com.orbit.wellness.data.local.ClusterMetaDao
import com.orbit.wellness.data.local.StateVectorSnapshot
import com.orbit.wellness.data.local.StateVectorSnapshotDao
import com.orbit.wellness.data.local.TopologyEdge
import com.orbit.wellness.data.local.TopologyEdgeDao
import com.orbit.wellness.data.local.TopologyNode
import com.orbit.wellness.data.local.TopologyNodeDao
import com.orbit.wellness.data.local.TopologyState
import com.orbit.wellness.data.local.TopologyStateDao
import com.orbit.wellness.domain.state.FeatureAxes
import com.orbit.wellness.domain.state.StateVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.floor

/**
 * 90-day HNSW working set over hourly state-vector snapshots.
 * Write path is insert + merge only; splits happen in [compact].
 */
class TopologyRepository(
    private val snapshotDao: StateVectorSnapshotDao,
    private val nodeDao: TopologyNodeDao,
    private val edgeDao: TopologyEdgeDao,
    private val clusterMetaDao: ClusterMetaDao,
    private val stateDao: TopologyStateDao,
) {
    private val mutex = Mutex()
    private var index: HnswIndex? = null
    private val cores = ArrayList<Float>()
    private val clusterIds = ArrayList<String?>()
    private val exhaustFlags = ArrayList<Boolean>()
    private var lambdaCap: Float = Float.POSITIVE_INFINITY
    private var loaded = false

    suspend fun warmUp() {
        mutex.withLock { ensureLoaded() }
    }

    suspend fun ingest(snapshot: StateVectorSnapshot) {
        mutex.withLock {
            withContext(Dispatchers.Default) {
                ensureLoaded()
                val graph = index ?: return@withContext
                val cutoff = windowCutoff()
                graph.evictOlderThan(cutoff)
                nodeDao.deleteOlderThan(cutoff)
                if (graph.indexOf(snapshot.id) != null) return@withContext

                val graphStart = SystemClock.elapsedRealtime()
                val slot = graph.insert(toPoint(snapshot))
                ensureSlot(slot)
                val affected = (listOf(slot) + graph.baseNeighbors(slot)).distinct()
                val wasExhaust = affected.associateWith { exhaustFlags.getOrElse(it) { true } }
                refreshCoresAround(graph, slot)
                for (i in affected) {
                    val nowCore = cores[i].isFinite() && cores[i] <= lambdaCap
                    if (i == slot || (nowCore && wasExhaust[i] == true)) {
                        mergeAssign(graph, i)
                    }
                }
                val hnswMs = SystemClock.elapsedRealtime() - graphStart

                val persistStart = SystemClock.elapsedRealtime()
                persistAllNodes(graph)
                val persistMs = SystemClock.elapsedRealtime() - persistStart

                Log.d(
                    TAG,
                    "ingest n=${graph.liveIndices().count()} hnsw=${hnswMs}ms persist=${persistMs}ms " +
                        "total=${hnswMs + persistMs}ms exhaust=${exhaustFlags[slot]} " +
                        "cluster=${clusterIds[slot] ?: "none"} lambda=$lambdaCap",
                )
            }
        }
    }

    suspend fun suggestBetterNeighborhood(now: StateVector): NeighborhoodSuggestion? {
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                ensureLoaded()
                val graph = index ?: return@withContext null
                val knn = graph.knn(now.values, now.presentMask, 16).map { hit ->
                    val point = graph.pointAt(hit.index)
                    NeighborView(
                        snapshotId = point.snapshotId,
                        values = point.values,
                        presentMask = point.presentMask,
                        distance = hit.value,
                    )
                }
                val suggestion = NeighborhoodSuggester.suggest(now, liveClusterViews(graph), knn)
                when (suggestion?.tier) {
                    SuggestionTier.LEARNING -> {
                        Log.i(TAG, "suggest[learning]: ${suggestion.message}")
                    }
                    SuggestionTier.SPARSE -> {
                        Log.i(
                            TAG,
                            "suggest[sparse] mood ${suggestion.currentMood}→${suggestion.targetMood} " +
                                "dist=${suggestion.distance} neighbors=${suggestion.targetSize} " +
                                "id=${suggestion.targetClusterId}",
                        )
                        suggestion.deltas.take(4).forEach { delta ->
                            val sign = if (delta.amount >= 0f) "+" else ""
                            Log.i(
                                TAG,
                                "  delta ${delta.axisId} ${delta.from}→${delta.to} ($sign${delta.amount})",
                            )
                        }
                    }
                    SuggestionTier.DENSE -> {
                        Log.i(
                            TAG,
                            "suggest mood ${suggestion.currentMood}→${suggestion.targetMood} " +
                                "dist=${suggestion.distance} size=${suggestion.targetSize} " +
                                "cluster=${suggestion.targetClusterId}",
                        )
                        suggestion.deltas.take(4).forEach { delta ->
                            val sign = if (delta.amount >= 0f) "+" else ""
                            Log.i(
                                TAG,
                                "  delta ${delta.axisId} ${delta.from}→${delta.to} ($sign${delta.amount})",
                            )
                        }
                    }
                    null -> {
                        Log.i(TAG, "suggest: no better neighborhood nowMood=${now.values[0]}")
                    }
                }
                suggestion
            }
        }
    }

    /**
     * Rebuilds HNSW from the 90-day window, recomputes core distances,
     * sets λ to p90 of core distances, then splits via connected components.
     */
    suspend fun compact() {
        mutex.withLock {
            val cutoff = windowCutoff()
            nodeDao.deleteOlderThan(cutoff)
            val snapshots = snapshotDao.getSince(cutoff)
            val previous = nodeDao.getSince(cutoff).associateBy { it.snapshotId }
            rebuildFrom(snapshots, previous)
            val graph = index ?: return
            recomputeAllCores(graph)
            lambdaCap = percentile90(
                graph.liveIndices()
                    .map { cores[it] }
                    .filter { it.isFinite() }
                    .toList(),
            )
            assignByComponents(graph, previous)
            persistAll(graph)
            stateDao.upsert(
                TopologyState(
                    lambdaCap = lambdaCap,
                    lastCompactedAt = System.currentTimeMillis(),
                ),
            )
            val sizes = graph.liveIndices()
                .mapNotNull { clusterIds[it] }
                .groupingBy { it }
                .eachCount()
                .values
                .sortedDescending()
            Log.d(
                TAG,
                "compacted n=${graph.liveIndices().count()} clusters=${sizes.size} " +
                    "sizes=$sizes lambda=$lambdaCap",
            )
            loaded = true
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val cutoff = windowCutoff()
        val snapshots = snapshotDao.getSince(cutoff)
        val previous = nodeDao.getSince(cutoff).associateBy { it.snapshotId }
        val state = stateDao.get()
        lambdaCap = state?.lambdaCap ?: Float.POSITIVE_INFINITY
        rebuildFrom(snapshots, previous)
        val graph = index ?: return
        val neverCompacted = (state?.lastCompactedAt ?: 0L) == 0L
        if (neverCompacted && snapshots.size >= TopologyParams.MIN_PTS) {
            recomputeAllCores(graph)
            lambdaCap = percentile90(
                graph.liveIndices()
                    .map { cores[it] }
                    .filter { it.isFinite() }
                    .toList(),
            )
            assignByComponents(graph, previous)
            persistAll(graph)
            stateDao.upsert(
                TopologyState(
                    lambdaCap = lambdaCap,
                    lastCompactedAt = System.currentTimeMillis(),
                ),
            )
        } else if (previous.isEmpty() && snapshots.isNotEmpty()) {
            recomputeAllCores(graph)
            graph.liveIndices().forEach { mergeAssign(graph, it) }
            persistAllNodes(graph)
        }
        loaded = true
        Log.d(TAG, "index ready n=${graph.liveIndices().count()} lambda=$lambdaCap")
    }

    private fun rebuildFrom(
        snapshots: List<StateVectorSnapshot>,
        previous: Map<String, TopologyNode>,
    ) {
        val graph = HnswIndex()
        cores.clear()
        clusterIds.clear()
        exhaustFlags.clear()
        for (snapshot in snapshots) {
            val slot = graph.insert(toPoint(snapshot))
            ensureSlot(slot)
            val node = previous[snapshot.id]
            cores[slot] = node?.coreDistance ?: Float.POSITIVE_INFINITY
            clusterIds[slot] = node?.clusterId
            exhaustFlags[slot] = node?.isExhaust ?: true
        }
        index = graph
    }

    private fun refreshCoresAround(graph: HnswIndex, slot: Int) {
        val affected = LinkedHashSet<Int>()
        affected += slot
        affected += graph.baseNeighbors(slot)
        for (index in affected) {
            cores[index] = coreDistanceOf(graph, index)
        }
    }

    private fun recomputeAllCores(graph: HnswIndex) {
        for (i in graph.liveIndices()) {
            ensureSlot(i)
            cores[i] = coreDistanceOf(graph, i)
        }
    }

    private fun coreDistanceOf(graph: HnswIndex, slot: Int): Float {
        val point = graph.pointAt(slot)
        val neighbors = graph.knn(point.values, point.presentMask, TopologyParams.K_NEIGHBORS)
            .filter { it.index != slot && it.value.isFinite() }
        val needed = TopologyParams.MIN_PTS - 1
        if (neighbors.size < needed) return Float.POSITIVE_INFINITY
        return neighbors[needed - 1].value
    }

    private fun mergeAssign(graph: HnswIndex, slot: Int) {
        val liveCount = graph.liveIndices().count()
        if (liveCount < TopologyParams.MIN_PTS || !cores[slot].isFinite() || cores[slot] > lambdaCap) {
            exhaustFlags[slot] = true
            clusterIds[slot] = null
            return
        }
        exhaustFlags[slot] = false
        val point = graph.pointAt(slot)
        val partners = graph.knn(point.values, point.presentMask, TopologyParams.K_NEIGHBORS)
            .filter { candidate ->
                val other = candidate.index
                other != slot &&
                    cores.getOrElse(other) { Float.POSITIVE_INFINITY }.isFinite() &&
                    cores[other] <= lambdaCap &&
                    FeatureDistance.mutualReachability(cores[slot], cores[other], candidate.value) <= lambdaCap
            }
            .map { it.index }
        if (partners.isEmpty()) {
            clusterIds[slot] = UUID.randomUUID().toString()
            return
        }
        val existing = partners.mapNotNull { clusterIds[it] }
        val keep = if (existing.isEmpty()) {
            UUID.randomUUID().toString()
        } else {
            preferredClusterId(graph, existing)
        }
        clusterIds[slot] = keep
        val rewritten = HashSet<String>()
        for (partner in partners) {
            val old = clusterIds[partner]
            if (old == null) {
                clusterIds[partner] = keep
            } else if (old != keep) {
                rewritten += old
            }
        }
        if (rewritten.isNotEmpty()) {
            for (i in graph.liveIndices()) {
                val id = clusterIds[i] ?: continue
                if (id in rewritten) clusterIds[i] = keep
            }
        }
    }

    private fun assignByComponents(graph: HnswIndex, previous: Map<String, TopologyNode>) {
        val live = graph.liveIndices().toList()
        if (live.size < TopologyParams.MIN_PTS) {
            for (i in live) {
                exhaustFlags[i] = true
                clusterIds[i] = null
            }
            return
        }
        val coresInCap = live.filter { cores[it].isFinite() && cores[it] <= lambdaCap }.toHashSet()
        for (i in live) {
            val isCore = i in coresInCap
            exhaustFlags[i] = !isCore
            if (!isCore) clusterIds[i] = null
        }
        if (coresInCap.isEmpty()) return

        val uf = UnionFind(graph.size)
        for (i in coresInCap) {
            val point = graph.pointAt(i)
            val neighbors = graph.knn(point.values, point.presentMask, TopologyParams.K_NEIGHBORS)
            for (candidate in neighbors) {
                val other = candidate.index
                if (other == i || other !in coresInCap) continue
                val reach = FeatureDistance.mutualReachability(cores[i], cores[other], candidate.value)
                if (reach <= lambdaCap) uf.union(i, other)
            }
        }
        val members = coresInCap.groupBy { uf.find(it) }
        for ((_, group) in members) {
            val votes = group.mapNotNull { previous[graph.pointAt(it).snapshotId]?.clusterId }
                .groupingBy { it }
                .eachCount()
            val keep = votes.maxByOrNull { it.value }?.key ?: UUID.randomUUID().toString()
            for (i in group) {
                clusterIds[i] = keep
                exhaustFlags[i] = false
            }
        }
    }

    private fun preferredClusterId(graph: HnswIndex, ids: List<String>): String {
        val unique = ids.distinct()
        if (unique.size == 1) return unique.first()
        return unique.sortedWith(
            compareByDescending<String> { id -> graph.liveIndices().count { clusterIds[it] == id } }
                .thenBy { id ->
                    graph.liveIndices()
                        .filter { clusterIds[it] == id }
                        .minOfOrNull { graph.pointAt(it).recordedAt } ?: Long.MAX_VALUE
                },
        ).first()
    }

    private suspend fun persistAllNodes(graph: HnswIndex) {
        val nodes = graph.liveIndices().map { i ->
            val point = graph.pointAt(i)
            TopologyNode(
                snapshotId = point.snapshotId,
                recordedAt = point.recordedAt,
                coreDistance = cores[i],
                clusterId = clusterIds[i],
                isExhaust = exhaustFlags[i],
            )
        }.toList()
        if (nodes.isNotEmpty()) nodeDao.upsertAll(nodes)
    }

    private suspend fun persistAll(graph: HnswIndex) {
        persistAllNodes(graph)
        edgeDao.deleteAll()
        val edges = ArrayList<TopologyEdge>()
        for (i in graph.liveIndices()) {
            val from = graph.pointAt(i).snapshotId
            for (layer in 0..TopologyParams.MAX_LEVEL) {
                for (j in graph.neighborsAt(i, layer)) {
                    edges += TopologyEdge(
                        fromId = from,
                        toId = graph.pointAt(j).snapshotId,
                        distance = graph.distanceBetween(i, j),
                        layer = layer,
                    )
                }
            }
        }
        if (edges.isNotEmpty()) edgeDao.insertAll(edges)
        clusterMetaDao.deleteAll()
        val now = System.currentTimeMillis()
        val metas = graph.liveIndices()
            .mapNotNull { i -> clusterIds[i]?.let { it to i } }
            .groupBy({ it.first }, { it.second })
            .map { (id, members) -> centroidMeta(graph, id, members, now) }
        if (metas.isNotEmpty()) clusterMetaDao.upsertAll(metas)
    }

    private fun liveClusterViews(graph: HnswIndex): List<ClusterView> {
        return graph.liveIndices()
            .mapNotNull { i -> clusterIds[i]?.let { it to i } }
            .groupBy({ it.first }, { it.second })
            .map { (id, members) ->
                val sums = FloatArray(FeatureAxes.COUNT)
                val counts = IntArray(FeatureAxes.COUNT)
                for (i in members) {
                    val point = graph.pointAt(i)
                    for (axis in 0 until FeatureAxes.COUNT) {
                        if (point.presentMask and FeatureAxes.bit(axis) == 0) continue
                        sums[axis] += point.values[axis]
                        counts[axis]++
                    }
                }
                val centroid = FloatArray(FeatureAxes.COUNT) { axis ->
                    if (counts[axis] == 0) FeatureAxes.MISSING_NEUTRAL else sums[axis] / counts[axis]
                }
                var mask = 0
                for (axis in 0 until FeatureAxes.COUNT) {
                    if (counts[axis] > 0) mask = mask or FeatureAxes.bit(axis)
                }
                ClusterView(id = id, size = members.size, centroid = centroid, presentMask = mask)
            }
    }

    private fun centroidMeta(
        graph: HnswIndex,
        id: String,
        members: List<Int>,
        now: Long,
    ): ClusterMeta {
        val sums = FloatArray(FeatureAxes.COUNT)
        val counts = IntArray(FeatureAxes.COUNT)
        for (i in members) {
            val point = graph.pointAt(i)
            for (axis in 0 until FeatureAxes.COUNT) {
                if (point.presentMask and FeatureAxes.bit(axis) == 0) continue
                sums[axis] += point.values[axis]
                counts[axis]++
            }
        }
        fun axis(index: Int): Float =
            if (counts[index] == 0) FeatureAxes.MISSING_NEUTRAL else sums[index] / counts[index]
        return ClusterMeta(
            id = id,
            size = members.size,
            moodCurrent = axis(FeatureAxes.MOOD_CURRENT),
            moodTrend1h = axis(FeatureAxes.MOOD_TREND_1H),
            sleepHoursLast = axis(FeatureAxes.SLEEP_HOURS_LAST),
            rhrCurrent = axis(FeatureAxes.RHR_CURRENT),
            stepsToday = axis(FeatureAxes.STEPS_TODAY),
            activityLoad24h = axis(FeatureAxes.ACTIVITY_LOAD_24H),
            workoutLoad24h = axis(FeatureAxes.WORKOUT_LOAD_24H),
            supportiveCount24h = axis(FeatureAxes.SUPPORTIVE_COUNT_24H),
            copingCount24h = axis(FeatureAxes.COPING_COUNT_24H),
            mealRegularity = axis(FeatureAxes.MEAL_REGULARITY),
            lastUpdated = now,
        )
    }

    private fun ensureSlot(slot: Int) {
        while (cores.size <= slot) {
            cores.add(Float.POSITIVE_INFINITY)
            clusterIds.add(null)
            exhaustFlags.add(true)
        }
    }

    private fun toPoint(snapshot: StateVectorSnapshot) = HnswIndex.Point(
        snapshotId = snapshot.id,
        recordedAt = snapshot.recordedAt,
        values = floatArrayOf(
            snapshot.moodCurrent,
            snapshot.moodTrend1h,
            snapshot.sleepHoursLast,
            snapshot.rhrCurrent,
            snapshot.stepsToday,
            snapshot.activityLoad24h,
            snapshot.workoutLoad24h,
            snapshot.supportiveCount24h,
            snapshot.copingCount24h,
            snapshot.mealRegularity,
        ),
        presentMask = snapshot.presentMask,
    )

    private fun windowCutoff(): Long = System.currentTimeMillis() - TopologyParams.WINDOW_MS

    private fun percentile90(values: List<Float>): Float {
        if (values.isEmpty()) return Float.POSITIVE_INFINITY
        val sorted = values.sorted()
        val idx = floor(0.9 * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    companion object {
        private const val TAG = "OrbitTopology"
    }
}
