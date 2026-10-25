package com.orbit.wellness.domain.topology

import com.orbit.wellness.data.local.StateVectorSnapshot
import com.orbit.wellness.domain.state.FeatureAxes
import com.orbit.wellness.domain.state.StateVector
import kotlin.random.Random

/**
 * Four geometrically separated hourly regimes for topology debug / tests.
 */
object SyntheticRegimes {
    const val ID_PREFIX = "synthetic-"
    const val ALL_PRESENT_MASK = (1 shl FeatureAxes.COUNT) - 1
    private const val HOUR_MS = 60L * 60L * 1000L

    enum class Regime(
        val key: String,
        val center: FloatArray,
    ) {
        RESTED(
            "rested",
            floatArrayOf(0.85f, 0.70f, 0.82f, 0.35f, 0.72f, 0.65f, 0.22f, 0.18f, 0.10f, 0.90f),
        ),
        DEPLETED(
            "depleted",
            floatArrayOf(0.18f, 0.22f, 0.22f, 0.70f, 0.15f, 0.12f, 0.08f, 0.10f, 0.82f, 0.28f),
        ),
        BASELINE(
            "baseline",
            floatArrayOf(0.52f, 0.50f, 0.55f, 0.45f, 0.40f, 0.35f, 0.20f, 0.20f, 0.18f, 0.65f),
        ),
        TRAINING(
            "training",
            floatArrayOf(0.74f, 0.62f, 0.58f, 0.40f, 0.55f, 0.48f, 0.80f, 0.20f, 0.12f, 0.70f),
        ),
    }

    fun generate(
        total: Int = 800,
        nowMillis: Long = System.currentTimeMillis(),
        random: Random = Random(42),
    ): List<StateVectorSnapshot> {
        val regimes = Regime.entries
        val perRegime = total / regimes.size
        val snapshots = ArrayList<StateVectorSnapshot>(total)
        var hourOffset = total
        for (regime in regimes) {
            repeat(perRegime) { index ->
                hourOffset--
                snapshots += snapshot(
                    id = "$ID_PREFIX${regime.key}-$index",
                    recordedAt = nowMillis - hourOffset * HOUR_MS,
                    values = jitter(regime.center, random),
                )
            }
        }
        return snapshots.sortedBy { it.recordedAt }
    }

    fun lowMoodProbe(nowMillis: Long = System.currentTimeMillis()): StateVector {
        return StateVector(
            recordedAt = nowMillis,
            values = Regime.DEPLETED.center.copyOf(),
            presentMask = ALL_PRESENT_MASK,
        )
    }

    fun extraIngestPoints(
        nowMillis: Long = System.currentTimeMillis(),
        random: Random = Random(99),
    ): List<StateVectorSnapshot> {
        return Regime.entries.mapIndexed { index, regime ->
            snapshot(
                id = "$ID_PREFIX${regime.key}-probe-$index",
                recordedAt = nowMillis - index * 1_000L,
                values = jitter(regime.center, random),
            )
        }
    }

    private fun jitter(center: FloatArray, random: Random): FloatArray {
        return FloatArray(center.size) { axis ->
            (center[axis] + random.nextFloat() * 0.06f - 0.03f).coerceIn(0f, 1f)
        }
    }

    private fun snapshot(
        id: String,
        recordedAt: Long,
        values: FloatArray,
    ) = StateVectorSnapshot(
        id = id,
        recordedAt = recordedAt,
        moodCurrent = values[FeatureAxes.MOOD_CURRENT],
        moodTrend1h = values[FeatureAxes.MOOD_TREND_1H],
        sleepHoursLast = values[FeatureAxes.SLEEP_HOURS_LAST],
        rhrCurrent = values[FeatureAxes.RHR_CURRENT],
        stepsToday = values[FeatureAxes.STEPS_TODAY],
        activityLoad24h = values[FeatureAxes.ACTIVITY_LOAD_24H],
        workoutLoad24h = values[FeatureAxes.WORKOUT_LOAD_24H],
        supportiveCount24h = values[FeatureAxes.SUPPORTIVE_COUNT_24H],
        copingCount24h = values[FeatureAxes.COPING_COUNT_24H],
        mealRegularity = values[FeatureAxes.MEAL_REGULARITY],
        presentMask = ALL_PRESENT_MASK,
    )
}
