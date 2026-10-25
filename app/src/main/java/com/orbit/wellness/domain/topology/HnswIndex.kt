package com.orbit.wellness.domain.topology

import kotlin.random.Random

/**
 * Small HNSW over [FeatureDistance] for the 90-day working window (~2k points).
 * Deletes are tombstones; weekly compaction rebuilds a clean graph.
 */
class HnswIndex(
    private val m: Int = TopologyParams.M,
    private val mMax0: Int = TopologyParams.M_MAX_0,
    private val mUpper: Int = TopologyParams.M_UPPER,
    private val efConstruction: Int = TopologyParams.EF_CONSTRUCTION,
    private val random: Random = Random.Default,
) {
    data class Point(
        val snapshotId: String,
        val recordedAt: Long,
        val values: FloatArray,
        val presentMask: Int,
    )

    private val points = ArrayList<Point>()
    private val idToIndex = HashMap<String, Int>()
    private val deleted = ArrayList<Boolean>()
    private val maxLevel = ArrayList<Int>()
    private val neighbors = Array(TopologyParams.MAX_LEVEL + 1) { ArrayList<ArrayList<Int>>() }
    private var entryPoint = -1

    val size: Int get() = points.size

    fun pointAt(index: Int): Point = points[index]

    fun indexOf(snapshotId: String): Int? = idToIndex[snapshotId]

    fun isDeleted(index: Int): Boolean = deleted[index]

    fun baseNeighbors(index: Int): List<Int> =
        neighbors[0][index].filter { !deleted[it] }

    fun insert(point: Point): Int {
        idToIndex[point.snapshotId]?.let { existing ->
            if (!deleted[existing]) return existing
        }
        val index = points.size
        points += point
        deleted += false
        idToIndex[point.snapshotId] = index
        val level = randomLevel()
        maxLevel += level
        for (layer in neighbors.indices) {
            neighbors[layer].add(ArrayList())
        }

        if (entryPoint < 0 || deleted[entryPoint]) {
            entryPoint = index
            return index
        }

        var current = entryPoint
        for (layer in TopologyParams.MAX_LEVEL downTo level + 1) {
            current = greedy(index, current, layer)
        }
        for (layer in minOf(level, TopologyParams.MAX_LEVEL) downTo 0) {
            val candidates = searchLayer(index, current, efConstruction, layer)
            val cap = if (layer == 0) mMax0 else mUpper
            val selected = selectNeighbors(index, candidates, cap)
            neighbors[layer][index].clear()
            neighbors[layer][index].addAll(selected)
            for (neighbor in selected) {
                neighbors[layer][neighbor].add(index)
                prune(neighbor, cap, layer)
            }
            current = selected.minByOrNull { distance(index, it) } ?: current
        }
        if (level > maxLevel[entryPoint]) {
            entryPoint = index
        }
        return index
    }

    fun knn(values: FloatArray, presentMask: Int, k: Int): List<IndexedValue<Float>> {
        if (entryPoint < 0) return emptyList()
        var current = liveEntry() ?: return emptyList()
        val query = Point("", 0L, values, presentMask)
        for (layer in TopologyParams.MAX_LEVEL downTo 1) {
            current = greedyQuery(query, current, layer)
        }
        val candidates = searchLayerQuery(query, current, maxOf(k, efConstruction), 0)
        return candidates
            .filter { !deleted[it] }
            .map { IndexedValue(it, distanceQuery(query, it)) }
            .sortedBy { it.value }
            .take(k)
    }

    fun evictOlderThan(cutoffMillis: Long) {
        for (i in points.indices) {
            if (points[i].recordedAt < cutoffMillis) {
                deleted[i] = true
                idToIndex.remove(points[i].snapshotId)
            }
        }
        if (entryPoint >= 0 && deleted[entryPoint]) {
            entryPoint = liveEntry() ?: -1
        }
    }

    fun liveIndices(): Sequence<Int> = points.indices.asSequence().filter { !deleted[it] }

    fun neighborsAt(index: Int, layer: Int): List<Int> =
        neighbors[layer][index].filter { !deleted[it] }

    fun distanceBetween(a: Int, b: Int): Float = distance(a, b)

    private fun liveEntry(): Int? {
        if (entryPoint >= 0 && !deleted[entryPoint]) return entryPoint
        return points.indices.firstOrNull { !deleted[it] }
    }

    private fun randomLevel(): Int {
        var level = 0
        val p = 1.0 / m
        while (level < TopologyParams.MAX_LEVEL && random.nextDouble() < p) {
            level++
        }
        return level
    }

    private fun greedy(source: Int, start: Int, layer: Int): Int {
        var current = start
        var best = distance(source, current)
        var moved = true
        while (moved) {
            moved = false
            for (neighbor in neighbors[layer][current]) {
                if (deleted[neighbor]) continue
                val d = distance(source, neighbor)
                if (d < best) {
                    best = d
                    current = neighbor
                    moved = true
                }
            }
        }
        return current
    }

    private fun greedyQuery(query: Point, start: Int, layer: Int): Int {
        var current = start
        var best = distanceQuery(query, current)
        var moved = true
        while (moved) {
            moved = false
            for (neighbor in neighbors[layer][current]) {
                if (deleted[neighbor]) continue
                val d = distanceQuery(query, neighbor)
                if (d < best) {
                    best = d
                    current = neighbor
                    moved = true
                }
            }
        }
        return current
    }

    private fun searchLayer(source: Int, start: Int, ef: Int, layer: Int): List<Int> {
        if (deleted[start]) return emptyList()
        val visited = HashSet<Int>()
        val candidates = java.util.PriorityQueue(compareBy<Pair<Int, Float>> { it.second })
        val w = java.util.PriorityQueue(compareByDescending<Pair<Int, Float>> { it.second })
        val d0 = distance(source, start)
        candidates.add(start to d0)
        w.add(start to d0)
        visited.add(start)
        while (candidates.isNotEmpty()) {
            val next = candidates.poll() ?: break
            val (c, cd) = next
            val farthest = w.peek()?.second ?: Float.POSITIVE_INFINITY
            if (cd > farthest) break
            for (neighbor in neighbors[layer][c]) {
                if (!visited.add(neighbor) || deleted[neighbor]) continue
                val d = distance(source, neighbor)
                val worst = w.peek()?.second ?: Float.POSITIVE_INFINITY
                if (d < worst || w.size < ef) {
                    candidates.add(neighbor to d)
                    w.add(neighbor to d)
                    if (w.size > ef) w.poll()
                }
            }
        }
        return w.map { it.first }
    }

    private fun searchLayerQuery(query: Point, start: Int, ef: Int, layer: Int): List<Int> {
        if (deleted[start]) return emptyList()
        val visited = HashSet<Int>()
        val candidates = java.util.PriorityQueue(compareBy<Pair<Int, Float>> { it.second })
        val w = java.util.PriorityQueue(compareByDescending<Pair<Int, Float>> { it.second })
        val d0 = distanceQuery(query, start)
        candidates.add(start to d0)
        w.add(start to d0)
        visited.add(start)
        while (candidates.isNotEmpty()) {
            val next = candidates.poll() ?: break
            val (c, cd) = next
            val farthest = w.peek()?.second ?: Float.POSITIVE_INFINITY
            if (cd > farthest) break
            for (neighbor in neighbors[layer][c]) {
                if (!visited.add(neighbor) || deleted[neighbor]) continue
                val d = distanceQuery(query, neighbor)
                val worst = w.peek()?.second ?: Float.POSITIVE_INFINITY
                if (d < worst || w.size < ef) {
                    candidates.add(neighbor to d)
                    w.add(neighbor to d)
                    if (w.size > ef) w.poll()
                }
            }
        }
        return w.map { it.first }
    }

    private fun selectNeighbors(source: Int, candidates: List<Int>, cap: Int): List<Int> {
        return candidates
            .filter { it != source && !deleted[it] }
            .sortedBy { distance(source, it) }
            .take(cap)
    }

    private fun prune(node: Int, cap: Int, layer: Int) {
        val list = neighbors[layer][node]
        if (list.size <= cap) return
        val kept = list.distinct().filter { !deleted[it] }.sortedBy { distance(node, it) }.take(cap)
        list.clear()
        list.addAll(kept)
    }

    private fun distance(a: Int, b: Int): Float {
        val pa = points[a]
        val pb = points[b]
        return FeatureDistance.rms(pa.values, pa.presentMask, pb.values, pb.presentMask)
    }

    private fun distanceQuery(query: Point, index: Int): Float {
        val pb = points[index]
        return FeatureDistance.rms(query.values, query.presentMask, pb.values, pb.presentMask)
    }
}
