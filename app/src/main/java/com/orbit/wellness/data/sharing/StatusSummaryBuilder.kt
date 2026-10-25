package com.orbit.wellness.data.sharing

import com.orbit.wellness.data.local.ActivityEventDao
import com.orbit.wellness.data.local.DailyJournalDao
import com.orbit.wellness.data.local.MealEventDao
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.data.local.MoodSampleDao
import com.orbit.wellness.data.local.SleepEventDao
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceUseEventDao
import com.orbit.wellness.data.local.TrustedContact
import com.orbit.wellness.data.local.WorkoutEventDao
import com.orbit.wellness.util.DateUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class StatusSummary(
    val body: String,
    val smsChunks: List<String>,
    val modulesShared: List<String>,
)

private data class TimelineEntry(
    val sortAt: Long,
    val line: String,
)

/**
 * Builds a clean SMS summary for a trusted contact using only permitted modules.
 *
 * Layout:
 * - Intro (separate lines): mood if that contact shares mood and data exists;
 *   sleep if they share sleep and data exists. `Orbit Status - [date]` only when
 *   neither mood nor sleep sharing is enabled. Journal is an optional extra intro line.
 * - Body: one fully chronological timeline (no Diet / Activities / … section splits)
 *
 * Packing rules:
 * - Maximize characters per SMS (GSM 7-bit ASCII → 153; Unicode → 68)
 * - No trailing dividers or accidental leading blank lines
 */
class StatusSummaryBuilder(
    private val moodSampleDao: MoodSampleDao,
    private val dailyJournalDao: DailyJournalDao,
    private val mealEventDao: MealEventDao,
    private val workoutEventDao: WorkoutEventDao,
    private val sleepEventDao: SleepEventDao,
    private val substanceUseEventDao: SubstanceUseEventDao,
    private val activityEventDao: ActivityEventDao,
) {
    suspend fun build(contact: TrustedContact): StatusSummary {
        val today = LocalDate.now()
        val todayStr = DateUtils.formatLocalDate(today)
        val startMillis = DateUtils.startOfDayMillis(today)
        val endMillis = DateUtils.endOfDayMillis(today)
        val modules = mutableListOf<String>()

        val moods = moodSampleDao.getForDate(todayStr)

        val topLines = mutableListOf<String>()

        if (contact.shareMood) {
            val latest = moods.firstOrNull()
            if (latest != null) {
                modules += "mood"
                topLines += if (latest.emotion.isNotBlank()) {
                    "Mood: ${latest.moodScore}/10 (${latest.emotion})"
                } else {
                    "Mood: ${latest.moodScore}/10"
                }
            }
        }

        if (contact.shareSleep) {
            val sleep = sleepEventDao.getForDate(todayStr)
            if (sleep != null) {
                modules += "sleep"
                val duration = DateUtils.sleepDurationLabel(sleep.bedtime, sleep.wakeTime)
                topLines += "Sleep: $duration - Quality ${sleep.sleepQuality}/10"
            }
        }

        if (!contact.shareMood && !contact.shareSleep) {
            topLines += "Orbit Status - ${dayFormatter.format(today)}"
        }

        if (contact.shareJournal) {
            val journal = dailyJournalDao.getByDate(todayStr)
            if (journal != null && journal.note.isNotBlank()) {
                modules += "journal"
                val snippet = journal.note.trim().replace('\n', ' ')
                val clipped = if (snippet.length > 80) snippet.take(77) + "…" else snippet
                topLines += "Journal: \"$clipped\""
            }
        }

        val timeline = mutableListOf<TimelineEntry>()

        if (contact.shareDiet) {
            val meals = mealEventDao.getBetween(startMillis, endMillis)
            if (meals.isNotEmpty()) {
                modules += "diet"
                meals.forEach { meal ->
                    val time = DateUtils.formatTime(meal.timestamp)
                    val label = meal.label.ifBlank { meal.summaryTitle }
                    val moodPart = if (contact.shareMood) {
                        findNearestMoodScore(meal.timestamp, moods)
                            ?.let { " - Mood $it/10" }
                            .orEmpty()
                    } else {
                        ""
                    }
                    timeline += TimelineEntry(
                        sortAt = meal.timestamp,
                        line = "- $time Diet: $label$moodPart",
                    )
                }
            }
        }

        if (contact.shareActivity) {
            val activities = activityEventDao.getBetween(startMillis, endMillis)
            if (activities.isNotEmpty()) {
                modules += "activity"
                activities.forEach { event ->
                    val at = event.effectiveAt
                    val range = if (
                        event.endTime != null &&
                        kotlin.math.abs(event.endTime - event.startTime) >= 60_000L
                    ) {
                        "${DateUtils.formatTime(event.startTime)}-${DateUtils.formatTime(event.endTime)} "
                    } else {
                        "${DateUtils.formatTime(at)} "
                    }
                    val moodPart = if (event.endTime == null) {
                        " - Mood TBD"
                    } else {
                        val post = event.postMood?.let { "$it/10" } ?: "…"
                        val pre = event.preMood?.let { "$it/10" } ?: "?"
                        " - Mood $pre -> $post"
                    }
                    timeline += TimelineEntry(
                        sortAt = at,
                        line = "- $range Activity: ${event.activityName}$moodPart",
                    )
                }
            }
        }

        if (contact.shareExercise) {
            val workouts = workoutEventDao.getBetween(startMillis, endMillis)
            if (workouts.isNotEmpty()) {
                modules += "exercise"
                workouts.forEach { workout ->
                    val at = workout.effectiveAt
                    val time = DateUtils.formatTime(at)
                    val moodPart = when {
                        workout.endTime == null -> " - Mood TBD"
                        workout.preMood != null && workout.postMood != null &&
                            kotlin.math.abs(workout.endTime - workout.startTime) >= 60_000L ->
                            " - Mood ${workout.preMood}/10 -> ${workout.postMood}/10"
                        workout.postMood != null -> " - Mood ${workout.postMood}/10"
                        contact.shareMood ->
                            findNearestMoodScore(at, moods)?.let { " - Mood $it/10" }.orEmpty()
                        else -> ""
                    }
                    val range = if (
                        workout.endTime != null &&
                        kotlin.math.abs(workout.endTime - workout.startTime) >= 60_000L
                    ) {
                        "${DateUtils.formatTime(workout.startTime)}-${DateUtils.formatTime(workout.endTime)} "
                    } else {
                        "$time "
                    }
                    timeline += TimelineEntry(
                        sortAt = at,
                        line = "- $range Exercise: ${workout.workoutType} (${workout.durationMinutes} min)$moodPart",
                    )
                }
            }
        }

        if (contact.shareSupportive || contact.shareCoping) {
            val uses = substanceUseEventDao.getBetween(startMillis, endMillis)
            if (contact.shareSupportive) {
                val supportive = uses.filter { it.intent == SubstanceIntent.SUPPORTIVE.name }
                if (supportive.isNotEmpty()) {
                    modules += "supportive"
                    supportive.forEach { event ->
                        timeline += TimelineEntry(
                            sortAt = event.timestamp,
                            line = formatUseLine("Supportive", event.name, event.amount, event.moodScore, event.timestamp),
                        )
                    }
                }
            }
            if (contact.shareCoping) {
                val coping = uses.filter { it.intent == SubstanceIntent.COPING.name }
                if (coping.isNotEmpty()) {
                    modules += "coping"
                    coping.forEach { event ->
                        timeline += TimelineEntry(
                            sortAt = event.timestamp,
                            line = formatUseLine("Coping", event.name, event.amount, event.moodScore, event.timestamp),
                        )
                    }
                }
            }
        }

        if (modules.isEmpty()) {
            val empty = "Orbit: no shared entries logged today for this contact."
            return StatusSummary(
                body = empty,
                smsChunks = listOf(empty),
                modulesShared = emptyList(),
            )
        }

        val timelineLines = timeline
            .sortedWith(compareBy({ it.sortAt }, { it.line }))
            .map { it.line }

        val allText = buildString {
            topLines.forEach { append(it) }
            timelineLines.forEach { append(it) }
        }
        val usesUnicode = allText.any { it.code > 127 }
        val limit = if (usesUnicode) 68 else 153

        val smsChunks = chunkStatusSummary(topLines, timelineLines, limit)
        val fullBody = smsChunks.joinToString("\n\n")

        return StatusSummary(
            body = fullBody,
            smsChunks = smsChunks,
            modulesShared = modules,
        )
    }

    private fun formatUseLine(
        kind: String,
        name: String,
        amount: String,
        moodScore: Int,
        timestamp: Long,
    ): String {
        val time = DateUtils.formatTime(timestamp)
        val amountPart = amount.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "- $time $kind: $name$amountPart - Mood $moodScore/10"
    }

    private fun findNearestMoodScore(timestamp: Long, moods: List<MoodSample>): Int? {
        if (moods.isEmpty()) return null
        return moods.minByOrNull { abs(it.recordedAt - timestamp) }?.moodScore
    }

    private fun chunkStatusSummary(
        topLines: List<String>,
        timelineLines: List<String>,
        limit: Int,
    ): List<String> {
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()

        fun flush() {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) chunks.add(text)
            buffer.clear()
        }

        fun canFit(extra: String): Boolean {
            if (extra.isEmpty()) return true
            return if (buffer.isEmpty()) {
                extra.length <= limit
            } else {
                buffer.length + extra.length <= limit
            }
        }

        fun appendRaw(text: String) {
            buffer.append(text)
        }

        fun appendLine(line: String, blankBefore: Boolean = false) {
            val piece = when {
                buffer.isEmpty() -> line
                blankBefore -> "\n\n$line"
                else -> "\n$line"
            }
            if (!canFit(piece)) {
                flush()
                if (line.length <= limit) {
                    appendRaw(line)
                } else {
                    appendRaw(line.take(limit))
                    flush()
                }
            } else {
                appendRaw(piece)
            }
        }

        topLines.forEach { appendLine(it) }

        timelineLines.forEachIndexed { index, line ->
            // One blank line between intro and the first timeline row when they share a chunk.
            appendLine(line, blankBefore = index == 0 && buffer.isNotEmpty())
        }

        flush()
        return chunks.ifEmpty {
            listOf(topLines.joinToString("\n").take(limit))
        }
    }

    companion object {
        private val dayFormatter = DateTimeFormatter.ofPattern("EEE MMM d")
    }
}
