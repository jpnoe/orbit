package com.orbit.wellness.ui.navigation

enum class OrbitDestination(
    val route: String,
) {
    CheckIn("check_in"),
    History("history"),
    Insights("insights"),
    Profile("profile"),
}

/**
 * Top-level navigation rules that do not depend on Compose.
 * The old category tabs stay out of this list. Their route names are retained only
 * so a restored back stack can be sent back to Log instead of crashing.
 */
object OrbitNavigation {
    val topLevel: List<OrbitDestination> = listOf(
        OrbitDestination.CheckIn,
        OrbitDestination.History,
        OrbitDestination.Insights,
        OrbitDestination.Profile,
    )

    const val CHECK_IN_LOG = "check_in/log"
    const val STATUS_REQUEST = "status_request"
    const val RESEARCH_PREVIEW = "profile/research_preview"

    val retiredTopLevelRoutes = setOf("activity", "exercise", "diet", "use")

    /** Release builds do not register this route. */
    fun researchPreviewRoute(debugBuild: Boolean): String? {
        return if (debugBuild) RESEARCH_PREVIEW else null
    }

    fun hidesBottomBar(route: String?): Boolean {
        return route == CHECK_IN_LOG || route == RESEARCH_PREVIEW || route?.startsWith("context/") == true
    }

    fun entryReturnsTo(): String = OrbitDestination.CheckIn.route

    fun isTopLevel(route: String?): Boolean = topLevel.any { it.route == route }
}
