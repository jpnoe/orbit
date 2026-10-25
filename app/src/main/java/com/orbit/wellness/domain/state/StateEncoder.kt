package com.orbit.wellness.domain.state

import com.orbit.wellness.data.CurrentMoodStore
import com.orbit.wellness.data.local.ActivityEvent
import com.orbit.wellness.data.local.ActivityEventDao
import com.orbit.wellness.data.local.BiometricSnapshotDao
import com.orbit.wellness.data.local.MealEventDao
import com.orbit.wellness.data.local.MoodSampleDao
import com.orbit.wellness.data.local.SleepEventDao
import com.orbit.wellness.data.local.StateVectorSnapshot
import com.orbit.wellness.data.local.StateVectorSnapshotDao
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceUseEventDao
import com.orbit.wellness.data.local.WorkoutEvent
import com.orbit.wellness.data.local.WorkoutEventDao

/**
 * Projects Room logs + latest biometric snapshot into the frozen 10-axis [StateVector].
 * Evaluation window for mood trend is a strict 1-hour rolling window.
 */
class StateEncoder(
    private val moodSampleDao: MoodSampleDao,
    private val sleepEventDao: SleepEventDao,
    private val activityEventDao: ActivityEventDao,
    private val workoutEventDao: WorkoutEventDao,
    private val substanceUseEventDao: SubstanceUseEventDao,
    private val mealEventDao: MealEventDao,
    private val biometricSnapshotDao: BiometricSnapshotDao,
    private val stateVectorSnapshotDao: StateVectorSnapshotDao,
    private val currentMoodStore: CurrentMoodStore,
) {
    suspend fun encode(nowMillis: Long = System.currentTimeMillis()): StateVector {
        val values = FloatArray(FeatureAxes.COUNT) { FeatureAxes.MISSING_NEUTRAL }
        var presentMask = 0

        fun set(axis: Int, rawNormalized: Float, present: Boolean) {
            values[axis] = rawNormalized.coerceIn(0f, 1f)
            if (present) presentMask = presentMask or FeatureAxes.bit(axis)
        }

        val moodCurrent = currentMoodStore.committedMood.value
        set(FeatureAxes.MOOD_CURRENT, moodCurrent / 10f, present = true)

        val windowStart = nowMillis - FeatureAxes.EVALUATION_WINDOW_MS
        val samplesInHour = moodSampleDao.getBetween(windowStart, nowMillis)
        val trendDelta = when {
            samplesInHour.size >= 2 -> {
                moodCurrent - samplesInHour.first().moodScore.toFloat()
            }
            samplesInHour.size == 1 -> {
                moodCurrent - samplesInHour.first().moodScore.toFloat()
            }
            else -> 0f
        }
        // Map delta [-10, +10] → [0, 1]; 0.5 = stable.
        set(
            FeatureAxes.MOOD_TREND_1H,
            ((trendDelta + 10f) / 20f).coerceIn(0f, 1f),
            present = true,
        )

        val biometric = biometricSnapshotDao.getLatest()
        val manualSleep = sleepEventDao.getLatest()
        // Prefer cemented Mood-tab sleep log; fall back to HC hours from last sync.
        val sleepHours = manualSleep?.let {
            (it.wakeTime - it.bedtime).coerceAtLeast(0L) / 3_600_000.0
        } ?: biometric?.sleepHoursLast
        set(
            FeatureAxes.SLEEP_HOURS_LAST,
            ((sleepHours ?: 0.0) / SLEEP_HOURS_CAP).toFloat(),
            present = sleepHours != null,
        )
        if (sleepHours == null) values[FeatureAxes.SLEEP_HOURS_LAST] = FeatureAxes.MISSING_NEUTRAL

        val rhr = biometric?.restingHeartRateBpm
        if (rhr != null) {
            set(
                FeatureAxes.RHR_CURRENT,
                ((rhr - RHR_MIN) / (RHR_MAX - RHR_MIN)).toFloat(),
                present = true,
            )
        }

        val steps = biometric?.stepsToday
        if (steps != null) {
            set(
                FeatureAxes.STEPS_TODAY,
                (steps.toDouble() / STEPS_CAP).toFloat(),
                present = true,
            )
        }

        val loadStart = nowMillis - FeatureAxes.LOAD_WINDOW_MS
        val activities = activityEventDao.getBetween(loadStart, nowMillis)
        val activityMinutes = activities.sumOf { activityDurationMinutes(it, nowMillis) }
        set(
            FeatureAxes.ACTIVITY_LOAD_24H,
            (activityMinutes / ACTIVITY_MINUTES_CAP).toFloat(),
            present = activities.isNotEmpty(),
        )
        if (activities.isEmpty()) values[FeatureAxes.ACTIVITY_LOAD_24H] = FeatureAxes.MISSING_NEUTRAL

        val workouts = workoutEventDao.getBetween(loadStart, nowMillis)
        val workoutMinutes = workouts.sumOf { workoutDurationMinutes(it, nowMillis).toDouble() }
        set(
            FeatureAxes.WORKOUT_LOAD_24H,
            (workoutMinutes / WORKOUT_MINUTES_CAP).toFloat(),
            present = workouts.isNotEmpty(),
        )
        if (workouts.isEmpty()) values[FeatureAxes.WORKOUT_LOAD_24H] = FeatureAxes.MISSING_NEUTRAL

        val substances = substanceUseEventDao.getBetween(loadStart, nowMillis)
        val supportive = substances.count { it.intent == SubstanceIntent.SUPPORTIVE.name }
        val coping = substances.count { it.intent == SubstanceIntent.COPING.name }
        set(
            FeatureAxes.SUPPORTIVE_COUNT_24H,
            supportive / COUNT_CAP,
            present = supportive > 0,
        )
        if (supportive == 0) values[FeatureAxes.SUPPORTIVE_COUNT_24H] = FeatureAxes.MISSING_NEUTRAL
        set(
            FeatureAxes.COPING_COUNT_24H,
            coping / COUNT_CAP,
            present = coping > 0,
        )
        if (coping == 0) values[FeatureAxes.COPING_COUNT_24H] = FeatureAxes.MISSING_NEUTRAL

        val meals = mealEventDao.getBetween(loadStart, nowMillis)
        set(
            FeatureAxes.MEAL_REGULARITY,
            mealRegularityScore(meals.size),
            present = meals.isNotEmpty(),
        )
        if (meals.isEmpty()) values[FeatureAxes.MEAL_REGULARITY] = FeatureAxes.MISSING_NEUTRAL

        return StateVector(
            recordedAt = nowMillis,
            values = values,
            presentMask = presentMask,
        )
    }

    suspend fun encodeAndPersist(nowMillis: Long = System.currentTimeMillis()): StateVectorSnapshot {
        val snapshot = encode(nowMillis).toSnapshot()
        stateVectorSnapshotDao.insert(snapshot)
        return snapshot
    }

    private fun activityDurationMinutes(event: ActivityEvent, nowMillis: Long): Double {
        val end = event.endTime ?: nowMillis
        val millis = (end - event.startTime).coerceAtLeast(0L)
        return millis / 60_000.0
    }

    private fun workoutDurationMinutes(event: WorkoutEvent, nowMillis: Long): Int {
        if (event.durationMinutes > 0) return event.durationMinutes
        val end = event.endTime ?: nowMillis
        return ((end - event.startTime).coerceAtLeast(0L) / 60_000L).toInt()
    }

    /**
     * 0 meals → missing handled by caller; 1–2 rising; 3–4 ideal; 5+ slight decay.
     */
    private fun mealRegularityScore(mealCount: Int): Float = when {
        mealCount <= 0 -> 0f
        mealCount == 1 -> 0.35f
        mealCount == 2 -> 0.7f
        mealCount in 3..4 -> 1f
        else -> (1f - 0.1f * (mealCount - 4)).coerceAtLeast(0.4f)
    }

    companion object {
        private const val SLEEP_HOURS_CAP = 12.0
        private const val RHR_MIN = 40.0
        private const val RHR_MAX = 100.0
        private const val STEPS_CAP = 15_000.0
        private const val ACTIVITY_MINUTES_CAP = 180.0
        private const val WORKOUT_MINUTES_CAP = 120.0
        private const val COUNT_CAP = 4f
    }
}
