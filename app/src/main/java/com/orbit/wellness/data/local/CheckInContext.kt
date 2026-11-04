package com.orbit.wellness.data.local

object CheckInContextStartBasis {
    const val PREVIOUS_CHECK_IN = "previous_check_in"
    const val WAKE_TIME = "wake_time"
    const val DAY_START = "day_start"

    val ALL = setOf(PREVIOUS_CHECK_IN, WAKE_TIME, DAY_START)
}

object CheckInContextCategories {
    const val ACTIVITY = "activity"
    const val WORKOUT = "workout"
    const val DIET = "diet"
    const val USAGE = "usage"

    val ALL = listOf(ACTIVITY, WORKOUT, DIET, USAGE)
}

object CheckInContextStatus {
    const val UNREVIEWED = "unreviewed"
    const val NONE = "none"
    const val ENTRIES = "entries"

    val ALL = setOf(UNREVIEWED, NONE, ENTRIES)
}
