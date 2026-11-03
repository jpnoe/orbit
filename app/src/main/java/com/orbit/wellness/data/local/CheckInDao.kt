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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAxes(axes: List<CheckInAxis>)

    @Insert
    suspend fun insertCheckIn(record: CheckInRecord)

    @Insert
    suspend fun insertAxisValues(values: List<CheckInAxisValue>)

    @Transaction
    suspend fun insertCheckInWithValues(record: CheckInRecord, values: List<CheckInAxisValue>) {
        insertCheckIn(record)
        insertAxisValues(values)
    }
}
