package com.orbit.wellness.data.biometrics

/**
 * Reads every page of one record query.
 * The first call uses a null token. A null or blank [RecordPage.pageToken]
 * ends the read, so no further request is made.
 */
data class RecordPage<T>(
    val records: List<T>,
    val pageToken: String?,
)

suspend fun <T> readAllPages(
    fetchPage: suspend (pageToken: String?) -> RecordPage<T>,
): List<T> {
    val collected = ArrayList<T>()
    var pageToken: String? = null
    while (true) {
        val page = fetchPage(pageToken)
        collected += page.records
        val next = page.pageToken
        if (next.isNullOrEmpty()) return collected
        pageToken = next
    }
}
