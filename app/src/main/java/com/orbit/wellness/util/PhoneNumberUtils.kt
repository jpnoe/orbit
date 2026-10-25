package com.orbit.wellness.util

object PhoneNumberUtils {
    /**
     * Normalize to digits only, preferring a US-style 11-digit form starting with 1
     * when given a 10-digit national number.
     */
    fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 11 && digits.startsWith("1") -> digits
            digits.length == 10 -> "1$digits"
            else -> digits
        }
    }

    fun matches(storedE164: String, incomingRaw: String): Boolean {
        val stored = normalize(storedE164)
        val incoming = normalize(incomingRaw)
        if (stored.isEmpty() || incoming.isEmpty()) return false
        if (stored == incoming) return true
        val storedTail = stored.takeLast(10)
        val incomingTail = incoming.takeLast(10)
        return storedTail.length == 10 && storedTail == incomingTail
    }
}
