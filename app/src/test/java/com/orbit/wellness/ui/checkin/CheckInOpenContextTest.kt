package com.orbit.wellness.ui.checkin

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInOpenContextTest {

    @Test
    fun successfulSaveEmitsExactlyOneNavigationId() = runBlocking {
        val events = CheckInOpenContextEvents()
        val received = mutableListOf<String>()
        val collector = launch {
            events.checkInIds.collect { received += it }
        }
        val id = checkInContextNavigationId(
            validationFailed = false,
            savedCheckInId = "check-in-1",
            persistenceFailed = false,
        )
        assertEquals("check-in-1", id)
        events.onSaved(id!!)
        yield()
        collector.cancel()

        val afterRecomposition = mutableListOf<String>()
        val laterCollector = launch {
            events.checkInIds.collect { afterRecomposition += it }
        }
        yield()
        laterCollector.cancel()

        assertEquals(listOf("check-in-1"), received)
        assertTrue(afterRecomposition.isEmpty())
    }

    @Test
    fun invalidOrFailedSaveDoesNotNavigate() {
        assertNull(
            checkInContextNavigationId(
                validationFailed = true,
                savedCheckInId = "check-in-1",
                persistenceFailed = false,
            ),
        )
        assertNull(
            checkInContextNavigationId(
                validationFailed = false,
                savedCheckInId = null,
                persistenceFailed = true,
            ),
        )
        assertNull(
            checkInContextNavigationId(
                validationFailed = false,
                savedCheckInId = "  ",
                persistenceFailed = false,
            ),
        )
    }
}
