package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxis
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInDao
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.CheckInWorkoutItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class InMemoryCheckInDao : CheckInDao {
    var inserted: CheckInRecord? = null
        private set

    private val checkIns = linkedMapOf<String, CheckInRecord>()
    private val values = mutableListOf<CheckInAxisValue>()
    private val windows = MutableStateFlow<Map<String, CheckInContextWindow>>(emptyMap())
    private val categories = MutableStateFlow<List<CheckInContextCategory>>(emptyList())
    private val activityItems = MutableStateFlow<List<CheckInActivityItem>>(emptyList())
    private val workoutItems = MutableStateFlow<List<CheckInWorkoutItem>>(emptyList())
    private val usageItems = MutableStateFlow<List<CheckInUsageItem>>(emptyList())
    private val meals = MutableStateFlow<List<CheckInMeal>>(emptyList())
    private val mealFoods = MutableStateFlow<List<CheckInMealFoodItem>>(emptyList())

    override fun observeEnabledAxes(): Flow<List<CheckInAxis>> = MutableStateFlow(emptyList())

    override fun observeRecent(limit: Int): Flow<List<CheckInRecord>> =
        MutableStateFlow(checkIns.values.sortedByDescending { it.recordedAt }.take(limit))

    override suspend fun getValuesForCheckIn(checkInId: String): List<CheckInAxisValue> =
        values.filter { it.checkInId == checkInId }

    override fun observeAxisValues(): Flow<List<CheckInAxisValue>> = MutableStateFlow(values.toList())

    override suspend fun getLatest(): CheckInRecord? =
        checkIns.values.maxByOrNull { it.recordedAt }

    override suspend fun getCheckInsOnDate(localDate: String): List<CheckInRecord> =
        checkIns.values
            .filter { it.localDate == localDate }
            .sortedWith(compareBy<CheckInRecord> { it.recordedAt }.thenBy { it.id })

    override suspend fun insertAxes(axes: List<CheckInAxis>) = Unit

    override suspend fun insertCheckIn(record: CheckInRecord) {
        checkIns[record.id] = record
        inserted = record
    }

    override suspend fun deleteCheckIn(checkInId: String) {
        checkIns.remove(checkInId)
        values.removeAll { it.checkInId == checkInId }
        windows.value = windows.value - checkInId
        categories.value = categories.value.filterNot { it.checkInId == checkInId }
        activityItems.value = activityItems.value.filterNot { it.checkInId == checkInId }
        workoutItems.value = workoutItems.value.filterNot { it.checkInId == checkInId }
        usageItems.value = usageItems.value.filterNot { it.checkInId == checkInId }
        deleteMealsForCheckIn(checkInId)
        if (inserted?.id == checkInId) inserted = null
    }

    override suspend fun insertAxisValues(values: List<CheckInAxisValue>) {
        this.values += values
    }

    override suspend fun insertContextWindow(window: CheckInContextWindow) {
        windows.value = windows.value + (window.checkInId to window)
    }

    override suspend fun insertContextCategories(categories: List<CheckInContextCategory>) {
        this.categories.value = this.categories.value + categories
    }

    override fun observeContextWindow(checkInId: String): Flow<CheckInContextWindow?> =
        windows.map { it[checkInId] }

    override suspend fun getContextWindow(checkInId: String): CheckInContextWindow? =
        windows.value[checkInId]

    override fun observeContextCategories(checkInId: String): Flow<List<CheckInContextCategory>> =
        categories.map { rows -> rows.filter { it.checkInId == checkInId } }

    override suspend fun getContextCategories(checkInId: String): List<CheckInContextCategory> =
        categories.value.filter { it.checkInId == checkInId }

    override fun observeActivityItems(checkInId: String): Flow<List<CheckInActivityItem>> =
        activityItems.map { rows ->
            rows.filter { it.checkInId == checkInId }
                .sortedWith(compareBy<CheckInActivityItem> { it.activityName }.thenBy { it.id })
        }

    override suspend fun getActivityItems(checkInId: String): List<CheckInActivityItem> =
        activityItems.value
            .filter { it.checkInId == checkInId }
            .sortedWith(compareBy<CheckInActivityItem> { it.activityName }.thenBy { it.id })

    override suspend fun deleteActivityItemsForCheckIn(checkInId: String) {
        activityItems.value = activityItems.value.filterNot { it.checkInId == checkInId }
    }

    override suspend fun insertActivityItems(items: List<CheckInActivityItem>) {
        activityItems.value = activityItems.value + items
    }

    override suspend fun setCompletedAtIfNull(checkInId: String, completedAt: Long): Int {
        val current = windows.value[checkInId] ?: return 0
        if (current.completedAt != null) return 0
        windows.value = windows.value + (checkInId to current.copy(completedAt = completedAt))
        return 1
    }

    fun snapshot(): CheckInDaoSnapshot = CheckInDaoSnapshot(
        checkIns = checkIns.toMap(),
        values = values.toList(),
        windows = windows.value,
        categories = categories.value,
        activityItems = activityItems.value,
        workoutItems = workoutItems.value,
        usageItems = usageItems.value,
        meals = meals.value,
        mealFoods = mealFoods.value,
        inserted = inserted,
    )

    fun restore(snapshot: CheckInDaoSnapshot) {
        checkIns.clear()
        checkIns.putAll(snapshot.checkIns)
        values.clear()
        values += snapshot.values
        windows.value = snapshot.windows
        categories.value = snapshot.categories
        activityItems.value = snapshot.activityItems
        workoutItems.value = snapshot.workoutItems
        usageItems.value = snapshot.usageItems
        meals.value = snapshot.meals
        mealFoods.value = snapshot.mealFoods
        inserted = snapshot.inserted
    }

    override suspend fun updateCategoryStatus(
        checkInId: String,
        category: String,
        status: String,
        reviewedAt: Long,
    ) {
        categories.value = categories.value.map { row ->
            if (row.checkInId == checkInId && row.category == category) {
                row.copy(status = status, reviewedAt = reviewedAt)
            } else {
                row
            }
        }
    }

    override fun observeWorkoutItems(checkInId: String): Flow<List<CheckInWorkoutItem>> =
        workoutItems.map { rows -> rows.filter { it.checkInId == checkInId } }

    override suspend fun getWorkoutItems(checkInId: String): List<CheckInWorkoutItem> =
        workoutItems.value.filter { it.checkInId == checkInId }

    override suspend fun deleteWorkoutItemsForCheckIn(checkInId: String) {
        workoutItems.value = workoutItems.value.filterNot { it.checkInId == checkInId }
    }

    override suspend fun insertWorkoutItems(items: List<CheckInWorkoutItem>) {
        workoutItems.value = workoutItems.value + items
    }

    override fun observeUsageItems(checkInId: String): Flow<List<CheckInUsageItem>> =
        usageItems.map { rows -> rows.filter { it.checkInId == checkInId } }

    override suspend fun getUsageItems(checkInId: String): List<CheckInUsageItem> =
        usageItems.value.filter { it.checkInId == checkInId }

    override suspend fun deleteUsageItemsForCheckIn(checkInId: String) {
        usageItems.value = usageItems.value.filterNot { it.checkInId == checkInId }
    }

    override suspend fun insertUsageItems(items: List<CheckInUsageItem>) {
        usageItems.value = usageItems.value + items
    }

    override fun observeMeals(checkInId: String): Flow<List<CheckInMeal>> =
        meals.map { rows -> rows.forCheckIn(checkInId) }

    override suspend fun getMeals(checkInId: String): List<CheckInMeal> =
        meals.value.forCheckIn(checkInId)

    override fun observeMealFoods(checkInId: String): Flow<List<CheckInMealFoodItem>> =
        combine(meals, mealFoods) { _, _ -> foodsFor(checkInId) }

    override suspend fun getMealFoods(checkInId: String): List<CheckInMealFoodItem> =
        foodsFor(checkInId)

    override suspend fun deleteMealsForCheckIn(checkInId: String) {
        val mealIds = meals.value.filter { it.checkInId == checkInId }.map { it.id }.toSet()
        meals.value = meals.value.filterNot { it.checkInId == checkInId }
        mealFoods.value = mealFoods.value.filterNot { it.mealId in mealIds }
    }

    override suspend fun insertMeals(meals: List<CheckInMeal>) {
        this.meals.value = this.meals.value + meals
    }

    override suspend fun insertMealFoods(foods: List<CheckInMealFoodItem>) {
        mealFoods.value = mealFoods.value + foods
    }

    override fun observeCheckInsBetween(startMillis: Long, endMillis: Long): Flow<List<CheckInRecord>> =
        MutableStateFlow(
            checkIns.values
                .filter { it.recordedAt in startMillis..endMillis }
                .sortedWith(compareByDescending<CheckInRecord> { it.recordedAt }.thenByDescending { it.id }),
        )

    override fun observeContextWindows(): Flow<List<CheckInContextWindow>> = windows.map { it.values.toList() }

    override fun observeContextCategories(): Flow<List<CheckInContextCategory>> = categories

    override fun observeActivityItems(): Flow<List<CheckInActivityItem>> = activityItems

    override fun observeWorkoutItems(): Flow<List<CheckInWorkoutItem>> = workoutItems

    override fun observeUsageItems(): Flow<List<CheckInUsageItem>> = usageItems

    override fun observeMeals(): Flow<List<CheckInMeal>> = meals

    override fun observeMealFoods(): Flow<List<CheckInMealFoodItem>> = mealFoods

    private fun List<CheckInMeal>.forCheckIn(checkInId: String): List<CheckInMeal> =
        filter { it.checkInId == checkInId }.sortedWith(compareBy<CheckInMeal> { it.sortOrder }.thenBy { it.id })

    private fun foodsFor(checkInId: String): List<CheckInMealFoodItem> {
        val orderedMeals = meals.value.forCheckIn(checkInId)
        val rank = orderedMeals.mapIndexed { index, meal -> meal.id to index }.toMap()
        return mealFoods.value
            .filter { it.mealId in rank }
            .sortedWith(compareBy<CheckInMealFoodItem> { rank.getValue(it.mealId) }.thenBy { it.sortOrder }.thenBy { it.id })
    }
}

data class CheckInDaoSnapshot(
    val checkIns: Map<String, CheckInRecord>,
    val values: List<CheckInAxisValue>,
    val windows: Map<String, CheckInContextWindow>,
    val categories: List<CheckInContextCategory>,
    val activityItems: List<CheckInActivityItem>,
    val workoutItems: List<CheckInWorkoutItem>,
    val usageItems: List<CheckInUsageItem>,
    val meals: List<CheckInMeal>,
    val mealFoods: List<CheckInMealFoodItem>,
    val inserted: CheckInRecord?,
)
