package com.orbit.wellness.ui.context

import android.net.Uri

object ContextRoutes {
    const val CHECK_IN_ID = "checkInId"
    const val OVERVIEW = "context/{$CHECK_IN_ID}"
    const val ACTIVITY = "context/{$CHECK_IN_ID}/activity"
    const val WORKOUT = "context/{$CHECK_IN_ID}/workout"
    const val USAGE = "context/{$CHECK_IN_ID}/usage"
    const val DIET = "context/{$CHECK_IN_ID}/diet"

    fun overview(checkInId: String): String = "context/${Uri.encode(checkInId)}"

    fun activity(checkInId: String): String = "context/${Uri.encode(checkInId)}/activity"

    fun workout(checkInId: String): String = "context/${Uri.encode(checkInId)}/workout"

    fun usage(checkInId: String): String = "context/${Uri.encode(checkInId)}/usage"

    fun diet(checkInId: String): String = "context/${Uri.encode(checkInId)}/diet"
}
