package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckInDao {
    @Query(
        """
        SELECT * FROM check_in_axes
        WHERE isEnabled = 1
        ORDER BY sortOrder ASC, id ASC
        """,
    )
    fun observeEnabledAxes(): Flow<List<CheckInAxis>>

    @Query(
        """
        SELECT * FROM check_ins
        ORDER BY recordedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<CheckInRecord>>

    @Query(
        """
        SELECT * FROM check_ins
        ORDER BY recordedAt ASC, id ASC
        """,
    )
    fun observeAll(): Flow<List<CheckInRecord>>

    @Query(
        """
        SELECT * FROM check_in_axis_values
        WHERE checkInId = :checkInId
        ORDER BY axisId ASC
        """,
    )
    suspend fun getValuesForCheckIn(checkInId: String): List<CheckInAxisValue>

    @Query("SELECT * FROM check_in_axis_values")
    fun observeAxisValues(): Flow<List<CheckInAxisValue>>

    @Query(
        """
        SELECT * FROM check_ins
        ORDER BY recordedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatest(): CheckInRecord?

    @Query(
        """
        SELECT * FROM check_ins
        WHERE localDate = :localDate
        ORDER BY recordedAt ASC, id ASC
        """,
    )
    suspend fun getCheckInsOnDate(localDate: String): List<CheckInRecord>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAxes(axes: List<CheckInAxis>)

    @Insert
    suspend fun insertCheckIn(record: CheckInRecord)

    @Query("SELECT * FROM check_ins WHERE id = :id")
    suspend fun getCheckIn(id: String): CheckInRecord?

    @Query(
        """
        UPDATE check_ins
        SET recordedAt = :recordedAt, localDate = :localDate
        WHERE id = :id
        """,
    )
    suspend fun updateRecordedAt(id: String, recordedAt: Long, localDate: String)

    @Query("DELETE FROM check_ins WHERE id = :checkInId")
    suspend fun deleteCheckIn(checkInId: String)

    @Insert
    suspend fun insertAxisValues(values: List<CheckInAxisValue>)

    @Insert
    suspend fun insertContextWindow(window: CheckInContextWindow)

    @Insert
    suspend fun insertContextCategories(categories: List<CheckInContextCategory>)

    @Transaction
    suspend fun insertCheckInWithContext(
        record: CheckInRecord,
        values: List<CheckInAxisValue>,
        window: CheckInContextWindow,
        categories: List<CheckInContextCategory>,
    ) {
        insertCheckIn(record)
        insertAxisValues(values)
        insertContextWindow(window)
        insertContextCategories(categories)
    }

    @Query(
        """
        SELECT * FROM check_in_context_windows
        WHERE checkInId = :checkInId
        """,
    )
    fun observeContextWindow(checkInId: String): Flow<CheckInContextWindow?>

    @Query(
        """
        SELECT * FROM check_in_context_windows
        WHERE checkInId = :checkInId
        """,
    )
    suspend fun getContextWindow(checkInId: String): CheckInContextWindow?

    @Query(
        """
        UPDATE check_in_context_windows
        SET windowStartAt = :windowStartAt, windowEndAt = :windowEndAt
        WHERE checkInId = :checkInId
        """,
    )
    suspend fun updateWindowBounds(checkInId: String, windowStartAt: Long, windowEndAt: Long)

    @Query(
        """
        UPDATE check_in_context_windows
        SET completedAt = :completedAt
        WHERE checkInId = :checkInId AND completedAt IS NULL
        """,
    )
    suspend fun setCompletedAtIfNull(checkInId: String, completedAt: Long): Int

    /**
     * Sets completion only while it is still null.
     * Returns null when the window does not exist, otherwise the stored completion time.
     */
    @Transaction
    suspend fun completeContextIfUnset(checkInId: String, completedAt: Long): Long? {
        val existing = getContextWindow(checkInId) ?: return null
        if (existing.completedAt != null) return existing.completedAt
        setCompletedAtIfNull(checkInId, completedAt)
        return getContextWindow(checkInId)?.completedAt
    }

    @Query(
        """
        SELECT * FROM check_in_context_categories
        WHERE checkInId = :checkInId
        """,
    )
    fun observeContextCategories(checkInId: String): Flow<List<CheckInContextCategory>>

    @Query(
        """
        SELECT * FROM check_in_context_categories
        WHERE checkInId = :checkInId
        """,
    )
    suspend fun getContextCategories(checkInId: String): List<CheckInContextCategory>

    @Query(
        """
        SELECT * FROM check_in_activity_items
        WHERE checkInId = :checkInId
        ORDER BY activityName ASC, id ASC
        """,
    )
    fun observeActivityItems(checkInId: String): Flow<List<CheckInActivityItem>>

    @Query(
        """
        SELECT * FROM check_in_activity_items
        WHERE checkInId = :checkInId
        ORDER BY activityName ASC, id ASC
        """,
    )
    suspend fun getActivityItems(checkInId: String): List<CheckInActivityItem>

    @Query("DELETE FROM check_in_activity_items WHERE checkInId = :checkInId")
    suspend fun deleteActivityItemsForCheckIn(checkInId: String)

    @Insert
    suspend fun insertActivityItems(items: List<CheckInActivityItem>)

    @Query(
        """
        UPDATE check_in_context_categories
        SET status = :status, reviewedAt = :reviewedAt
        WHERE checkInId = :checkInId AND category = :category
        """,
    )
    suspend fun updateCategoryStatus(
        checkInId: String,
        category: String,
        status: String,
        reviewedAt: Long,
    )

    @Transaction
    suspend fun replaceActivitySelections(
        checkInId: String,
        items: List<CheckInActivityItem>,
        status: String,
        reviewedAt: Long,
    ) {
        deleteActivityItemsForCheckIn(checkInId)
        if (items.isNotEmpty()) {
            insertActivityItems(items)
        }
        updateCategoryStatus(
            checkInId = checkInId,
            category = CheckInContextCategories.ACTIVITY,
            status = status,
            reviewedAt = reviewedAt,
        )
    }

    @Query(
        """
        SELECT * FROM check_in_workout_items
        WHERE checkInId = :checkInId
        ORDER BY rowid ASC
        """,
    )
    fun observeWorkoutItems(checkInId: String): Flow<List<CheckInWorkoutItem>>

    @Query(
        """
        SELECT * FROM check_in_workout_items
        WHERE checkInId = :checkInId
        ORDER BY rowid ASC
        """,
    )
    suspend fun getWorkoutItems(checkInId: String): List<CheckInWorkoutItem>

    @Query("DELETE FROM check_in_workout_items WHERE checkInId = :checkInId")
    suspend fun deleteWorkoutItemsForCheckIn(checkInId: String)

    @Insert
    suspend fun insertWorkoutItems(items: List<CheckInWorkoutItem>)

    @Transaction
    suspend fun replaceWorkoutSelections(
        checkInId: String,
        items: List<CheckInWorkoutItem>,
        status: String,
        reviewedAt: Long,
    ) {
        deleteWorkoutItemsForCheckIn(checkInId)
        if (items.isNotEmpty()) {
            insertWorkoutItems(items)
        }
        updateCategoryStatus(
            checkInId = checkInId,
            category = CheckInContextCategories.WORKOUT,
            status = status,
            reviewedAt = reviewedAt,
        )
    }

    @Query(
        """
        SELECT * FROM check_in_usage_items
        WHERE checkInId = :checkInId
        ORDER BY rowid ASC
        """,
    )
    fun observeUsageItems(checkInId: String): Flow<List<CheckInUsageItem>>

    @Query(
        """
        SELECT * FROM check_in_usage_items
        WHERE checkInId = :checkInId
        ORDER BY rowid ASC
        """,
    )
    suspend fun getUsageItems(checkInId: String): List<CheckInUsageItem>

    @Query("DELETE FROM check_in_usage_items WHERE checkInId = :checkInId")
    suspend fun deleteUsageItemsForCheckIn(checkInId: String)

    @Insert
    suspend fun insertUsageItems(items: List<CheckInUsageItem>)

    @Transaction
    suspend fun replaceUsageSelections(
        checkInId: String,
        items: List<CheckInUsageItem>,
        status: String,
        reviewedAt: Long,
    ) {
        deleteUsageItemsForCheckIn(checkInId)
        if (items.isNotEmpty()) {
            insertUsageItems(items)
        }
        updateCategoryStatus(
            checkInId = checkInId,
            category = CheckInContextCategories.USAGE,
            status = status,
            reviewedAt = reviewedAt,
        )
    }

    @Query(
        """
        SELECT * FROM check_in_meals
        WHERE checkInId = :checkInId
        ORDER BY sortOrder ASC, rowid ASC
        """,
    )
    fun observeMeals(checkInId: String): Flow<List<CheckInMeal>>

    @Query(
        """
        SELECT * FROM check_in_meals
        WHERE checkInId = :checkInId
        ORDER BY sortOrder ASC, rowid ASC
        """,
    )
    suspend fun getMeals(checkInId: String): List<CheckInMeal>

    @Query(
        """
        SELECT food.* FROM check_in_meal_food_items AS food
        INNER JOIN check_in_meals AS meal ON food.mealId = meal.id
        WHERE meal.checkInId = :checkInId
        ORDER BY meal.sortOrder ASC, food.sortOrder ASC, food.rowid ASC
        """,
    )
    fun observeMealFoods(checkInId: String): Flow<List<CheckInMealFoodItem>>

    @Query(
        """
        SELECT food.* FROM check_in_meal_food_items AS food
        INNER JOIN check_in_meals AS meal ON food.mealId = meal.id
        WHERE meal.checkInId = :checkInId
        ORDER BY meal.sortOrder ASC, food.sortOrder ASC, food.rowid ASC
        """,
    )
    suspend fun getMealFoods(checkInId: String): List<CheckInMealFoodItem>

    @Query("DELETE FROM check_in_meals WHERE checkInId = :checkInId")
    suspend fun deleteMealsForCheckIn(checkInId: String)

    @Insert
    suspend fun insertMeals(meals: List<CheckInMeal>)

    @Insert
    suspend fun insertMealFoods(foods: List<CheckInMealFoodItem>)

    @Query(
        """
        SELECT * FROM check_ins
        WHERE recordedAt >= :startMillis AND recordedAt <= :endMillis
        ORDER BY recordedAt DESC, id DESC
        """,
    )
    fun observeCheckInsBetween(startMillis: Long, endMillis: Long): Flow<List<CheckInRecord>>

    @Query("SELECT * FROM check_in_context_windows")
    fun observeContextWindows(): Flow<List<CheckInContextWindow>>

    @Query("SELECT * FROM check_in_context_categories")
    fun observeContextCategories(): Flow<List<CheckInContextCategory>>

    @Query(
        """
        SELECT * FROM check_in_activity_items
        ORDER BY activityName ASC, id ASC
        """,
    )
    fun observeActivityItems(): Flow<List<CheckInActivityItem>>

    @Query(
        """
        SELECT * FROM check_in_workout_items
        ORDER BY rowid ASC
        """,
    )
    fun observeWorkoutItems(): Flow<List<CheckInWorkoutItem>>

    @Query(
        """
        SELECT * FROM check_in_usage_items
        ORDER BY rowid ASC
        """,
    )
    fun observeUsageItems(): Flow<List<CheckInUsageItem>>

    @Query(
        """
        SELECT * FROM check_in_meals
        ORDER BY sortOrder ASC, rowid ASC
        """,
    )
    fun observeMeals(): Flow<List<CheckInMeal>>

    @Query(
        """
        SELECT * FROM check_in_meal_food_items
        ORDER BY sortOrder ASC, rowid ASC
        """,
    )
    fun observeMealFoods(): Flow<List<CheckInMealFoodItem>>

    @Transaction
    suspend fun replaceDietSelections(
        checkInId: String,
        meals: List<CheckInMeal>,
        foods: List<CheckInMealFoodItem>,
        status: String,
        reviewedAt: Long,
    ) {
        deleteMealsForCheckIn(checkInId)
        if (meals.isNotEmpty()) {
            insertMeals(meals)
        }
        if (foods.isNotEmpty()) {
            insertMealFoods(foods)
        }
        updateCategoryStatus(
            checkInId = checkInId,
            category = CheckInContextCategories.DIET,
            status = status,
            reviewedAt = reviewedAt,
        )
    }
}
