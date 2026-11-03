package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInActivitySelection
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.local.ActivityOption

sealed interface ActivityReviewCommit {
    data object Leave : ActivityReviewCommit
    data class Replace(val selections: List<CheckInActivitySelection>) : ActivityReviewCommit
}

class ContextualActivitySelection {
    private var applied = false
    private var optionsReady = false
    private var itemsReady = false
    private var options: List<ActivityOption> = emptyList()
    private var savedOptionIds: List<String> = emptyList()
    private var pendingName: String? = null

    var selectedIds: Set<String> = emptySet()
        private set

    fun onOptions(options: List<ActivityOption>) {
        this.options = options
        optionsReady = true
        preloadIfReady()
        val pending = pendingName ?: return
        val match = options.firstOrNull { it.name.equals(pending, ignoreCase = true) } ?: return
        selectedIds = selectedIds + match.id
        pendingName = null
    }

    fun onSavedItems(optionIds: List<String?>) {
        savedOptionIds = optionIds.mapNotNull { it }
        itemsReady = true
        preloadIfReady()
    }

    fun toggle(optionId: String) {
        selectedIds = if (optionId in selectedIds) {
            selectedIds - optionId
        } else {
            selectedIds + optionId
        }
    }

    fun selectAddedName(name: String) {
        pendingName = name.trim()
        val match = options.firstOrNull { it.name.equals(pendingName, ignoreCase = true) }
        if (match != null) {
            selectedIds = selectedIds + match.id
            pendingName = null
        }
    }

    fun snapshots(): List<CheckInActivitySelection> {
        return options
            .filter { it.id in selectedIds }
            .sortedBy { it.sortOrder }
            .map { option ->
                CheckInActivitySelection(
                    activityOptionId = option.id,
                    activityName = option.name,
                )
            }
    }

    fun commitForSave(): ActivityReviewCommit = ActivityReviewCommit.Replace(snapshots())

    fun commitForNone(): ActivityReviewCommit = ActivityReviewCommit.Replace(emptyList())

    fun commitForBack(): ActivityReviewCommit = ActivityReviewCommit.Leave

    private fun preloadIfReady() {
        if (applied || !optionsReady || !itemsReady) return
        val available = options.map { it.id }.toSet()
        selectedIds = savedOptionIds.filter { it in available }.toSet()
        applied = true
    }
}

suspend fun commitActivityReview(
    repository: CheckInRepository,
    checkInId: String,
    commit: ActivityReviewCommit,
) {
    when (commit) {
        ActivityReviewCommit.Leave -> Unit
        is ActivityReviewCommit.Replace -> repository.saveActivitySelections(
            checkInId = checkInId,
            selections = commit.selections,
        )
    }
}
