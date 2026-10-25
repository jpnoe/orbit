package com.orbit.wellness.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Last **logged** mood for seeding sliders.
 * Draft slider moves stay on the screen; leaving without logging snaps back here.
 */
class CurrentMoodStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _committedMood = MutableStateFlow(
        prefs.getFloat(KEY_SCORE, DEFAULT_MOOD).coerceIn(0f, 10f),
    )
    val committedMood: StateFlow<Float> = _committedMood.asStateFlow()

    private var committedAt: Long = prefs.getLong(KEY_AT, 0L)

    /** Persist a mood that was actually logged (check-in, activity, use, …). */
    fun commit(score: Float, recordedAt: Long = System.currentTimeMillis()) {
        val value = score.coerceIn(0f, 10f)
        _committedMood.value = value
        committedAt = recordedAt
        prefs.edit()
            .putFloat(KEY_SCORE, value)
            .putLong(KEY_AT, recordedAt)
            .apply()
    }

    /**
     * Apply a DB mood sample if it is at least as new as the last commit
     * (covers process death after check-in / activity logs).
     */
    fun hydrateFromSample(moodScore: Int, recordedAt: Long) {
        if (recordedAt >= committedAt) {
            commit(moodScore.toFloat(), recordedAt)
        }
    }

    companion object {
        private const val PREFS = "orbit_committed_mood"
        private const val KEY_SCORE = "score"
        private const val KEY_AT = "recorded_at"
        const val DEFAULT_MOOD = 5f
    }
}
