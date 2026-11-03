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
        SELECT * FROM check_in_axis_values
        WHERE checkInId = :checkInId
        ORDER BY axisId ASC
        """,
    )
    suspend fun getValuesForCheckIn(checkInId: String): List<CheckInAxisValue>

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
}
