package com.orbit.wellness.data

import androidx.room.withTransaction
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.data.local.OrbitDatabase
import kotlinx.coroutines.CancellationException

/**
 * Optional legacy mood written in the same Room transaction as a configurable check-in.
 * [source] stays [MoodSample.SOURCE_CHECK_IN] so history can tell a check-in mood from an older mood log.
 */
data class LegacyCheckInMood(
    val score: Int,
    val note: String,
    val emotion: String,
    val recordedAt: Long,
    val source: String = MoodSample.SOURCE_CHECK_IN,
)

fun interface MoodSampleWriter {
    suspend fun insert(sample: MoodSample)
}

fun interface MoodSampleRemover {
    suspend fun delete(id: String)
}

/** Moves the legacy mood sample so it stays attached to the check-in end time. */
fun interface MoodSampleClock {
    suspend fun move(id: String, recordedAt: Long, localDate: String)
}

enum class IntervalTimeField {
    START,
    END,
}

sealed interface IntervalTimeAdjustment {
    data object Updated : IntervalTimeAdjustment
    data object Missing : IntervalTimeAdjustment
    data object StartAfterEnd : IntervalTimeAdjustment
}

data class SavedCheckIn(
    val record: CheckInRecord,
    val legacyMoodSampleId: String? = null,
)

/**
 * Runs a submission as one unit of work.
 * Production uses [RoomDatabaseTransaction]. Tests can restore in-memory state when the block throws.
 * That test restore shows the intended all-or-nothing shape. It does not prove SQLite crash rollback.
 */
interface DatabaseTransaction {
    suspend fun <R> run(block: suspend () -> R): R
}

class ImmediateDatabaseTransaction : DatabaseTransaction {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}

class RoomDatabaseTransaction(
    private val database: OrbitDatabase,
) : DatabaseTransaction {
    override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction(block)
}

/**
 * SharedPreferences cannot join the Room transaction.
 * Callers commit the current mood only when this returns a score after [submitted] is true.
 */
fun committedMoodAfterSubmission(explicitScore: Int?, submitted: Boolean): Float? {
    if (!submitted || explicitScore == null) return null
    return explicitScore.toFloat()
}

/**
 * Result of updating the preference cache after the Room submission has already succeeded.
 * [Failed] does not undo that submission. Startup hydration repairs the cache from the latest mood sample.
 */
sealed class MoodCacheUpdate {
    data object NotRequested : MoodCacheUpdate()
    data object Updated : MoodCacheUpdate()
    data class Failed(val error: Exception) : MoodCacheUpdate()
}

/**
 * Updates the committed-mood cache when this submission included an explicit mood.
 * A cache failure is returned, not thrown, so the caller can still treat the Room submission as saved.
 */
fun updateMoodCacheAfterSubmission(
    explicitScore: Int?,
    commit: (Float) -> Unit,
): MoodCacheUpdate {
    val score = committedMoodAfterSubmission(explicitScore, submitted = true)
        ?: return MoodCacheUpdate.NotRequested
    return try {
        commit(score)
        MoodCacheUpdate.Updated
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        MoodCacheUpdate.Failed(error)
    }
}
