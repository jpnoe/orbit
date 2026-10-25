package com.orbit.wellness.domain.state

/**
 * Frozen Phase 2 feature space (exactly 10 axes).
 * Values on [StateVector] are normalized to 0..1.
 */
object FeatureAxes {
    const val COUNT = 10

    const val MOOD_CURRENT = 0
    const val MOOD_TREND_1H = 1
    const val SLEEP_HOURS_LAST = 2
    const val RHR_CURRENT = 3
    const val STEPS_TODAY = 4
    const val ACTIVITY_LOAD_24H = 5
    const val WORKOUT_LOAD_24H = 6
    const val SUPPORTIVE_COUNT_24H = 7
    const val COPING_COUNT_24H = 8
    const val MEAL_REGULARITY = 9

    val IDS = listOf(
        "mood_current",
        "mood_trend_1h",
        "sleep_hours_last",
        "rhr_current",
        "steps_today",
        "activity_load_24h",
        "workout_load_24h",
        "supportive_count_24h",
        "coping_count_24h",
        "meal_regularity",
    )

    fun bit(index: Int): Int = 1 shl index

    /** Neutral fill when a source is missing (keeps distance math defined). */
    const val MISSING_NEUTRAL = 0.5f

    const val EVALUATION_WINDOW_MS = 60L * 60L * 1000L
    const val LOAD_WINDOW_MS = 24L * 60L * 60L * 1000L
}
