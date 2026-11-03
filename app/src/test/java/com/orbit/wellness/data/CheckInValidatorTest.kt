package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInValidatorTest {

    @Test
    fun acceptsValuesInsideTheScale() {
        CheckInValidator.validate(
            listOf(
                CheckInAxisInput(DefaultCheckInAxes.MOOD, 0),
                CheckInAxisInput(DefaultCheckInAxes.ANXIETY, 10),
            ),
        )
    }

    @Test
    fun rejectsAnEmptyCheckIn() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CheckInValidator.validate(emptyList())
        }
        assertTrue(error.message!!.contains("At least one axis value"))
    }

    @Test
    fun rejectsBlankAxisIds() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CheckInValidator.validate(
                listOf(CheckInAxisInput("  ", 5)),
            )
        }
        assertTrue(error.message!!.contains("blank"))
    }

    @Test
    fun rejectsDuplicateAxisIds() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CheckInValidator.validate(
                listOf(
                    CheckInAxisInput(DefaultCheckInAxes.MOOD, 4),
                    CheckInAxisInput(DefaultCheckInAxes.ENERGY, 5),
                    CheckInAxisInput(DefaultCheckInAxes.MOOD, 6),
                ),
            )
        }
        assertTrue(error.message!!.contains("Duplicate axis IDs"))
        assertTrue(error.message!!.contains(DefaultCheckInAxes.MOOD))
    }

    @Test
    fun rejectsValuesOutsideZeroToTen() {
        listOf(-1, 11).forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                CheckInValidator.validate(
                    listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, value)),
                )
            }
            assertTrue(error.message!!.contains("0..10"))
        }
    }

    @Test
    fun axisValueConstructorRejectsValuesOutsideZeroToTen() {
        listOf(-1, 11).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                CheckInAxisValue(checkInId = "check-in", axisId = DefaultCheckInAxes.MOOD, value = value)
            }
        }
        val low = CheckInAxisValue(checkInId = "check-in", axisId = DefaultCheckInAxes.MOOD, value = 0)
        val high = CheckInAxisValue(checkInId = "check-in", axisId = DefaultCheckInAxes.MOOD, value = 10)
        assertEquals(0, low.value)
        assertEquals(10, high.value)
    }

    @Test
    fun defaultAxesUseStableIdsLabelsAndSeedSql() {
        assertEquals(
            listOf(
                DefaultCheckInAxes.MOOD,
                DefaultCheckInAxes.ENERGY,
                DefaultCheckInAxes.CONFIDENCE,
                DefaultCheckInAxes.ANXIETY,
            ),
            DefaultCheckInAxes.ALL.map { it.id },
        )
        val byId = DefaultCheckInAxes.ALL.associateBy { it.id }
        assertEquals("Very unpleasant" to "Very pleasant", byId.getValue("mood").lowLabel to byId.getValue("mood").highLabel)
        assertEquals("No energy" to "Extremely energized", byId.getValue("energy").lowLabel to byId.getValue("energy").highLabel)
        assertEquals("No confidence" to "Extremely confident", byId.getValue("confidence").lowLabel to byId.getValue("confidence").highLabel)
        assertEquals("No anxiety" to "Extreme anxiety", byId.getValue("anxiety").lowLabel to byId.getValue("anxiety").highLabel)
        assertTrue(DefaultCheckInAxes.ALL.all { it.isDefault && it.isEnabled && it.createdAt == DefaultCheckInAxes.SEEDED_CREATED_AT })

        val sql = DefaultCheckInAxes.insertOrIgnoreSql()
        assertTrue(sql.startsWith("INSERT OR IGNORE INTO check_in_axes"))
        listOf("mood", "energy", "confidence", "anxiety").forEach { id ->
            assertTrue(sql.contains("'$id'"))
        }
    }

    @Test
    fun triggerConstantsMatchTheStableNames() {
        assertEquals("wake", CheckInRecord.TRIGGER_WAKE)
        assertEquals("scheduled", CheckInRecord.TRIGGER_SCHEDULED)
        assertEquals("manual", CheckInRecord.TRIGGER_MANUAL)
        assertEquals("evening", CheckInRecord.TRIGGER_EVENING)
    }
}
