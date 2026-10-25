package com.orbit.wellness.domain.topology

import android.os.SystemClock
import android.util.Log
import com.orbit.wellness.data.local.StateVectorSnapshot
import com.orbit.wellness.data.local.StateVectorSnapshotDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug seeder: replaces synthetic-* snapshots, compact-assigns, times a few
 * live ingests, then logs [NeighborhoodSuggester] on a depleted probe.
 */
class TopologyHarness(
    private val snapshotDao: StateVectorSnapshotDao,
    private val topologyRepository: TopologyRepository,
) {
    suspend fun seedAndEvaluate(total: Int = 800): String = withContext(Dispatchers.Default) {
        snapshotDao.deleteSynthetic()
        val snapshots = SyntheticRegimes.generate(total)
        snapshotDao.insertAll(snapshots)
        Log.d(TAG, "seeded ${snapshots.size} synthetic hours")

        logHnswInsertScale(snapshots)

        val compactStart = SystemClock.elapsedRealtime()
        topologyRepository.compact()
        Log.d(TAG, "compact ${SystemClock.elapsedRealtime() - compactStart}ms after seed")

        val extras = SyntheticRegimes.extraIngestPoints()
        snapshotDao.insertAll(extras)
        for (extra in extras) {
            topologyRepository.ingest(extra)
        }

        val suggestion = topologyRepository.suggestBetterNeighborhood(
            SyntheticRegimes.lowMoodProbe(),
        )
        val summary = formatSuggestion(suggestion)
        Log.i(TAG, summary)
        summary
    }

    private fun logHnswInsertScale(snapshots: List<StateVectorSnapshot>) {
        val index = HnswIndex()
        var batchStart = SystemClock.elapsedRealtime()
        snapshots.forEachIndexed { i, snapshot ->
            index.insert(
                HnswIndex.Point(
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
                ),
            )
            val n = i + 1
            if (n % 200 == 0 || n == snapshots.size) {
                val elapsed = SystemClock.elapsedRealtime() - batchStart
                Log.d(TAG, "hnsw-scale n=$n batch=${elapsed}ms (~${elapsed / 200.0}ms/insert)")
                batchStart = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun formatSuggestion(suggestion: NeighborhoodSuggestion?): String {
        if (suggestion == null) {
            return "suggest: no better neighborhood for depleted probe"
        }
        if (suggestion.tier == SuggestionTier.LEARNING) {
            return "suggest[learning]: ${suggestion.message}"
        }
        val deltas = suggestion.deltas.take(4).joinToString("; ") { delta ->
            val sign = if (delta.amount >= 0) "+" else ""
            "${delta.axisId} ${fmt(delta.from)}→${fmt(delta.to)} ($sign${fmt(delta.amount)})"
        }
        val cluster = suggestion.targetClusterId?.take(8) ?: "none"
        val prefix = if (suggestion.tier == SuggestionTier.SPARSE) "suggest[sparse]" else "suggest"
        return "$prefix: mood ${fmt(suggestion.currentMood)}→${fmt(suggestion.targetMood ?: 0f)} " +
            "dist=${fmt(suggestion.distance ?: 0f)} size=${suggestion.targetSize} " +
            "cluster=$cluster | $deltas"
    }

    private fun fmt(value: Float): String = "%.2f".format(value)

    companion object {
        private const val TAG = "OrbitTopology"
    }
}
