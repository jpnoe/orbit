package com.orbit.wellness.domain.topology

import com.orbit.wellness.domain.state.FeatureAxes
import com.orbit.wellness.domain.state.StateVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighborhoodSuggesterTest {

    @Test
    fun depletedProbeRanksSleepUpAndCopingDown() {
        val clusters = SyntheticRegimes.Regime.entries.map { regime ->
            ClusterView(
                id = regime.key,
                size = 200,
                centroid = regime.center.copyOf(),
                presentMask = SyntheticRegimes.ALL_PRESENT_MASK,
            )
        }
        val suggestion = NeighborhoodSuggester.suggest(
            SyntheticRegimes.lowMoodProbe(),
            clusters,
        )
        assertNotNull(suggestion)
        val result = suggestion!!
        assertEquals(SuggestionTier.DENSE, result.tier)
        val targetMood = requireNotNull(result.targetMood)
        assertTrue(targetMood >= NeighborhoodSuggester.TARGET_MOOD_MIN)
        assertTrue(targetMood >= result.currentMood + NeighborhoodSuggester.MOOD_MARGIN)
        assertTrue(result.targetClusterId == "rested" || result.targetClusterId == "training")

        val sleep = result.deltas.first { it.axisId == "sleep_hours_last" }
        val coping = result.deltas.first { it.axisId == "coping_count_24h" }
        assertTrue(sleep.amount > 0f)
        assertTrue(coping.amount < 0f)
        assertEquals("mood_current", FeatureAxes.IDS[FeatureAxes.MOOD_CURRENT])
        assertTrue(result.deltas.none { it.axisId == "mood_current" })
    }

    @Test
    fun highMoodHasNoBetterNeighborhood() {
        val rested = ClusterView(
            id = "rested",
            size = 200,
            centroid = SyntheticRegimes.Regime.RESTED.center.copyOf(),
            presentMask = SyntheticRegimes.ALL_PRESENT_MASK,
        )
        val now = StateVector(
            recordedAt = 0L,
            values = SyntheticRegimes.Regime.RESTED.center.copyOf(),
            presentMask = SyntheticRegimes.ALL_PRESENT_MASK,
        )
        assertNull(NeighborhoodSuggester.suggest(now, listOf(rested)))
    }

    @Test
    fun missingMoodDoesNotSuggest() {
        val now = StateVector(
            recordedAt = 0L,
            values = SyntheticRegimes.Regime.DEPLETED.center.copyOf(),
            presentMask = 0,
        )
        val cluster = ClusterView(
            id = "rested",
            size = 200,
            centroid = SyntheticRegimes.Regime.RESTED.center.copyOf(),
            presentMask = SyntheticRegimes.ALL_PRESENT_MASK,
        )
        assertNull(NeighborhoodSuggester.suggest(now, listOf(cluster)))
    }

    @Test
    fun sparseKnnAveragesTopBetterHours() {
        val now = SyntheticRegimes.lowMoodProbe()
        val undersized = ClusterView(
            id = "tiny-rested",
            size = 2,
            centroid = SyntheticRegimes.Regime.RESTED.center.copyOf(),
            presentMask = SyntheticRegimes.ALL_PRESENT_MASK,
        )
        val neighbors = listOf(
            neighbor("far-training", SyntheticRegimes.Regime.TRAINING.center, 0.40f),
            neighbor("near-rested", SyntheticRegimes.Regime.RESTED.center, 0.22f),
            neighbor("mid-rested", jitterSleep(SyntheticRegimes.Regime.RESTED.center, 0.88f), 0.28f),
            neighbor("low", SyntheticRegimes.Regime.DEPLETED.center, 0.05f),
        )
        val suggestion = NeighborhoodSuggester.suggest(now, listOf(undersized), neighbors)
        assertNotNull(suggestion)
        val result = suggestion!!
        assertEquals(SuggestionTier.SPARSE, result.tier)
        assertEquals("near-rested", result.targetClusterId)
        assertEquals(3, result.targetSize)
        val sleep = result.deltas.first { it.axisId == "sleep_hours_last" }
        val coping = result.deltas.first { it.axisId == "coping_count_24h" }
        assertTrue(sleep.amount > 0f)
        assertTrue(coping.amount < 0f)
    }

    @Test
    fun dayOneReturnsLearningMessage() {
        val suggestion = NeighborhoodSuggester.suggest(
            SyntheticRegimes.lowMoodProbe(),
            clusters = emptyList(),
            neighbors = emptyList(),
        )
        assertNotNull(suggestion)
        val result = suggestion!!
        assertEquals(SuggestionTier.LEARNING, result.tier)
        assertNull(result.targetClusterId)
        assertEquals(NeighborhoodSuggester.LEARNING_MESSAGE, result.message)
        assertTrue(result.deltas.isEmpty())
    }

    @Test
    fun sparseIgnoresLowMoodNeighborsAndFallsToLearning() {
        val onlyLow = listOf(
            neighbor("depleted-1", SyntheticRegimes.Regime.DEPLETED.center, 0.04f),
            neighbor("depleted-2", SyntheticRegimes.Regime.DEPLETED.center, 0.08f),
        )
        val suggestion = NeighborhoodSuggester.suggest(
            SyntheticRegimes.lowMoodProbe(),
            clusters = emptyList(),
            neighbors = onlyLow,
        )
        assertEquals(SuggestionTier.LEARNING, suggestion?.tier)
        assertEquals(NeighborhoodSuggester.LEARNING_MESSAGE, suggestion?.message)
    }

    private fun neighbor(id: String, values: FloatArray, distance: Float) = NeighborView(
        snapshotId = id,
        values = values.copyOf(),
        presentMask = SyntheticRegimes.ALL_PRESENT_MASK,
        distance = distance,
    )

    private fun jitterSleep(values: FloatArray, sleep: Float): FloatArray {
        val copy = values.copyOf()
        copy[FeatureAxes.SLEEP_HOURS_LAST] = sleep
        return copy
    }
}
