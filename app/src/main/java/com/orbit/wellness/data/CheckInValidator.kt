package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInAxisValue

data class CheckInAxisInput(
    val axisId: String,
    val value: Int,
)

object CheckInValidator {
    fun validate(values: List<CheckInAxisInput>) {
        require(values.isNotEmpty()) {
            "At least one axis value is required"
        }
        val duplicateIds = values
            .groupingBy { it.axisId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Duplicate axis IDs are not allowed: ${duplicateIds.sorted().joinToString()}"
        }
        values.forEach { input ->
            require(input.value in CheckInAxisValue.MIN_VALUE..CheckInAxisValue.MAX_VALUE) {
                "Axis value must be in ${CheckInAxisValue.MIN_VALUE}..${CheckInAxisValue.MAX_VALUE}, " +
                    "was ${input.value} for ${input.axisId}"
            }
        }
    }
}
