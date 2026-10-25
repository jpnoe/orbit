package com.orbit.wellness.domain.state

import com.orbit.wellness.data.local.StateVectorSnapshot

/**
 * Normalized 10-dimensional mood-space coordinate (values in 0..1).
 * [presentMask] marks axes that had real source data vs neutral fill.
 */
data class StateVector(
    val recordedAt: Long,
    val values: FloatArray,
    val presentMask: Int,
) {
    init {
        require(values.size == FeatureAxes.COUNT) {
            "StateVector requires ${FeatureAxes.COUNT} axes, got ${values.size}"
        }
    }

    fun value(axis: Int): Float = values[axis]

    fun isPresent(axis: Int): Boolean = (presentMask and FeatureAxes.bit(axis)) != 0

    fun toSnapshot(): StateVectorSnapshot = StateVectorSnapshot(
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
        presentMask = presentMask,
    )

    fun labeledPairs(): List<Pair<String, Float>> =
        FeatureAxes.IDS.mapIndexed { index, id -> id to values[index] }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StateVector) return false
        return recordedAt == other.recordedAt &&
            presentMask == other.presentMask &&
            values.contentEquals(other.values)
    }

    override fun hashCode(): Int {
        var result = recordedAt.hashCode()
        result = 31 * result + values.contentHashCode()
        result = 31 * result + presentMask
        return result
    }

    companion object {
        fun fromSnapshot(snapshot: StateVectorSnapshot): StateVector = StateVector(
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
    }
}
