package com.orbit.wellness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInDraftsTest {

    @Test
    fun untouchedMidpointAnchorsAreOmitted() {
        val drafts = mapOf(
            "mood" to null,
            "energy" to null,
        )
        assertEquals(5, CheckInDrafts.visualAnchor("mood", moodAnchor = 5f))
        assertEquals(5, CheckInDrafts.visualAnchor("energy", moodAnchor = 8f))
        assertTrue(CheckInDrafts.toAxisInputs(drafts).isEmpty())
        assertTrue(CheckInDrafts.toAxisInputs(emptyMap()).isEmpty())
    }

    @Test
    fun explicitZeroAndTenAreRetained() {
        val inputs = CheckInDrafts.toAxisInputs(
            mapOf(
                "mood" to 0,
                "anxiety" to 10,
                "energy" to null,
            ),
        )
        assertEquals(
            setOf("mood" to 0, "anxiety" to 10),
            inputs.map { it.axisId to it.value }.toSet(),
        )
    }

    @Test
    fun clearingAnAxisRemovesItFromSavedInputs() {
        val drafts = linkedMapOf<String, Int?>(
            "mood" to 7,
            "energy" to 2,
        )
        drafts["mood"] = null
        assertEquals(
            listOf("energy" to 2),
            CheckInDrafts.toAxisInputs(drafts).map { it.axisId to it.value },
        )
    }

    @Test
    fun axisOrderingDoesNotChangeSavedIdentityValuePairs() {
        val first = CheckInDrafts.toAxisInputs(
            linkedMapOf(
                "confidence" to 4,
                "mood" to 0,
                "anxiety" to 10,
            ),
        )
        val second = CheckInDrafts.toAxisInputs(
            linkedMapOf(
                "anxiety" to 10,
                "confidence" to 4,
                "mood" to 0,
            ),
        )
        assertEquals(
            first.map { it.axisId to it.value }.toSet(),
            second.map { it.axisId to it.value }.toSet(),
        )
        assertEquals(3, first.size)
        assertEquals(3, second.size)
    }
}
