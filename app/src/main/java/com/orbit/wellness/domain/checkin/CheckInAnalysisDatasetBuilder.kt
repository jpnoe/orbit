package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.DefaultCheckInAxes
import kotlin.comparisons.nullsLast

/**
 * Projects schema 22 check-in entities into [CheckInAnalysisDatasetV2].
 *
 * The input collections are the records. This function does not read Room,
 * impute missing scores, or repair disagreeing rows. Entity constructors
 * already reject an axis value outside 0–10 and a window whose start is after
 * its end, so those cases do not arrive here.
 */
object CheckInAnalysisDatasetBuilder {
    fun build(
        checkIns: List<CheckInRecord> = emptyList(),
        axisValues: List<CheckInAxisValue> = emptyList(),
        windows: List<CheckInContextWindow> = emptyList(),
        categories: List<CheckInContextCategory> = emptyList(),
        activities: List<CheckInActivityItem> = emptyList(),
        workouts: List<CheckInWorkoutItem> = emptyList(),
        meals: List<CheckInMeal> = emptyList(),
        foods: List<CheckInMealFoodItem> = emptyList(),
        usage: List<CheckInUsageItem> = emptyList(),
    ): CheckInAnalysisDatasetV2 {
        val issues = mutableListOf<AnalysisIssue>()
        val knownCheckInIds = checkIns.map { it.id }.toSet()
        val droppedCheckInIds = mutableSetOf<String>()
        val keptCheckIns = mutableListOf<CheckInRecord>()

        checkIns.groupBy { it.id }.toSortedMap().forEach { (id, group) ->
            val distinct = group.distinct()
            if (distinct.size > 1) {
                droppedCheckInIds += id
                issues += issue(
                    AnalysisIssueCode.DUPLICATE_CHECK_IN,
                    checkInId = id,
                    detail = childCounts(
                        id,
                        "Check-in $id has ${distinct.size} distinct records and was not projected",
                        categories, activities, workouts, meals, foods, usage,
                    ) + "; axisValues=${axisValues.count { it.checkInId == id }}",
                )
            } else {
                if (group.size > 1) {
                    issues += issue(
                        AnalysisIssueCode.DUPLICATE_CHECK_IN,
                        checkInId = id,
                        detail = "Check-in $id is repeated ${group.size} times",
                    )
                }
                keptCheckIns += distinct.single()
            }
        }

        val orderedCheckIns = keptCheckIns.sortedWith(compareBy({ it.recordedAt }, { it.id }))
        val states = orderedCheckIns.map { checkIn ->
            stateOf(checkIn, axisValues.filter { it.checkInId == checkIn.id }, issues)
        }
        val stateById = states.associateBy { it.checkInId }

        axisValues
            .filter { it.checkInId !in knownCheckInIds }
            .sortedWith(compareBy({ it.checkInId }, { it.axisId }, { it.value }))
            .forEach { value ->
                issues += issue(
                    AnalysisIssueCode.CHILD_WITHOUT_PARENT,
                    checkInId = value.checkInId,
                    relatedId = value.axisId,
                    detail = "Axis ${value.axisId} value ${value.value} has no check-in",
                )
            }

        val windowsByCheckIn = windows.groupBy { it.checkInId }
        windowsByCheckIn.toSortedMap().forEach { (checkInId, group) ->
            if (checkInId in knownCheckInIds) return@forEach
            issues += issue(
                AnalysisIssueCode.CHILD_WITHOUT_PARENT,
                checkInId = checkInId,
                detail = childCounts(
                    checkInId,
                    "Context window has no check-in (${group.distinct().size} distinct window rows)",
                    categories, activities, workouts, meals, foods, usage,
                ),
            )
        }

        fun projectionOf(checkInId: String): Projection {
            if (checkInId in droppedCheckInIds) return Projection.AMBIGUOUS
            val distinctWindows = windowsByCheckIn[checkInId].orEmpty().distinct()
            return when {
                checkInId !in knownCheckInIds && distinctWindows.isNotEmpty() -> Projection.ORPHAN_WINDOW
                distinctWindows.size > 1 -> Projection.AMBIGUOUS
                distinctWindows.size == 1 && checkInId in knownCheckInIds -> Projection.PROJECTED
                else -> Projection.NO_WINDOW
            }
        }

        val keptActivities = keepRows(activities, { it.id }, { it.checkInId }, "Activity", issues)
        val keptWorkouts = keepRows(workouts, { it.id }, { it.checkInId }, "Workout", issues)
        val keptMeals = keepRows(meals, { it.id }, { it.checkInId }, "Meal", issues)
        val keptUsage = keepRows(usage, { it.id }, { it.checkInId }, "Usage", issues)
        val keptFoods = keepRows(foods, { it.id }, { null }, "Food", issues)
        val rawMealIds = meals.map { it.id }.toSet()
        val keptMealsById = keptMeals.associateBy { it.id }

        fun flagUnprojected(checkInId: String, relatedId: String, detail: String) {
            if (projectionOf(checkInId) == Projection.NO_WINDOW) {
                issues += issue(
                    AnalysisIssueCode.CHILD_WITHOUT_PARENT,
                    checkInId = checkInId,
                    relatedId = relatedId,
                    detail = detail,
                )
            }
        }

        val activitiesByCheckIn = bucket(keptActivities, { it.checkInId }, ::projectionOf) { item ->
            flagUnprojected(item.checkInId, item.id, "Activity ${item.id} has no context window")
        }
        val workoutsByCheckIn = bucket(keptWorkouts, { it.checkInId }, ::projectionOf) { item ->
            flagUnprojected(item.checkInId, item.id, "Workout ${item.id} has no context window")
        }
        val mealsByCheckIn = bucket(keptMeals, { it.checkInId }, ::projectionOf) { item ->
            flagUnprojected(item.checkInId, item.id, "Meal ${item.id} has no context window")
        }
        val usageByCheckIn = bucket(keptUsage, { it.checkInId }, ::projectionOf) { item ->
            flagUnprojected(item.checkInId, item.id, "Usage ${item.id} has no context window")
        }

        val foodsByMeal = mutableMapOf<String, MutableList<CheckInMealFoodItem>>()
        keptFoods.sortedWith(compareBy({ it.mealId }, { it.sortOrder }, { it.id })).forEach { food ->
            when {
                food.mealId !in rawMealIds -> {
                    issues += issue(
                        AnalysisIssueCode.CHILD_WITHOUT_PARENT,
                        relatedId = food.id,
                        detail = "Food ${food.id} has no meal",
                    )
                }
                food.mealId !in keptMealsById -> Unit
                else -> {
                    val meal = keptMealsById.getValue(food.mealId)
                    if (projectionOf(meal.checkInId) == Projection.PROJECTED) {
                        foodsByMeal.getOrPut(meal.id) { mutableListOf() }.add(food)
                    }
                }
            }
        }

        categories
            .groupBy { it.checkInId to it.category }
            .toSortedMap(compareBy<Pair<String, String>>({ it.first }, { it.second }))
            .forEach { (key, group) ->
                val (checkInId, category) = key
                val projection = projectionOf(checkInId)
                if (projection != Projection.PROJECTED && group.size > 1) {
                    issues += issue(
                        AnalysisIssueCode.DUPLICATE_CATEGORY_ROW,
                        checkInId = checkInId,
                        relatedId = category,
                        detail = "Category $category is repeated ${group.size} times and was not projected",
                    )
                }
                if (projection == Projection.NO_WINDOW) {
                    issues += issue(
                        AnalysisIssueCode.CHILD_WITHOUT_PARENT,
                        checkInId = checkInId,
                        relatedId = category,
                        detail = "Category $category has no context window",
                    )
                }
            }

        val intervals = orderedCheckIns.mapNotNull { checkIn ->
            val group = windowsByCheckIn[checkIn.id].orEmpty()
            val distinct = group.distinct()
            when {
                distinct.isEmpty() -> {
                    issues += issue(
                        AnalysisIssueCode.CHECK_IN_WITHOUT_CONTEXT_WINDOW,
                        checkInId = checkIn.id,
                        detail = "Check-in ${checkIn.id} has no context window",
                    )
                    null
                }
                distinct.size > 1 -> {
                    issues += issue(
                        AnalysisIssueCode.DUPLICATE_CONTEXT_WINDOW,
                        checkInId = checkIn.id,
                        detail = childCounts(
                            checkIn.id,
                            "Check-in ${checkIn.id} has ${distinct.size} distinct context windows",
                            categories, activities, workouts, meals, foods, usage,
                        ),
                    )
                    null
                }
                else -> {
                    if (group.size > 1) {
                        issues += issue(
                            AnalysisIssueCode.DUPLICATE_CONTEXT_WINDOW,
                            checkInId = checkIn.id,
                            detail = "Context window for ${checkIn.id} is repeated ${group.size} times",
                        )
                    }
                    intervalOf(
                        checkIn = checkIn,
                        window = distinct.single(),
                        categories = categories.filter { it.checkInId == checkIn.id },
                        activities = activitiesByCheckIn[checkIn.id].orEmpty(),
                        workouts = workoutsByCheckIn[checkIn.id].orEmpty(),
                        meals = mealsByCheckIn[checkIn.id].orEmpty(),
                        foodsByMeal = foodsByMeal,
                        usage = usageByCheckIn[checkIn.id].orEmpty(),
                        rawItemCounts = RawItemCounts(
                            activities = activities.count { it.checkInId == checkIn.id },
                            workouts = workouts.count { it.checkInId == checkIn.id },
                            meals = meals.count { it.checkInId == checkIn.id },
                            usage = usage.count { it.checkInId == checkIn.id },
                        ),
                        knownStateIds = stateById.keys,
                        issues = issues,
                    )
                }
            }
        }

        val transitions = intervals.mapNotNull { interval ->
            val previousId = interval.previousCheckInId ?: return@mapNotNull null
            val previous = stateById[previousId] ?: return@mapNotNull null
            val ending = stateById.getValue(interval.endingCheckInId)
            if (previous.recordedAt >= ending.recordedAt) {
                issues += issue(
                    AnalysisIssueCode.PREVIOUS_CHECK_IN_IS_NOT_EARLIER,
                    checkInId = ending.checkInId,
                    relatedId = previous.checkInId,
                    detail = "Previous check-in ${previous.checkInId} is recorded at " +
                        "${previous.recordedAt}, which is not earlier than ${ending.recordedAt}",
                )
            }
            val deltas = linkedMapOf<String, AxisDeltaV2>()
            previous.axes.keys.intersect(ending.axes.keys).sorted().forEach { axisId ->
                val from = previous.axes.getValue(axisId)
                val to = ending.axes.getValue(axisId)
                val deltaRaw = to.raw - from.raw
                deltas[axisId] = AxisDeltaV2(
                    axisId = axisId,
                    previousRaw = from.raw,
                    endingRaw = to.raw,
                    deltaRaw = deltaRaw,
                    deltaNormalized = deltaRaw / 10.0,
                )
            }
            StateTransitionObservationV2(
                previous = previous,
                ending = ending,
                interval = interval,
                deltas = deltas,
                coreDeltas = CoreAxisDeltaViewV2(
                    mood = deltas[DefaultCheckInAxes.MOOD],
                    energy = deltas[DefaultCheckInAxes.ENERGY],
                    confidence = deltas[DefaultCheckInAxes.CONFIDENCE],
                    anxiety = deltas[DefaultCheckInAxes.ANXIETY],
                ),
            )
        }

        return CheckInAnalysisDatasetV2(
            states = states,
            intervals = intervals,
            transitions = transitions,
            issues = issues.sortedWith(ISSUE_ORDER),
        )
    }
}

private enum class Projection {
    PROJECTED,
    NO_WINDOW,
    AMBIGUOUS,
    ORPHAN_WINDOW,
}

private data class RawItemCounts(
    val activities: Int,
    val workouts: Int,
    val meals: Int,
    val usage: Int,
)

private data class CategoryHeader(
    val kind: CategoryObservationKindV2,
    val reviewedAt: Long?,
)

private fun stateOf(
    checkIn: CheckInRecord,
    rows: List<CheckInAxisValue>,
    issues: MutableList<AnalysisIssue>,
): StateObservationV2 {
    val readings = sortedMapOf<String, AxisReadingV2>()
    val conflicting = mutableListOf<String>()
    rows.groupBy { it.axisId }.toSortedMap().forEach { (axisId, axisRows) ->
        val values = axisRows.map { it.value }.sorted()
        if (axisRows.size > 1) {
            issues += issue(
                AnalysisIssueCode.DUPLICATE_AXIS_VALUE,
                checkInId = checkIn.id,
                relatedId = axisId,
                detail = "Axis $axisId values: ${values.joinToString(", ")}",
            )
        }
        if (values.distinct().size == 1) {
            val raw = values.first()
            readings[axisId] = AxisReadingV2(
                axisId = axisId,
                raw = raw,
                normalized = raw / 10.0,
            )
        } else {
            conflicting += axisId
        }
    }
    return StateObservationV2(
        checkInId = checkIn.id,
        recordedAt = checkIn.recordedAt,
        localDate = checkIn.localDate,
        axes = readings,
        core = CoreAxisViewV2(
            mood = readings[DefaultCheckInAxes.MOOD],
            energy = readings[DefaultCheckInAxes.ENERGY],
            confidence = readings[DefaultCheckInAxes.CONFIDENCE],
            anxiety = readings[DefaultCheckInAxes.ANXIETY],
        ),
        conflictingAxisIds = conflicting,
        trigger = checkIn.trigger,
        note = checkIn.note,
        emotion = checkIn.emotion,
    )
}

private fun intervalOf(
    checkIn: CheckInRecord,
    window: CheckInContextWindow,
    categories: List<CheckInContextCategory>,
    activities: List<CheckInActivityItem>,
    workouts: List<CheckInWorkoutItem>,
    meals: List<CheckInMeal>,
    foodsByMeal: Map<String, List<CheckInMealFoodItem>>,
    usage: List<CheckInUsageItem>,
    rawItemCounts: RawItemCounts,
    knownStateIds: Set<String>,
    issues: MutableList<AnalysisIssue>,
): ContextIntervalObservationV2 {
    if (window.windowEndAt != checkIn.recordedAt) {
        issues += issue(
            AnalysisIssueCode.WINDOW_END_DISAGREES_WITH_CHECK_IN,
            checkInId = checkIn.id,
            detail = "Window ends at ${window.windowEndAt} and check-in ${checkIn.id} " +
                "is recorded at ${checkIn.recordedAt}",
        )
    }
    val previousId = window.previousCheckInId
    if (previousId != null && previousId !in knownStateIds) {
        issues += issue(
            AnalysisIssueCode.MISSING_PREVIOUS_CHECK_IN,
            checkInId = checkIn.id,
            relatedId = previousId,
            detail = "previousCheckInId $previousId does not resolve to a check-in",
        )
    }
    fun header(category: String, itemCount: Int): CategoryHeader {
        val rows = categories.filter { it.category == category }
        if (rows.isEmpty()) {
            issues += issue(
                AnalysisIssueCode.MISSING_CATEGORY_ROW,
                checkInId = checkIn.id,
                relatedId = category,
                detail = "Check-in ${checkIn.id} has no $category category row",
            )
            return CategoryHeader(CategoryObservationKindV2.ABSENT, null)
        }
        val distinct = rows.distinct()
        if (distinct.size > 1) {
            val statuses = distinct.map { it.status }.sorted().joinToString(", ")
            issues += issue(
                AnalysisIssueCode.DUPLICATE_CATEGORY_ROW,
                checkInId = checkIn.id,
                relatedId = category,
                detail = "Category $category has distinct statuses: $statuses",
            )
            return CategoryHeader(CategoryObservationKindV2.CONFLICTING, null)
        }
        if (rows.size > 1) {
            issues += issue(
                AnalysisIssueCode.DUPLICATE_CATEGORY_ROW,
                checkInId = checkIn.id,
                relatedId = category,
                detail = "Category $category is repeated ${rows.size} times",
            )
        }
        val row = distinct.single()
        val kind = when (row.status) {
            CheckInContextStatus.NONE -> CategoryObservationKindV2.NONE
            CheckInContextStatus.ENTRIES -> CategoryObservationKindV2.ENTRIES
            CheckInContextStatus.UNREVIEWED -> CategoryObservationKindV2.UNREVIEWED
            else -> error("Unknown context status: ${row.status}")
        }
        val disagrees = when (kind) {
            CategoryObservationKindV2.NONE -> itemCount > 0
            CategoryObservationKindV2.ENTRIES -> itemCount == 0
            CategoryObservationKindV2.UNREVIEWED -> itemCount > 0
            else -> false
        }
        if (disagrees) {
            issues += issue(
                AnalysisIssueCode.CATEGORY_STATUS_DISAGREES_WITH_ITEMS,
                checkInId = checkIn.id,
                relatedId = category,
                detail = "Category $category is ${row.status} and has $itemCount item row(s)",
            )
        }
        if (kind == CategoryObservationKindV2.UNREVIEWED && window.completedAt != null) {
            issues += issue(
                AnalysisIssueCode.COMPLETED_INTERVAL_HAS_UNREVIEWED,
                checkInId = checkIn.id,
                relatedId = category,
                detail = "Completed check-in ${checkIn.id} still has unreviewed $category",
            )
        }
        return CategoryHeader(kind, row.reviewedAt)
    }

    val activityHeader = header(CheckInContextCategories.ACTIVITY, rawItemCounts.activities)
    val workoutHeader = header(CheckInContextCategories.WORKOUT, rawItemCounts.workouts)
    val dietHeader = header(CheckInContextCategories.DIET, rawItemCounts.meals)
    val usageHeader = header(CheckInContextCategories.USAGE, rawItemCounts.usage)
    return ContextIntervalObservationV2(
        endingCheckInId = checkIn.id,
        previousCheckInId = previousId,
        windowStartAt = window.windowStartAt,
        windowEndAt = window.windowEndAt,
        durationMillis = window.windowEndAt - window.windowStartAt,
        startBasis = window.startBasis,
        completedAt = window.completedAt,
        activity = ActivityCategoryObservationV2(
            kind = activityHeader.kind,
            reviewedAt = activityHeader.reviewedAt,
            activities = activities
                .sortedWith(compareBy({ it.reportedAt }, { it.id }))
                .map { item ->
                    ActivityObservationV2(
                        id = item.id,
                        optionId = item.activityOptionId,
                        name = item.activityName,
                        reportedAt = item.reportedAt,
                    )
                },
        ),
        workout = WorkoutCategoryObservationV2(
            kind = workoutHeader.kind,
            reviewedAt = workoutHeader.reviewedAt,
            workouts = workouts
                .sortedWith(compareBy({ it.reportedAt }, { it.id }))
                .map { item ->
                    WorkoutObservationV2(
                        id = item.id,
                        optionId = item.workoutOptionId,
                        name = item.workoutName,
                        durationMinutes = item.durationMinutes,
                        reportedAt = item.reportedAt,
                    )
                },
        ),
        diet = DietCategoryObservationV2(
            kind = dietHeader.kind,
            reviewedAt = dietHeader.reviewedAt,
            meals = meals
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { meal ->
                    MealObservationV2(
                        id = meal.id,
                        size = meal.size,
                        sortOrder = meal.sortOrder,
                        reportedAt = meal.reportedAt,
                        foods = foodsByMeal[meal.id].orEmpty()
                            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                            .map { food ->
                                FoodObservationV2(
                                    id = food.id,
                                    optionId = food.foodOptionId,
                                    name = food.foodName,
                                    sortOrder = food.sortOrder,
                                )
                            },
                    )
                },
        ),
        usage = UsageCategoryObservationV2(
            kind = usageHeader.kind,
            reviewedAt = usageHeader.reviewedAt,
            usage = usage
                .sortedWith(compareBy({ it.reportedAt }, { it.id }))
                .map { item ->
                    UsageObservationV2(
                        id = item.id,
                        optionId = item.substanceOptionId,
                        name = item.name,
                        intent = item.intent,
                        amountText = item.amount,
                        reportedAt = item.reportedAt,
                    )
                },
        ),
    )
}

private fun <T> keepRows(
    rows: List<T>,
    id: (T) -> String,
    checkInId: (T) -> String?,
    label: String,
    issues: MutableList<AnalysisIssue>,
): List<T> {
    val kept = mutableListOf<T>()
    rows.groupBy(id).toSortedMap().forEach { (rowId, group) ->
        val distinct = group.distinct()
        if (group.size > 1) {
            val copies = if (distinct.size > 1) {
                " with ${distinct.size} distinct copies"
            } else {
                ""
            }
            issues += issue(
                AnalysisIssueCode.DUPLICATE_CHILD_ROW,
                checkInId = checkInId(group.first()),
                relatedId = rowId,
                detail = "$label $rowId is repeated ${group.size} times$copies",
            )
        }
        if (distinct.size == 1) kept += distinct.single()
    }
    return kept
}

private fun <T> bucket(
    rows: List<T>,
    checkInId: (T) -> String,
    projectionOf: (String) -> Projection,
    onUnprojected: (T) -> Unit,
): Map<String, List<T>> {
    val projected = mutableMapOf<String, MutableList<T>>()
    rows.forEach { row ->
        if (projectionOf(checkInId(row)) == Projection.PROJECTED) {
            projected.getOrPut(checkInId(row)) { mutableListOf() }.add(row)
        } else {
            onUnprojected(row)
        }
    }
    return projected
}

private fun childCounts(
    checkInId: String,
    lead: String,
    categories: List<CheckInContextCategory>,
    activities: List<CheckInActivityItem>,
    workouts: List<CheckInWorkoutItem>,
    meals: List<CheckInMeal>,
    foods: List<CheckInMealFoodItem>,
    usage: List<CheckInUsageItem>,
): String {
    val mealIds = meals.filter { it.checkInId == checkInId }.map { it.id }.toSet()
    return "$lead; categories=${categories.count { it.checkInId == checkInId }}" +
        " activities=${activities.count { it.checkInId == checkInId }}" +
        " workouts=${workouts.count { it.checkInId == checkInId }}" +
        " meals=${mealIds.size}" +
        " foods=${foods.count { it.mealId in mealIds }}" +
        " usage=${usage.count { it.checkInId == checkInId }}"
}

private fun issue(
    code: AnalysisIssueCode,
    checkInId: String? = null,
    relatedId: String? = null,
    detail: String,
): AnalysisIssue = AnalysisIssue(
    code = code,
    checkInId = checkInId,
    relatedId = relatedId,
    detail = detail,
)

private val ISSUE_ORDER = compareBy<AnalysisIssue> { it.code.name }
    .thenBy(nullsLast()) { it.checkInId }
    .thenBy(nullsLast()) { it.relatedId }
    .thenBy { it.detail }
