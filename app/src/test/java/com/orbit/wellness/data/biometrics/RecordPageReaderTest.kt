package com.orbit.wellness.data.biometrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordPageReaderTest {
    @Test
    fun nullOrBlankTokenEndsTheRead() = runBlocking {
        val nullCalls = mutableListOf<String?>()
        val fromNull = readAllPages { token ->
            nullCalls += token
            RecordPage(records = listOf("only"), pageToken = null)
        }
        assertEquals(listOf("only"), fromNull)
        assertEquals(listOf<String?>(null), nullCalls)

        val blankCalls = mutableListOf<String?>()
        val fromBlank = readAllPages { token ->
            blankCalls += token
            RecordPage(records = listOf("only"), pageToken = "")
        }
        assertEquals(listOf("only"), fromBlank)
        assertEquals(listOf<String?>(null), blankCalls)
    }

    @Test
    fun laterPagesAreReadUntilTheTokenIsEmpty() = runBlocking {
        val pages = listOf(
            RecordPage(records = listOf("a", "b"), pageToken = "page-2"),
            RecordPage(records = listOf("c"), pageToken = "page-3"),
            RecordPage(records = listOf("d"), pageToken = ""),
        )
        val requested = mutableListOf<String?>()
        val records = readAllPages { token ->
            requested += token
            pages[requested.lastIndex]
        }
        assertEquals(listOf(null, "page-2", "page-3"), requested)
        assertEquals(listOf("a", "b", "c", "d"), records)
    }
}
