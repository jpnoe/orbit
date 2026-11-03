package com.orbit.wellness.ui.context

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.util.DateUtils
import java.time.ZoneId

data class ContextCategoryCardModel(
    val category: String,
    val title: String,
    val statusText: String,
    val interactive: Boolean,
    val comingNext: Boolean,
)

data class ContextOverviewModel(
    val missing: Boolean = false,
    val title: String = "",
    val detail: String? = null,
    val cards: List<ContextCategoryCardModel> = emptyList(),
)

object ContextOverviewCopy {
    const val SINCE_LAST_CHECK_IN = "Since your last check-in"
    const val SINCE_WAKING = "Since waking"
    const val SO_FAR_TODAY = "So far today"
    const val NOT_REVIEWED = "Not reviewed"
    const val NONE = "None"
    const val COMING_NEXT = "Coming next"
    const val MISSING = "This check-in could not be found."

    fun present(
        window: CheckInContextWindow?,
        categories: List<CheckInContextCategory>,
        activityItems: List<CheckInActivityItem>,
        workoutItems: List<CheckInWorkoutItem> = emptyList(),
        usageItems: List<CheckInUsageItem> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ContextOverviewModel {
        if (window == null) {
            return ContextOverviewModel(missing = true, title = MISSING)
        }
        val (title, detail) = heading(window.startBasis, window.windowStartAt, zone)
        val byCategory = categories.associateBy { it.category }
        val cards = CheckInContextCategories.ALL.map { category ->
            val status = byCategory[category]?.status ?: CheckInContextStatus.UNREVIEWED
            val interactive = category == CheckInContextCategories.ACTIVITY ||
                category == CheckInContextCategories.WORKOUT ||
                category == CheckInContextCategories.USAGE
            ContextCategoryCardModel(
                category = category,
                title = categoryTitle(category),
                statusText = when (category) {
                    CheckInContextCategories.ACTIVITY -> activityStatusText(
                        status,
                        activityItems.map { it.activityName },
                    )
                    CheckInContextCategories.WORKOUT -> workoutStatusText(
                        status,
                        workoutItems.map { it.workoutName to it.durationMinutes },
                    )
                    CheckInContextCategories.USAGE -> usageStatusText(status, usageItems)
                    else -> passiveStatusText(status)
                },
                interactive = interactive,
                comingNext = !interactive,
            )
        }
        return ContextOverviewModel(
            missing = false,
            title = title,
            detail = detail,
            cards = cards,
        )
    }

    fun heading(startBasis: String, windowStartAt: Long, zone: ZoneId): Pair<String, String?> {
        val time = DateUtils.formatTime(windowStartAt, zone)
        return when (startBasis) {
            CheckInContextStartBasis.PREVIOUS_CHECK_IN -> SINCE_LAST_CHECK_IN to time
            CheckInContextStartBasis.WAKE_TIME -> SINCE_WAKING to time
            else -> SO_FAR_TODAY to null
        }
    }

    fun activityStatusText(status: String, names: List<String>): String {
        return when (status) {
            CheckInContextStatus.NONE -> NONE
            CheckInContextStatus.ENTRIES -> when (names.size) {
                0 -> NOT_REVIEWED
                1 -> names.first()
                2 -> names.joinToString(", ")
                else -> "${names.size} activities"
            }
            else -> NOT_REVIEWED
        }
    }

    fun workoutStatusText(status: String, workouts: List<Pair<String, Int>>): String {
        return when (status) {
            CheckInContextStatus.NONE -> NONE
            CheckInContextStatus.ENTRIES -> when (workouts.size) {
                0 -> NOT_REVIEWED
                1 -> "${workouts.first().first} · ${workouts.first().second} min"
                else -> "${workouts.size} workouts · ${workouts.sumOf { it.second }} min"
            }
            else -> NOT_REVIEWED
        }
    }

    fun usageStatusText(status: String, items: List<CheckInUsageItem>): String {
        return when (status) {
            CheckInContextStatus.NONE -> NONE
            CheckInContextStatus.ENTRIES -> when (items.size) {
                0 -> NOT_REVIEWED
                1 -> {
                    val item = items.first()
                    if (item.amount.isBlank()) item.name else "${item.name} · ${item.amount}"
                }
                else -> {
                    val supportive = items.count { it.intent == SubstanceIntent.SUPPORTIVE.name }
                    val coping = items.count { it.intent == SubstanceIntent.COPING.name }
                    "${items.size} items · $supportive supportive · $coping coping"
                }
            }
            else -> NOT_REVIEWED
        }
    }

    fun passiveStatusText(status: String): String {
        return when (status) {
            CheckInContextStatus.NONE -> NONE
            CheckInContextStatus.ENTRIES -> "Saved"
            else -> NOT_REVIEWED
        }
    }

    fun categoryTitle(category: String): String {
        return when (category) {
            CheckInContextCategories.ACTIVITY -> "Activity"
            CheckInContextCategories.WORKOUT -> "Workout"
            CheckInContextCategories.DIET -> "Diet"
            CheckInContextCategories.USAGE -> "Usage"
            else -> category
        }
    }
}
