package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.CheckInWorkoutSelection
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.WorkoutOption
import java.util.UUID

internal const val CONTEXTUAL_WORKOUT_NONE_LABEL = "No workouts to report"

val CONTEXTUAL_WORKOUT_DURATION_PRESETS = listOf(10, 20, 30, 45, 60, 90)

data class WorkoutDraftEntry(
    val id: String = UUID.randomUUID().toString(),
    val workoutOptionId: String?,
    val workoutName: String,
    val durationMinutes: Int,
)

sealed interface WorkoutReviewCommit {
    data object Leave : WorkoutReviewCommit
    data class Replace(val selections: List<CheckInWorkoutSelection>) : WorkoutReviewCommit
}

class ContextualWorkoutDraft {
    private var applied = false
    private var optionsReady = false
    private var itemsReady = false
    private var options: List<WorkoutOption> = emptyList()
    private var savedItems: List<CheckInWorkoutItem> = emptyList()
    private var pendingName: String? = null

    var entries: List<WorkoutDraftEntry> = emptyList()
        private set

    var selectedOptionId: String? = null
        private set

    var presetMinutes: Int? = null
        private set

    var customDurationText: String = ""
        private set

    var editingId: String? = null
        private set

    val resolvedDuration: Int?
        get() = when {
            customDurationText.isNotBlank() -> customDurationText.toIntOrNull()
            else -> presetMinutes
        }

    fun onOptions(options: List<WorkoutOption>) {
        this.options = options
        optionsReady = true
        preloadIfReady()
        val pending = pendingName ?: return
        val match = options.firstOrNull { it.name.equals(pending, ignoreCase = true) } ?: return
        selectedOptionId = match.id
        pendingName = null
    }

    fun onSavedItems(items: List<CheckInWorkoutItem>) {
        savedItems = items
        itemsReady = true
        preloadIfReady()
    }

    fun selectOption(optionId: String) {
        selectedOptionId = optionId
    }

    fun selectPreset(minutes: Int) {
        presetMinutes = minutes
        customDurationText = ""
    }

    fun onCustomDuration(text: String) {
        customDurationText = text.filter { it.isDigit() }.take(4)
        presetMinutes = null
    }

    fun selectAddedName(name: String) {
        pendingName = name.trim()
        val match = options.firstOrNull { it.name.equals(pendingName, ignoreCase = true) }
        if (match != null) {
            selectedOptionId = match.id
            pendingName = null
        }
    }

    fun beginEdit(entryId: String) {
        val entry = entries.firstOrNull { it.id == entryId } ?: return
        editingId = entry.id
        selectedOptionId = entry.workoutOptionId
        if (entry.durationMinutes in CONTEXTUAL_WORKOUT_DURATION_PRESETS) {
            presetMinutes = entry.durationMinutes
            customDurationText = ""
        } else {
            presetMinutes = null
            customDurationText = entry.durationMinutes.toString()
        }
    }

    fun remove(entryId: String) {
        entries = entries.filterNot { it.id == entryId }
        if (editingId == entryId) {
            clearComposer()
        }
    }

    /**
     * Adds the composer to the draft, or replaces the entry being edited.
     * Returns an error message when the composer is not a valid workout.
     */
    fun addOrUpdate(): String? {
        val option = options.firstOrNull { it.id == selectedOptionId }
            ?: return "Choose a workout type."
        val minutes = resolvedDuration ?: return "Choose a duration."
        if (minutes !in CheckInWorkoutItem.MIN_DURATION_MINUTES..CheckInWorkoutItem.MAX_DURATION_MINUTES) {
            return "Duration must be from ${CheckInWorkoutItem.MIN_DURATION_MINUTES} to ${CheckInWorkoutItem.MAX_DURATION_MINUTES} minutes."
        }
        val entry = WorkoutDraftEntry(
            id = editingId ?: UUID.randomUUID().toString(),
            workoutOptionId = option.id,
            workoutName = option.name,
            durationMinutes = minutes,
        )
        entries = if (editingId == null) {
            entries + entry
        } else {
            entries.map { current -> if (current.id == editingId) entry else current }
        }
        clearComposer()
        return null
    }

    fun snapshots(): List<CheckInWorkoutSelection> {
        return entries.map { entry ->
            CheckInWorkoutSelection(
                workoutOptionId = entry.workoutOptionId,
                workoutName = entry.workoutName,
                durationMinutes = entry.durationMinutes,
            )
        }
    }

    fun commitForSave(): WorkoutReviewCommit = WorkoutReviewCommit.Replace(snapshots())

    fun commitForNone(): WorkoutReviewCommit = WorkoutReviewCommit.Replace(emptyList())

    fun commitForBack(): WorkoutReviewCommit = WorkoutReviewCommit.Leave

    private fun preloadIfReady() {
        if (applied || !optionsReady || !itemsReady) return
        entries = savedItems.map { item ->
            WorkoutDraftEntry(
                id = item.id,
                workoutOptionId = item.workoutOptionId,
                workoutName = item.workoutName,
                durationMinutes = item.durationMinutes,
            )
        }
        applied = true
    }

    private fun clearComposer() {
        selectedOptionId = null
        presetMinutes = null
        customDurationText = ""
        editingId = null
    }
}

suspend fun commitWorkoutReview(
    repository: CheckInRepository,
    checkInId: String,
    commit: WorkoutReviewCommit,
) {
    when (commit) {
        WorkoutReviewCommit.Leave -> Unit
        is WorkoutReviewCommit.Replace -> repository.saveWorkoutSelections(
            checkInId = checkInId,
            selections = commit.selections,
        )
    }
}
