package com.orbit.wellness.ui.context

import android.net.Uri

object ContextRoutes {
    const val CHECK_IN_ID = "checkInId"
    const val OVERVIEW = "context/{$CHECK_IN_ID}"
    const val ACTIVITY = "context/{$CHECK_IN_ID}/activity"

    fun overview(checkInId: String): String = "context/${Uri.encode(checkInId)}"

    fun activity(checkInId: String): String = "context/${Uri.encode(checkInId)}/activity"
}
