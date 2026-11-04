package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.CheckInUsageSelection
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceOption

data class ContextualUsageLine(
    val key: String,
    val substanceOptionId: String?,
    val name: String,
    val intent: String,
    val amount: String,
    val sortOrder: Int,
)

sealed interface UsageReviewCommit {
    data object Leave : UsageReviewCommit
    data class Replace(val selections: List<CheckInUsageSelection>) : UsageReviewCommit
}

class ContextualUsageSelection {
    private var applied = false
    private var optionsReady = false
    private var itemsReady = false
    private var options: List<SubstanceOption> = emptyList()
    private var saved: List<CheckInUsageItem> = emptyList()
    private var pendingName: String? = null
    private var pendingIntent: String? = null

    var lines: List<ContextualUsageLine> = emptyList()
        private set

    fun onOptions(options: List<SubstanceOption>) {
        this.options = options
        optionsReady = true
        preloadIfReady()
        applyPending()
    }

    fun onSavedItems(items: List<CheckInUsageItem>) {
        saved = items
        itemsReady = true
        preloadIfReady()
    }

    fun toggle(option: SubstanceOption) {
        val existing = lines.indexOfFirst { it.substanceOptionId == option.id }
        lines = if (existing >= 0) {
            lines.filterIndexed { index, _ -> index != existing }
        } else {
            lines + lineFor(option, option.defaultAmount)
        }
    }

    fun onAmountChanged(key: String, amount: String) {
        lines = lines.map { line ->
            if (line.key == key) line.copy(amount = amount) else line
        }
    }

    fun remove(key: String) {
        lines = lines.filterNot { it.key == key }
    }

    fun selectAdded(name: String, intent: SubstanceIntent) {
        pendingName = name.trim()
        pendingIntent = intent.name
        applyPending()
    }

    fun snapshots(): List<CheckInUsageSelection> {
        return lines
            .sortedWith(
                compareBy<ContextualUsageLine> { intentRank(it.intent) }
                    .thenBy { it.sortOrder }
                    .thenBy { it.name },
            )
            .map { line ->
                val live = options.firstOrNull { it.id == line.substanceOptionId }
                CheckInUsageSelection(
                    substanceOptionId = line.substanceOptionId,
                    name = live?.name ?: line.name,
                    intent = live?.intent ?: line.intent,
                    amount = line.amount,
                )
            }
    }

    fun commitForSave(): UsageReviewCommit = UsageReviewCommit.Replace(snapshots())

    fun commitForNone(): UsageReviewCommit = UsageReviewCommit.Replace(emptyList())

    fun commitForBack(): UsageReviewCommit = UsageReviewCommit.Leave

    private fun preloadIfReady() {
        if (applied || !optionsReady || !itemsReady) return
        lines = saved.map { item ->
            val option = item.substanceOptionId?.let { id -> options.firstOrNull { it.id == id } }
            ContextualUsageLine(
                key = item.substanceOptionId ?: item.id,
                substanceOptionId = item.substanceOptionId,
                name = item.name,
                intent = item.intent,
                amount = item.amount,
                sortOrder = option?.sortOrder ?: Int.MAX_VALUE,
            )
        }
        applied = true
    }

    private fun applyPending() {
        val name = pendingName ?: return
        val intent = pendingIntent ?: return
        val match = options.firstOrNull { option ->
            option.intent == intent && option.name.equals(name, ignoreCase = true)
        } ?: return
        if (lines.none { it.substanceOptionId == match.id }) {
            lines = lines + lineFor(match, match.defaultAmount)
        }
        pendingName = null
        pendingIntent = null
    }

    private fun lineFor(option: SubstanceOption, amount: String) = ContextualUsageLine(
        key = option.id,
        substanceOptionId = option.id,
        name = option.name,
        intent = option.intent,
        amount = amount,
        sortOrder = option.sortOrder,
    )

    private fun intentRank(intent: String): Int = when (intent) {
        SubstanceIntent.SUPPORTIVE.name -> 0
        SubstanceIntent.COPING.name -> 1
        else -> 2
    }
}

suspend fun commitUsageReview(
    repository: CheckInRepository,
    checkInId: String,
    commit: UsageReviewCommit,
) {
    when (commit) {
        UsageReviewCommit.Leave -> Unit
        is UsageReviewCommit.Replace -> repository.saveUsageSelections(
            checkInId = checkInId,
            selections = commit.selections,
        )
    }
}
