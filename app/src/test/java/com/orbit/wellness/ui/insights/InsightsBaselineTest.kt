package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetBuilder
import com.orbit.wellness.domain.checkin.summarizeCheckInAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class InsightsBaselineTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun zeroCheckInsInviteAFirstEntryAndRecordNothing() {
        val baseline = snapshot().toInsightsBaseline(hasWearableObservations = false, zone = zone)
        assertEquals(InsightsBaselineKind.EMPTY, baseline.kind)
        assertEquals(0, baseline.checkInCount)
        assertEquals(0, baseline.dayCount)
        assertEquals(RecordedAvailability.NOT_YET_RECORDED, baseline.checkInState)
        assertEquals(RecordedAvailability.NOT_YET_RECORDED, baseline.context)
        assertEquals(RecordedAvailability.NOT_YET_RECORDED, baseline.wearables)
    }

    @Test
    fun storedCheckInsCountObservationsAndDistinctCalendarDays() {
        val sameDay = listOf(
            at(2026, 11, 4, 8),
            at(2026, 11, 4, 20),
        )
        val oneDay = checkIns(sameDay).toInsightsBaseline(hasWearableObservations = false, zone = zone)
        assertEquals(InsightsBaselineKind.COLLECTING, oneDay.kind)
        assertEquals(2, oneDay.checkInCount)
        assertEquals(1, oneDay.dayCount)
        assertEquals(RecordedAvailability.RECORDED, oneDay.checkInState)

        val twoDays = checkIns(
            listOf(at(2026, 11, 3, 9), at(2026, 11, 5, 18)),
        ).toInsightsBaseline(hasWearableObservations = false, zone = zone)
        assertEquals(2, twoDays.checkInCount)
        assertEquals(2, twoDays.dayCount)
    }

    @Test
    fun contextIsRecordedOnlyWhenAContextualEntryIsStored() {
        val emptyContext = checkIns(listOf(at(2026, 11, 4, 10)), withActivity = false)
            .toInsightsBaseline(hasWearableObservations = false, zone = zone)
        assertEquals(RecordedAvailability.NOT_YET_RECORDED, emptyContext.context)
        assertEquals(RecordedAvailability.RECORDED, emptyContext.checkInState)

        val withActivity = checkIns(listOf(at(2026, 11, 4, 10)), withActivity = true)
            .toInsightsBaseline(hasWearableObservations = false, zone = zone)
        assertEquals(RecordedAvailability.RECORDED, withActivity.context)
    }

    @Test
    fun wearablesAreRecordedOnlyWhenAnObservationIsStored() {
        val absent = checkIns(listOf(at(2026, 11, 4, 10)))
            .toInsightsBaseline(hasWearableObservations = false, zone = zone)
        assertEquals(RecordedAvailability.NOT_YET_RECORDED, absent.wearables)

        val present = snapshot().toInsightsBaseline(hasWearableObservations = true, zone = zone)
        assertEquals(InsightsBaselineKind.EMPTY, present.kind)
        assertEquals(RecordedAvailability.NOT_YET_RECORDED, present.checkInState)
        assertEquals(RecordedAvailability.RECORDED, present.wearables)
    }

    @Test
    fun sparseDataDoesNotClaimALearnedPattern() {
        val one = checkIns(listOf(at(2026, 11, 4, 10)))
            .toInsightsBaseline(hasWearableObservations = false, zone = zone)
        val two = checkIns(listOf(at(2026, 11, 3, 9), at(2026, 11, 5, 18)), withActivity = true)
            .toInsightsBaseline(hasWearableObservations = true, zone = zone)
        assertEquals(
            setOf(InsightsBaselineKind.EMPTY, InsightsBaselineKind.COLLECTING),
            InsightsBaselineKind.entries.toSet(),
        )
        assertEquals(InsightsBaselineKind.COLLECTING, one.kind)
        assertEquals(InsightsBaselineKind.COLLECTING, two.kind)
        assertEquals(1, one.checkInCount)
        val copy = source(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        ).readText()
            .substringAfter("insights_title")
            .substringBefore("wearable_metric_steps")
        val screen = source(
            "src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
        ).readText()
        assertTrue(copy.contains("Orbit is learning your patterns"))
        assertTrue(copy.contains("Patterns will appear here as your history grows."))
        assertTrue(copy.contains("Not yet recorded"))
        assertFalse(copy.contains("Disconnected"))
        assertFalse(copy.contains("has learned", ignoreCase = true))
        assertFalse(copy.contains("%"))
        assertFalse(screen.contains("linkedTransition"))
        assertFalse(screen.contains("WearableCoverage"))
        assertFalse(screen.contains("AnalysisIssue"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    }

    private fun checkIns(times: List<Long>, withActivity: Boolean = false): CheckInAnalysisSnapshot {
        val records = times.mapIndexed { index, time ->
            CheckInRecord(
                id = "check-$index",
                localDate = "2026-11-0${index + 1}",
                recordedAt = time,
                trigger = CheckInRecord.TRIGGER_MANUAL,
            )
        }
        val windows = records.map { record ->
            CheckInContextWindow(
                checkInId = record.id,
                previousCheckInId = null,
                windowStartAt = record.recordedAt - 60_000,
                windowEndAt = record.recordedAt,
                startBasis = CheckInContextStartBasis.WAKE_TIME,
                completedAt = record.recordedAt,
            )
        }
        val categories = records.flatMap { record ->
            CheckInContextCategories.ALL.map { category ->
                val status = if (withActivity && category == CheckInContextCategories.ACTIVITY) {
                    CheckInContextStatus.ENTRIES
                } else {
                    CheckInContextStatus.NONE
                }
                CheckInContextCategory(checkInId = record.id, category = category, status = status)
            }
        }
        val activities = if (withActivity) {
            records.map { record ->
                CheckInActivityItem(
                    id = "activity-${record.id}",
                    checkInId = record.id,
                    activityOptionId = null,
                    activityName = "Walk",
                    reportedAt = record.recordedAt,
                )
            }
        } else {
            emptyList()
        }
        return snapshot(records, windows, categories, activities)
    }

    private fun snapshot(
        records: List<CheckInRecord> = emptyList(),
        windows: List<CheckInContextWindow> = emptyList(),
        categories: List<CheckInContextCategory> = emptyList(),
        activities: List<CheckInActivityItem> = emptyList(),
    ): CheckInAnalysisSnapshot {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = records,
            windows = windows,
            categories = categories,
            activities = activities,
        )
        return CheckInAnalysisSnapshot(dataset, summarizeCheckInAnalysis(dataset))
    }

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }
}
