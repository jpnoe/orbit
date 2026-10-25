package com.orbit.wellness.data

import com.orbit.wellness.data.local.ActivityEvent
import com.orbit.wellness.data.local.ActivityEventDao
import com.orbit.wellness.data.local.ActivityOption
import com.orbit.wellness.data.local.ActivityOptionDao
import com.orbit.wellness.data.local.MoodSample
import kotlinx.coroutines.flow.Flow

class ActivityRepository(
    private val eventDao: ActivityEventDao,
    private val optionDao: ActivityOptionDao,
    private val moodRepository: MoodRepository,
) {
    companion object {
        const val MAX_CONCURRENT = 2
    }

    fun observeOptions(): Flow<List<ActivityOption>> = optionDao.observeAll()

    fun observeActive(): Flow<List<ActivityEvent>> = eventDao.observeActive()

    fun observeAll(): Flow<List<ActivityEvent>> = eventDao.observeAll()

    suspend fun addCustomActivity(name: String): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Activity name cannot be empty"))
        }
        if (optionDao.countByName(trimmed) > 0) {
            return Result.failure(IllegalArgumentException("\"$trimmed\" is already in your list."))
        }
        val nextOrder = (optionDao.maxSortOrder() ?: -1) + 1
        optionDao.insert(
            ActivityOption(
                name = trimmed,
                sortOrder = nextOrder,
                isCustom = true,
            ),
        )
        return Result.success(Unit)
    }

    suspend fun deleteActivityOption(id: String) {
        optionDao.deleteById(id)
    }

    /** One-shot log: mood at the chosen time (start == end). */
    suspend fun logActivity(
        activityName: String,
        moodScore: Int,
        atMillis: Long = System.currentTimeMillis(),
    ): Result<ActivityEvent> {
        val at = atMillis
        val mood = moodScore.coerceIn(0, 10)
        val evaluatedPre = moodRepository.getLatestScoreAtOrBefore(at)
        moodRepository.logMood(
            moodScore = mood,
            source = MoodSample.SOURCE_ACTIVITY_LOG,
            recordedAt = at,
        )
        val event = ActivityEvent(
            activityName = activityName.trim(),
            startTime = at,
            endTime = at,
            preMood = evaluatedPre,
            postMood = mood,
        )
        eventDao.insert(event)
        return Result.success(event)
    }

    suspend fun startActivity(
        activityName: String,
        preMood: Int,
        atMillis: Long = System.currentTimeMillis(),
    ): Result<ActivityEvent> {
        val active = eventDao.getActive()
        if (active.size >= MAX_CONCURRENT) {
            return Result.failure(
                IllegalStateException("You can run up to $MAX_CONCURRENT activities at once. Finish one first."),
            )
        }
        if (active.any { it.activityName.equals(activityName, ignoreCase = true) }) {
            return Result.failure(
                IllegalStateException("\"$activityName\" is already in progress."),
            )
        }

        val at = atMillis
        val mood = preMood.coerceIn(0, 10)
        val evaluatedPre = moodRepository.getLatestScoreAtOrBefore(at)
        moodRepository.logMood(
            moodScore = mood,
            source = MoodSample.SOURCE_ACTIVITY_PRE,
            recordedAt = at,
        )

        val event = ActivityEvent(
            activityName = activityName,
            startTime = at,
            preMood = evaluatedPre,
        )
        eventDao.insert(event)
        return Result.success(event)
    }

    suspend fun endActivity(eventId: String, postMood: Int): Result<ActivityEvent> {
        val active = eventDao.getById(eventId)
            ?: return Result.failure(IllegalStateException("Activity not found."))
        if (active.endTime != null) {
            return Result.failure(IllegalStateException("That activity is already finished."))
        }

        val now = System.currentTimeMillis()
        val mood = postMood.coerceIn(0, 10)
        moodRepository.logMood(
            moodScore = mood,
            source = MoodSample.SOURCE_ACTIVITY_POST,
            recordedAt = now,
        )

        val updated = active.copy(
            endTime = now,
            postMood = mood,
        )
        eventDao.update(updated)
        return Result.success(updated)
    }

    suspend fun updateActivity(event: ActivityEvent, newPreMood: Int?, newPostMood: Int?) {
        eventDao.update(
            event.copy(
                preMood = newPreMood?.coerceIn(0, 10),
                postMood = newPostMood?.coerceIn(0, 10),
            ),
        )
    }
}
