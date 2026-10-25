package com.orbit.wellness.data

import com.orbit.wellness.data.local.EmotionOption
import com.orbit.wellness.data.local.EmotionOptionDao
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.data.local.MoodSampleDao
import com.orbit.wellness.domain.timeWeightedMoodAverage
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import kotlin.math.roundToInt

class MoodRepository(
    private val dao: MoodSampleDao,
    private val emotionDao: EmotionOptionDao,
) {
    fun observeLastSevenDays(): Flow<List<MoodSample>> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(6)
        return dao.observeBetween(
            DateUtils.formatLocalDate(startDate),
            DateUtils.formatLocalDate(endDate),
        )
    }

    fun observeTodaySamples(): Flow<List<MoodSample>> {
        return dao.observeForDate(DateUtils.formatLocalDate(LocalDate.now()))
    }

    fun observeEmotionOptions(): Flow<List<EmotionOption>> = emotionDao.observeAll()

    suspend fun addCustomEmotion(name: String): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Emotion cannot be empty"))
        }
        if (emotionDao.countByName(trimmed) > 0) {
            return Result.failure(IllegalArgumentException("\"$trimmed\" is already in your list."))
        }
        val nextOrder = emotionDao.maxSortOrder() + 1
        emotionDao.insert(
            EmotionOption(
                name = trimmed,
                sortOrder = nextOrder,
                isCustom = true,
            ),
        )
        return Result.success(Unit)
    }

    suspend fun deleteEmotionOption(id: String) {
        emotionDao.deleteById(id)
    }

    fun observeDailyAveragesLastSevenDays(): Flow<Map<String, Int>> {
        return observeLastSevenDays().map { samples ->
            samples.groupBy { it.localDate }.mapValues { (localDate, daySamples) ->
                val date = DateUtils.parseLocalDate(localDate)
                val average = timeWeightedMoodAverage(
                    samples = daySamples.map { it.recordedAt to it.moodScore },
                    dayEndMillis = DateUtils.averageWindowEndMillis(date),
                )
                average?.roundToInt()?.coerceIn(0, 10) ?: daySamples.last().moodScore
            }
        }
    }

    suspend fun logMood(
        moodScore: Int,
        source: String = MoodSample.SOURCE_CHECK_IN,
        note: String = "",
        emotion: String = "",
        recordedAt: Long = System.currentTimeMillis(),
    ) {
        val date = DateUtils.formatLocalDate(DateUtils.localDateOf(recordedAt))
        dao.insert(
            MoodSample(
                localDate = date,
                moodScore = moodScore.coerceIn(0, 10),
                note = note.trim(),
                emotion = emotion.trim(),
                recordedAt = recordedAt,
                source = source,
            ),
        )
    }

    suspend fun getLatestSample(): MoodSample? = dao.getLatest()

    /** Most recent mood sample at or before [atMillis] (inclusive), per activity before-mood rules. */
    suspend fun getLatestScoreAtOrBefore(atMillis: Long): Int? =
        dao.getLatestAtOrBefore(atMillis)?.moodScore

    suspend fun updateMood(sample: MoodSample, newScore: Int, newNote: String) {
        dao.update(
            sample.copy(
                moodScore = newScore.coerceIn(0, 10),
                note = newNote.trim(),
            ),
        )
    }
}
