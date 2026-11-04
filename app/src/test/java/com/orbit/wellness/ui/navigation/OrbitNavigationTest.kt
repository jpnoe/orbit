package com.orbit.wellness.ui.navigation

import com.orbit.wellness.ui.checkin.CheckInUiState
import com.orbit.wellness.ui.context.ContextRoutes
import com.orbit.wellness.ui.history.HistoryTab
import com.orbit.wellness.ui.profile.ProfileSection
import com.orbit.wellness.ui.profile.ProfileSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OrbitNavigationTest {
    @Test
    fun topLevelDestinationsAreLogHistoryAndProfile() {
        assertEquals(
            listOf("check_in", "history", "profile"),
            OrbitNavigation.topLevel.map { it.route },
        )
        assertEquals(
            listOf("Log", "History", "Profile"),
            listOf("tab_check_in", "tab_history", "tab_profile").map { stringValue(it) },
        )
        assertEquals("Log", stringValue("check_in_title"))
        assertTrue(OrbitNavigation.topLevel.none { it.route in OrbitNavigation.retiredTopLevelRoutes })
    }

    @Test
    fun bottomNavigationDoesNotIncludeTheOldCategoryTabs() {
        val routes = OrbitNavigation.topLevel.map { it.route }.toSet()
        assertFalse("activity" in routes)
        assertFalse("exercise" in routes)
        assertFalse("diet" in routes)
        assertFalse("use" in routes)
        assertEquals(setOf("activity", "exercise", "diet", "use"), OrbitNavigation.retiredTopLevelRoutes)
    }

    @Test
    fun logStillOpensTheEntryWorkflowAndHidesTheBottomBar() {
        assertEquals("check_in", OrbitDestination.CheckIn.route)
        assertEquals("check_in/log", OrbitNavigation.CHECK_IN_LOG)
        assertTrue(OrbitNavigation.hidesBottomBar(OrbitNavigation.CHECK_IN_LOG))
        listOf(
            ContextRoutes.OVERVIEW,
            ContextRoutes.ACTIVITY,
            ContextRoutes.WORKOUT,
            ContextRoutes.USAGE,
            ContextRoutes.DIET,
        ).forEach { route ->
            assertTrue(route.startsWith("context/"))
            assertTrue(OrbitNavigation.hidesBottomBar(route))
        }
        assertFalse(OrbitNavigation.hidesBottomBar(OrbitDestination.CheckIn.route))
        assertFalse(OrbitNavigation.hidesBottomBar(OrbitDestination.History.route))
        assertFalse(OrbitNavigation.hidesBottomBar(OrbitDestination.Profile.route))
    }

    @Test
    fun doneAndCancelReturnToLog() {
        assertEquals(OrbitDestination.CheckIn.route, OrbitNavigation.entryReturnsTo())
        assertTrue(OrbitNavigation.isTopLevel(OrbitNavigation.entryReturnsTo()))
    }

    @Test
    fun logLandingNoLongerCarriesMoodHistory() {
        val names = CheckInUiState::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("todaySamples" in names)
        assertFalse("todaySampleCount" in names)
        assertTrue("emotionOptions" in names)
        assertTrue("hasLoggedSleepToday" in names)
    }

    @Test
    fun historyIsAStandaloneDestinationWithLogAndTrends() {
        assertTrue(OrbitNavigation.isTopLevel(OrbitDestination.History.route))
        assertTrue(ProfileSection.entries.none { it.name == "HISTORY" })
        assertEquals(listOf(HistoryTab.LOG, HistoryTab.TRENDS), HistoryTab.entries)
    }

    @Test
    fun profileShowsOnlyListsHealthAndSettingsWithListsFirst() {
        assertEquals(
            listOf(ProfileSection.MY_LISTS, ProfileSection.HEALTH, ProfileSection.SETTINGS),
            ProfileSections.visible,
        )
        assertEquals(ProfileSection.MY_LISTS, ProfileSections.defaultSection)
        assertEquals(ProfileSections.visible.first(), ProfileSections.defaultSection)
    }

    @Test
    fun dailyGuideOnlyVisitsTheRemainingTopLevelDestinations() {
        assertEquals(OrbitNavigation.topLevel, DailyGuide.steps)
        assertTrue(DailyGuide.steps.none { it.route in OrbitNavigation.retiredTopLevelRoutes })
        assertEquals(OrbitDestination.CheckIn, DailyGuide.steps.first())
        assertEquals(OrbitDestination.Profile, DailyGuide.nextAfter(OrbitDestination.History.route))
        assertTrue(DailyGuide.isLast(OrbitDestination.Profile.route))
    }

    @Test
    fun statusRequestOpensARouteOutsideTheBottomBar() {
        assertEquals("status_request", OrbitNavigation.STATUS_REQUEST)
        assertFalse(OrbitNavigation.isTopLevel(OrbitNavigation.STATUS_REQUEST))
        assertFalse(OrbitNavigation.hidesBottomBar(OrbitNavigation.STATUS_REQUEST))
        assertFalse(OrbitNavigation.STATUS_REQUEST in OrbitNavigation.retiredTopLevelRoutes)
    }

    private fun stringValue(name: String): String {
        val xml = stringsFile().readText()
        val match = Regex("""<string name="$name">([^<]*)</string>""").find(xml)
        return match?.groupValues?.get(1) ?: error("Missing string $name")
    }

    private fun stringsFile(): File {
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        )
        return candidates.firstOrNull { it.exists() } ?: error("strings.xml not found")
    }
}
