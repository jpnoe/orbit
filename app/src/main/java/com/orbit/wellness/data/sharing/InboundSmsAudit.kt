package com.orbit.wellness.data.sharing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Surfaces only SMS failures in the Trusted Contacts UI.
 * Routine non-trigger texts and successful shares stay out of the way.
 */
class InboundSmsAudit(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _lastError = MutableStateFlow(prefs.getString(KEY_ERROR, null))
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        // Drop older verbose debug breadcrumbs from earlier builds.
        prefs.edit().remove(KEY_LEGACY_SUMMARY).apply()
    }

    fun recordError(summary: String) {
        val stamped = "${timeFormatter.format(Instant.now())} · $summary"
        prefs.edit().putString(KEY_ERROR, stamped).apply()
        _lastError.value = stamped
    }

    fun clear() {
        prefs.edit().remove(KEY_ERROR).apply()
        _lastError.value = null
    }

    companion object {
        private const val PREFS = "orbit_sms_audit"
        private const val KEY_ERROR = "last_sms_error"
        private const val KEY_LEGACY_SUMMARY = "last_inbound_summary"
        private val timeFormatter = DateTimeFormatter.ofPattern("h:mm:ss a")
            .withZone(ZoneId.systemDefault())
    }
}
