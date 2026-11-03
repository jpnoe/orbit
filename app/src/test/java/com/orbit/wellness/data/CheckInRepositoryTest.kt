package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInAxis
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInDao
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.DefaultCheckInAxes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInRepositoryTest {

    @Test
    fun saveCheckInRejectsBlankTrigger() {
        val dao = RecordingCheckInDao()
        val repository = CheckInRepository(dao)
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveCheckIn(
                    axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 4)),
                    trigger = "   ",
                    recordedAt = 0L,
                )
            }
        }
        assertTrue(error.message!!.contains("trigger"))
        assertNull(dao.inserted)
    }

    @Test
    fun saveCheckInAllowsFutureNonblankTriggerNames() {
        val dao = RecordingCheckInDao()
        val repository = CheckInRepository(dao)
        val saved = runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 3)),
                trigger = "after_workout",
                recordedAt = 1_700_000_000_000L,
            )
        }
        assertEquals("after_workout", saved.trigger)
        assertEquals("after_workout", dao.inserted?.trigger)
    }

    private class RecordingCheckInDao : CheckInDao {
        var inserted: CheckInRecord? = null

        override fun observeEnabledAxes(): Flow<List<CheckInAxis>> = emptyFlow()

        override fun observeRecent(limit: Int): Flow<List<CheckInRecord>> = emptyFlow()

        override suspend fun getValuesForCheckIn(checkInId: String): List<CheckInAxisValue> = emptyList()

        override suspend fun getLatest(): CheckInRecord? = null

        override suspend fun insertAxes(axes: List<CheckInAxis>) = Unit

        override suspend fun insertCheckIn(record: CheckInRecord) {
            inserted = record
        }

        override suspend fun insertAxisValues(values: List<CheckInAxisValue>) = Unit
    }
}
