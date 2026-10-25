package com.orbit.wellness.domain.topology

import com.orbit.wellness.domain.state.FeatureAxes
import com.orbit.wellness.domain.state.StateVector
import kotlin.math.abs

data class ClusterView(
    val id: String,
    val size: Int,
    val centroid: FloatArray,
    val presentMask: Int,
)

data class NeighborView(
    val snapshotId: String,
    val values: FloatArray,
    val presentMask: Int,
    val distance: Float,
)

data class AxisDelta(
    val axisId: String,
    val axisIndex: Int,
    val from: Float,
    val to: Float,
) {
    val amount: Float get() = to - from
}

enum class SuggestionTier {
    DENSE,
    SPARSE,
    LEARNING,
}

data class NeighborhoodSuggestion(
    val tier: SuggestionTier,
    val currentMood: Float,
    val targetClusterId: String? = null,
    val targetSize: Int = 0,
    val targetMood: Float? = null,
    val distance: Float? = null,
    val deltas: List<AxisDelta> = emptyList(),
    val message: String? = null,
)

object NeighborhoodSuggester {
    const val MIN_CLUSTER_SIZE = TopologyParams.MIN_PTS
    const val MOOD_MARGIN = 0.15f
    const val TARGET_MOOD_MIN = 0.55f
    const val DELTA_MIN = 0.08f
    const val SPARSE_NEIGHBORS = 3
    const val LEARNING_MESSAGE =
        "Still learning your baseline patterns—keep logging to unlock insights."

    private val skipAxes = setOf(
        FeatureAxes.MOOD_CURRENT,
        FeatureAxes.MOOD_TREND_1H,
    )

    fun suggest(
        now: StateVector,
        clusters: List<ClusterView>,
        neighbors: List<NeighborView> = emptyList(),
    ): NeighborhoodSuggestion? {
        if (!now.isPresent(FeatureAxes.MOOD_CURRENT)) return null
        val nowMood = now.values[FeatureAxes.MOOD_CURRENT]

        denseSuggestion(now, nowMood, clusters)?.let { return it }
        sparseSuggestion(now, nowMood, neighbors)?.let { return it }

        if (hasHighMoodHistory(clusters, neighbors)) return null
        return NeighborhoodSuggestion(
            tier = SuggestionTier.LEARNING,
            currentMood = nowMood,
            message = LEARNING_MESSAGE,
        )
    }

    private fun denseSuggestion(
        now: StateVector,
        nowMood: Float,
        clusters: List<ClusterView>,
    ): NeighborhoodSuggestion? {
        val best = clusters
            .filter { cluster ->
                cluster.size >= MIN_CLUSTER_SIZE && isBetterMood(cluster.centroid, cluster.presentMask, nowMood)
            }
            .minByOrNull { cluster ->
                FeatureDistance.rms(now.values, now.presentMask, cluster.centroid, cluster.presentMask)
            } ?: return null
        return buildDeltaSuggestion(
            tier = SuggestionTier.DENSE,
            now = now,
            nowMood = nowMood,
            targetId = best.id,
            targetSize = best.size,
            targetValues = best.centroid,
            targetMask = best.presentMask,
        )
    }

    private fun sparseSuggestion(
        now: StateVector,
        nowMood: Float,
        neighbors: List<NeighborView>,
    ): NeighborhoodSuggestion? {
        val better = neighbors
            .filter { neighbor ->
                neighbor.distance.isFinite() &&
                    neighbor.distance > 1e-5f &&
                    isBetterMood(neighbor.values, neighbor.presentMask, nowMood)
            }
            .sortedBy { it.distance }
            .take(SPARSE_NEIGHBORS)
        if (better.isEmpty()) return null

        val sums = FloatArray(FeatureAxes.COUNT)
        val counts = IntArray(FeatureAxes.COUNT)
        var distSum = 0f
        for (neighbor in better) {
            distSum += neighbor.distance
            for (axis in 0 until FeatureAxes.COUNT) {
                if (neighbor.presentMask and FeatureAxes.bit(axis) == 0) continue
                sums[axis] += neighbor.values[axis]
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
        return buildDeltaSuggestion(
            tier = SuggestionTier.SPARSE,
            now = now,
            nowMood = nowMood,
            targetId = better.first().snapshotId,
            targetSize = better.size,
            targetValues = centroid,
            targetMask = mask,
            distanceOverride = distSum / better.size,
        )
    }

    private fun buildDeltaSuggestion(
        tier: SuggestionTier,
        now: StateVector,
        nowMood: Float,
        targetId: String,
        targetSize: Int,
        targetValues: FloatArray,
        targetMask: Int,
        distanceOverride: Float? = null,
    ): NeighborhoodSuggestion? {
        val distance = distanceOverride ?: FeatureDistance.rms(
            now.values,
            now.presentMask,
            targetValues,
            targetMask,
        )
        if (!distance.isFinite()) return null
        val shared = now.presentMask and targetMask
        val deltas = (0 until FeatureAxes.COUNT)
            .filter { axis ->
                axis !in skipAxes &&
                    shared and FeatureAxes.bit(axis) != 0 &&
                    abs(targetValues[axis] - now.values[axis]) >= DELTA_MIN
            }
            .map { axis ->
                AxisDelta(
                    axisId = FeatureAxes.IDS[axis],
                    axisIndex = axis,
                    from = now.values[axis],
                    to = targetValues[axis],
                )
            }
            .sortedByDescending { abs(it.amount) }
        return NeighborhoodSuggestion(
            tier = tier,
            currentMood = nowMood,
            targetClusterId = targetId,
            targetSize = targetSize,
            targetMood = targetValues[FeatureAxes.MOOD_CURRENT],
            distance = distance,
            deltas = deltas,
        )
    }

    private fun isBetterMood(values: FloatArray, presentMask: Int, nowMood: Float): Boolean {
        if (presentMask and FeatureAxes.bit(FeatureAxes.MOOD_CURRENT) == 0) return false
        val mood = values[FeatureAxes.MOOD_CURRENT]
        return mood >= TARGET_MOOD_MIN && mood >= nowMood + MOOD_MARGIN
    }

    private fun hasHighMoodHistory(
        clusters: List<ClusterView>,
        neighbors: List<NeighborView>,
    ): Boolean {
        val clusterHit = clusters.any { cluster ->
            cluster.presentMask and FeatureAxes.bit(FeatureAxes.MOOD_CURRENT) != 0 &&
                cluster.centroid[FeatureAxes.MOOD_CURRENT] >= TARGET_MOOD_MIN
        }
        val neighborHit = neighbors.any { neighbor ->
            neighbor.presentMask and FeatureAxes.bit(FeatureAxes.MOOD_CURRENT) != 0 &&
                neighbor.values[FeatureAxes.MOOD_CURRENT] >= TARGET_MOOD_MIN
        }
        return clusterHit || neighborHit
    }
}
