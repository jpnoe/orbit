package com.orbit.wellness.domain.topology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticTopologyHarnessTest {

    @Test
    fun generateFourRegimes() {
        val snapshots = SyntheticRegimes.generate(800)
        assertEquals(800, snapshots.size)
        val keys = snapshots.map { it.id.substringAfter("synthetic-").substringBefore("-") }.toSet()
        assertEquals(
            setOf("rested", "depleted", "baseline", "training"),
            keys,
        )
    }

    @Test
    fun hnswInsertCostStaysBoundedAsNGrows() {
        val snapshots = SyntheticRegimes.generate(800)
        val index = HnswIndex()
        val batchMs = ArrayList<Double>()
        var batchStart = System.nanoTime()
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
            if (n % 200 == 0) {
                val ms = (System.nanoTime() - batchStart) / 1_000_000.0
                batchMs += ms
                batchStart = System.nanoTime()
            }
        }
        assertEquals(4, batchMs.size)
        // Last 200 inserts should not be an order of magnitude slower than the first 200.
        assertTrue(
            "hnsw batches ms=$batchMs",
            batchMs.last() < batchMs.first() * 20.0 + 80.0,
        )
    }

    @Test
    fun depletedIsFartherFromRestedThanFromBaseline() {
        val depleted = SyntheticRegimes.Regime.DEPLETED.center
        val rested = SyntheticRegimes.Regime.RESTED.center
        val baseline = SyntheticRegimes.Regime.BASELINE.center
        val mask = SyntheticRegimes.ALL_PRESENT_MASK
        val toRested = FeatureDistance.rms(depleted, mask, rested, mask)
        val toBaseline = FeatureDistance.rms(depleted, mask, baseline, mask)
        assertTrue("rested=$toRested baseline=$toBaseline", toRested > toBaseline)
    }
}
