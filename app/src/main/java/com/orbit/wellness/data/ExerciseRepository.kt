package com.orbit.wellness.data

import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.data.local.WorkoutEvent
import com.orbit.wellness.data.local.WorkoutEventDao
import com.orbit.wellness.data.local.WorkoutOption
import com.orbit.wellness.data.local.WorkoutOptionDao
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

class ExerciseRepository(
    private val eventDao: WorkoutEventDao,
    private val optionDao: WorkoutOptionDao,
    private val moodRepository: MoodRepository,
) {
    companion object {
        const val MAX_CONCURRENT = 2
    }

    fun observeWorkouts(): Flow<List<WorkoutEvent>> = eventDao.observeAll()

    fun observeActive(): Flow<List<WorkoutEvent>> = eventDao.observeActive()

    fun observeOptions(): Flow<List<WorkoutOption>> = optionDao.observeAll()

    suspend fun addCustomWorkoutType(name: String): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Workout type cannot be empty"))
        }
        if (optionDao.countByName(trimmed) > 0) {
            return Result.failure(IllegalArgumentException("\"$trimmed\" is already in your list."))
        }
        val nextOrder = optionDao.maxSortOrder() + 1
        optionDao.insert(
            WorkoutOption(
                name = trimmed,
                sortOrder = nextOrder,
                isCustom = true,
            ),
        )
        return Result.success(Unit)
    }

    suspend fun deleteWorkoutOption(id: String) {
        optionDao.deleteById(id)
    }

    /** One-shot log with mood at logging time; start inferred from duration. */
    suspend fun logWorkout(
        workoutType: String,
        durationMinutes: Int,
        moodScore: Int,
        muscleGroups: List<String> = emptyList(),
        cardioActivity: String? = null,
        notes: String = "",
        atMillis: Long = System.currentTimeMillis(),
    ): Result<WorkoutEvent> {
        val duration = durationMinutes.coerceAtLeast(1)
        val end = atMillis
        val start = end - duration * 60_000L
        val mood = moodScore.coerceIn(0, 10)
        val evaluatedPre = moodRepository.getLatestScoreAtOrBefore(start)
        moodRepository.logMood(
            moodScore = mood,
            source = MoodSample.SOURCE_WORKOUT_LOG,
            recordedAt = end,
        )
        val event = WorkoutEvent(
            timestamp = end,
            workoutType = workoutType.trim(),
            durationMinutes = duration,
            muscleGroups = muscleGroups.joinToString(","),
            cardioActivity = cardioActivity,
            notes = notes.trim(),
            startTime = start,
            endTime = end,
            preMood = evaluatedPre,
            postMood = mood,
        )
        eventDao.insert(event)
        return Result.success(event)
    }

    suspend fun startWorkout(
        workoutType: String,
        preMood: Int,
        muscleGroups: List<String> = emptyList(),
        cardioActivity: String? = null,
        notes: String = "",
        atMillis: Long = System.currentTimeMillis(),
    ): Result<WorkoutEvent> {
        val active = eventDao.getActive()
        if (active.size >= MAX_CONCURRENT) {
            return Result.failure(
                IllegalStateException("You can run up to $MAX_CONCURRENT workouts at once. Finish one first."),
            )
        }
        val type = workoutType.trim()
        if (active.any { it.workoutType.equals(type, ignoreCase = true) }) {
            return Result.failure(
                IllegalStateException("A \"$type\" workout is already in progress."),
            )
        }

        val at = atMillis
        val mood = preMood.coerceIn(0, 10)
        val evaluatedPre = moodRepository.getLatestScoreAtOrBefore(at)
        moodRepository.logMood(
            moodScore = mood,
            source = MoodSample.SOURCE_WORKOUT_PRE,
            recordedAt = at,
        )
        val event = WorkoutEvent(
            timestamp = at,
            workoutType = type,
            durationMinutes = 0,
            muscleGroups = muscleGroups.joinToString(","),
            cardioActivity = cardioActivity,
            notes = notes.trim(),
            startTime = at,
            endTime = null,
            preMood = evaluatedPre,
            postMood = null,
        )
        eventDao.insert(event)
        return Result.success(event)
    }

    suspend fun endWorkout(eventId: String, postMood: Int): Result<WorkoutEvent> {
        val active = eventDao.getById(eventId)
            ?: return Result.failure(IllegalStateException("Workout not found."))
        if (active.endTime != null) {
            return Result.failure(IllegalStateException("That workout is already finished."))
        }

        val now = System.currentTimeMillis()
        val mood = postMood.coerceIn(0, 10)
        moodRepository.logMood(
            moodScore = mood,
            source = MoodSample.SOURCE_WORKOUT_POST,
            recordedAt = now,
        )
        val durationMinutes = ((now - active.startTime) / 60_000.0)
            .roundToInt()
            .coerceAtLeast(1)
        val updated = active.copy(
            endTime = now,
            timestamp = now,
            postMood = mood,
            durationMinutes = durationMinutes,
        )
        eventDao.update(updated)
        return Result.success(updated)
    }

    suspend fun updateWorkout(event: WorkoutEvent, newDurationMinutes: Int, newNotes: String) {
        eventDao.update(
            event.copy(
                durationMinutes = newDurationMinutes.coerceAtLeast(1),
                notes = newNotes.trim(),
            ),
        )
    }
}
