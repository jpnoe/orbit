package com.orbit.wellness.data

data class CheckInActivitySelection(
    val activityOptionId: String?,
    val activityName: String,
)

object CheckInActivitySelections {
    fun validate(selections: List<CheckInActivitySelection>, contextWindowExists: Boolean) {
        require(contextWindowExists) {
            "Activity context window does not exist"
        }
        require(selections.none { it.activityName.isBlank() }) {
            "Activity name must not be blank"
        }
        require(selections.none { it.activityOptionId?.isBlank() == true }) {
            "Activity option id must not be blank"
        }
        val duplicateOptionIds = selections
            .mapNotNull { it.activityOptionId }
            .groupingBy { it }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateOptionIds.isEmpty()) {
            "Duplicate activity options are not allowed: ${duplicateOptionIds.sorted().joinToString()}"
        }
    }
}
