package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.SubstanceIntent

data class CheckInUsageSelection(
    val substanceOptionId: String?,
    val name: String,
    val intent: String,
    val amount: String,
)

object CheckInUsageSelections {
    const val NONE_LABEL = "No usage to report"

    fun validate(selections: List<CheckInUsageSelection>, contextWindowExists: Boolean) {
        require(contextWindowExists) {
            "Usage context window does not exist"
        }
        require(selections.none { it.name.isBlank() }) {
            "Usage name must not be blank"
        }
        require(selections.none { it.substanceOptionId?.isBlank() == true }) {
            "Usage option id must not be blank"
        }
        require(selections.all { it.intent in CheckInUsageItem.ALLOWED_INTENTS }) {
            "Usage intent must be ${SubstanceIntent.SUPPORTIVE.name} or ${SubstanceIntent.COPING.name}"
        }
        val duplicateOptionIds = selections
            .mapNotNull { it.substanceOptionId }
            .groupingBy { it }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateOptionIds.isEmpty()) {
            "Duplicate usage options are not allowed: ${duplicateOptionIds.sorted().joinToString()}"
        }
        require(selections.none { it.name.trim().equals(NONE_LABEL, ignoreCase = true) }) {
            "A none placeholder is not a usage item"
        }
    }
}
