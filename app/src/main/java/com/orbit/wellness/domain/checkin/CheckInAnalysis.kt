package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.DefaultCheckInAxes

/**
 * In-memory projection of Room schema 22 check-ins.
 *
 * This is not the frozen ten-axis [com.orbit.wellness.domain.state.FeatureAxes]
 * space and it is not stored in Room. Missing axis values stay missing.
 * A raw value of 0 is a recorded zero. [AxisReadingV2.normalized] is `raw / 10.0`
 * for later arithmetic only: adjacent scores are not known to be equally far apart.
 * Anxiety is stored as observed, so a higher number means more anxiety.
 */
data class CheckInAnalysisDatasetV2(
    val states: List<StateObservationV2>,
    val intervals: List<ContextIntervalObservationV2>,
    val transitions: List<StateTransitionObservationV2>,
    val issues: List<AnalysisIssue>,
    val protocol: String = PROTOCOL,
    val sourceRoomSchema: Int = SOURCE_ROOM_SCHEMA,
) {
    /** Intervals whose overview save set [ContextIntervalObservationV2.completedAt]. */
    fun completedIntervals(): List<ContextIntervalObservationV2> =
        intervals.filter { it.isComplete }

    companion object {
        const val PROTOCOL = "check-in-analysis-v2"
        const val SOURCE_ROOM_SCHEMA = 22

        val CORE_AXIS_IDS: List<String> = listOf(
            DefaultCheckInAxes.MOOD,
            DefaultCheckInAxes.ENERGY,
            DefaultCheckInAxes.CONFIDENCE,
            DefaultCheckInAxes.ANXIETY,
        )
    }
}

data class AxisReadingV2(
    val axisId: String,
    val raw: Int,
    val normalized: Double,
)

/**
 * Core axes in a fixed order. A null slot was not recorded.
 * Custom axes are not copied here; they stay on [StateObservationV2.axes].
 */
data class CoreAxisViewV2(
    val mood: AxisReadingV2?,
    val energy: AxisReadingV2?,
    val confidence: AxisReadingV2?,
    val anxiety: AxisReadingV2?,
) {
    fun inOrder(): List<Pair<String, AxisReadingV2?>> = listOf(
        DefaultCheckInAxes.MOOD to mood,
        DefaultCheckInAxes.ENERGY to energy,
        DefaultCheckInAxes.CONFIDENCE to confidence,
        DefaultCheckInAxes.ANXIETY to anxiety,
    )
}

data class StateObservationV2(
    val checkInId: String,
    val recordedAt: Long,
    val localDate: String,
    /** Explicit readings only, keyed by stable axis id. Absence means missing. */
    val axes: Map<String, AxisReadingV2>,
    val core: CoreAxisViewV2,
    /**
     * Axes with disagreeing duplicate values. They are omitted from [axes]
     * and are not counted as ordinary missingness.
     */
    val conflictingAxisIds: List<String> = emptyList(),
    /** Raw check-in metadata. Not an axis and not embedded. */
    val trigger: String = "",
    /** Raw note. Empty stays empty. */
    val note: String = "",
    /**
     * Emotion snapshot from [com.orbit.wellness.data.local.CheckInRecord].
     * Empty stays empty. It is not mapped to a number.
     */
    val emotion: String = "",
) {
    val observedAxisCount: Int
        get() = axes.size

    val hasObservedAxes: Boolean
        get() = axes.isNotEmpty()

    /** How many of the four core axes have an explicit reading. */
    val coreAxisCount: Int
        get() = core.inOrder().count { it.second != null }
}

enum class CategoryObservationKindV2 {
    /** Stored `none`: the category was observed and empty. */
    NONE,
    /** Stored `entries`. */
    ENTRIES,
    /** Stored `unreviewed`. Not rewritten to none. */
    UNREVIEWED,
    /** The expected category row was not in the input. */
    ABSENT,
    /** More than one distinct row existed for this category. */
    CONFLICTING,
}

data class ActivityObservationV2(
    val id: String,
    val optionId: String?,
    val name: String,
    val reportedAt: Long,
)

data class WorkoutObservationV2(
    val id: String,
    val optionId: String?,
    val name: String,
    val durationMinutes: Int,
    val reportedAt: Long,
)

data class FoodObservationV2(
    val id: String,
    val optionId: String?,
    val name: String,
    val sortOrder: Int,
)

data class MealObservationV2(
    val id: String,
    val size: String,
    val sortOrder: Int,
    val reportedAt: Long,
    val foods: List<FoodObservationV2>,
)

/** [amountText] is the stored phrase. It is not parsed into a number or dose. */
data class UsageObservationV2(
    val id: String,
    val optionId: String?,
    val name: String,
    val intent: String,
    val amountText: String,
    val reportedAt: Long,
)

sealed interface ContextCategoryObservationV2 {
    val category: String
    val kind: CategoryObservationKindV2
    val reviewedAt: Long?
}

data class ActivityCategoryObservationV2(
    override val kind: CategoryObservationKindV2,
    override val reviewedAt: Long?,
    val activities: List<ActivityObservationV2>,
) : ContextCategoryObservationV2 {
    override val category: String = CheckInContextCategories.ACTIVITY
}

data class WorkoutCategoryObservationV2(
    override val kind: CategoryObservationKindV2,
    override val reviewedAt: Long?,
    val workouts: List<WorkoutObservationV2>,
) : ContextCategoryObservationV2 {
    override val category: String = CheckInContextCategories.WORKOUT
}

data class DietCategoryObservationV2(
    override val kind: CategoryObservationKindV2,
    override val reviewedAt: Long?,
    val meals: List<MealObservationV2>,
) : ContextCategoryObservationV2 {
    override val category: String = CheckInContextCategories.DIET
}

data class UsageCategoryObservationV2(
    override val kind: CategoryObservationKindV2,
    override val reviewedAt: Long?,
    val usage: List<UsageObservationV2>,
) : ContextCategoryObservationV2 {
    override val category: String = CheckInContextCategories.USAGE
}

data class ContextIntervalObservationV2(
    val endingCheckInId: String,
    val previousCheckInId: String?,
    val windowStartAt: Long,
    val windowEndAt: Long,
    val durationMillis: Long,
    val startBasis: String,
    val completedAt: Long?,
    val activity: ActivityCategoryObservationV2,
    val workout: WorkoutCategoryObservationV2,
    val diet: DietCategoryObservationV2,
    val usage: UsageCategoryObservationV2,
) {
    val isComplete: Boolean
        get() = completedAt != null

    fun categoriesInOrder(): List<ContextCategoryObservationV2> = listOf(
        activity,
        workout,
        diet,
        usage,
    )
}

/**
 * Change in recorded integers from the previous check-in to the ending one.
 * [deltaRaw] is ending minus previous. It is not a distance, and anxiety is not inverted.
 */
data class AxisDeltaV2(
    val axisId: String,
    val previousRaw: Int,
    val endingRaw: Int,
    val deltaRaw: Int,
    val deltaNormalized: Double,
)

data class CoreAxisDeltaViewV2(
    val mood: AxisDeltaV2?,
    val energy: AxisDeltaV2?,
    val confidence: AxisDeltaV2?,
    val anxiety: AxisDeltaV2?,
)

/**
 * A resolved previous check-in. [deltas] is empty when the link is real but no
 * axis was recorded at both ends. That link is not a measured change.
 */
data class StateTransitionObservationV2(
    val previous: StateObservationV2,
    val ending: StateObservationV2,
    val interval: ContextIntervalObservationV2,
    /** Axes recorded at both endpoints. An axis missing on either side is absent. */
    val deltas: Map<String, AxisDeltaV2>,
    val coreDeltas: CoreAxisDeltaViewV2,
) {
    val sharedAxisIds: List<String>
        get() = deltas.keys.sorted()

    val measuredAxisCount: Int
        get() = deltas.size

    val hasMeasuredDelta: Boolean
        get() = deltas.isNotEmpty()
}

enum class AnalysisIssueCode {
    DUPLICATE_AXIS_VALUE,
    DUPLICATE_CHECK_IN,
    DUPLICATE_CONTEXT_WINDOW,
    DUPLICATE_CATEGORY_ROW,
    DUPLICATE_CHILD_ROW,
    MISSING_PREVIOUS_CHECK_IN,
    PREVIOUS_CHECK_IN_IS_NOT_EARLIER,
    WINDOW_END_DISAGREES_WITH_CHECK_IN,
    MISSING_CATEGORY_ROW,
    CHECK_IN_WITHOUT_CONTEXT_WINDOW,
    CHILD_WITHOUT_PARENT,
    CATEGORY_STATUS_DISAGREES_WITH_ITEMS,
    COMPLETED_INTERVAL_HAS_UNREVIEWED,
}

data class AnalysisIssue(
    val code: AnalysisIssueCode,
    val checkInId: String? = null,
    val relatedId: String? = null,
    val detail: String,
)
