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

    val retiredTopLevelRoutes = setOf("activity", "exercise", "diet", "use")

    fun hidesBottomBar(route: String?): Boolean {
        return route == CHECK_IN_LOG || route?.startsWith("context/") == true
    }

    fun entryReturnsTo(): String = OrbitDestination.CheckIn.route

    fun isTopLevel(route: String?): Boolean = topLevel.any { it.route == route }
}
