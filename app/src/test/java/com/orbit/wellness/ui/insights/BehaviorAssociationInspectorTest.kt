package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.domain.checkin.ActivityCategoryObservationV2
import com.orbit.wellness.domain.checkin.ActivityObservationV2
import com.orbit.wellness.domain.checkin.AxisReadingV2
import com.orbit.wellness.domain.checkin.BehaviorKind
import com.orbit.wellness.domain.checkin.CategoryObservationKindV2
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.ContextIntervalObservationV2
import com.orbit.wellness.domain.checkin.CoreAxisViewV2
import com.orbit.wellness.domain.checkin.DietCategoryObservationV2
import com.orbit.wellness.domain.checkin.StateObservationV2
import com.orbit.wellness.domain.checkin.UsageCategoryObservationV2
import com.orbit.wellness.domain.checkin.UsageObservationV2
import com.orbit.wellness.domain.checkin.WorkoutCategoryObservationV2
import com.orbit.wellness.domain.checkin.WorkoutObservationV2
import com.orbit.wellness.domain.checkin.projectBehavioralEvidence
import com.orbit.wellness.domain.checkin.summarizeCheckInAnalysis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BehaviorAssociationInspectorTest {
    @Test
    fun anEmptyHistoryKeepsTheAuditNoticeAndNamesWhatToLog() {
        val content = inspect(dataset(emptyList()))
        assertEquals("behavior-association-v2", content.protocol)
        assertEquals(BehaviorAssociationCopy.NOTICE, content.notice)
        assertEquals(BehaviorAssociationCopy.EMPTY, content.emptyMessage)
        assertTrue(content.profiles.isEmpty())
        assertEquals(0, content.behaviorCount)
        assertNull(retainedBehaviorSelection(content.profiles, null))
    }

    @Test
    fun axisRowsKeepBothSidesAndLeaveAMissingSideUnavailable() {
        val profile = inspect(axisDataset()).profiles.single()
        val mood = profile.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        val before = mood.lenses.single { it.label == BehaviorAssociationCopy.BEFORE }
        val after = mood.lenses.single { it.label == BehaviorAssociationCopy.AFTER }
        val change = mood.lenses.single { it.label == BehaviorAssociationCopy.CHANGE }
        assertEquals("0 (n=1)", before.present)
        assertEquals("4 (n=1)", before.absent)
        assertEquals("-4", before.difference)
        assertFalse(before.unavailable)
        assertEquals("10 (n=1)", after.present)
        assertEquals("2 (n=1)", after.absent)
        assertEquals("8", after.difference)
        assertEquals("10 (n=1)", change.present)
        assertEquals("-2 (n=1)", change.absent)
        assertEquals("12", change.difference)
        assertFalse(before.difference == after.difference || after.difference == change.difference)
        val energy = profile.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.lenses
            .single { it.label == BehaviorAssociationCopy.AFTER }
        assertEquals("0 (n=1)", energy.present)
        assertNull(energy.absent)
        assertNull(energy.difference)
        assertTrue(energy.unavailable)
        assertEquals("deviation 0, min 0, max 0", energy.presentSpread)
    }

    @Test
    fun anxietyKeepsItsStoredDirection() {
        val anxiety = inspect(axisDataset()).profiles.single().axes
            .single { it.label == "Anxiety" }
        val after = anxiety.lenses.single { it.label == BehaviorAssociationCopy.AFTER }
        assertEquals("8 (n=1)", after.present)
        assertEquals("2 (n=1)", after.absent)
        assertEquals("6", after.difference)
        assertFalse(after.difference!!.startsWith("-"))
    }

    @Test
    fun calendarDaysAndQuantityStayBesidePresence() {
        val profiles = inspect(coverageDataset()).profiles
        val shower = profiles.single { it.identity.kind == BehaviorKind.ACTIVITY }
        assertTrue(shower.quantity is QuantityPresentation.PresenceOnly)
        assertEquals(1, shower.calendar.presentDays)
        assertEquals(1, shower.calendar.absentDays)
        assertTrue(shower.calendar.unknownDays >= 1)
        assertEquals(BehaviorAssociationCopy.DAY_WEIGHT, shower.calendar.dayWeightNote)
        val mood = shower.calendar.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals("5 (n=1)", mood.present)
        assertEquals("4 (n=1)", mood.absent)
        assertEquals("1", mood.difference)
        val energy = shower.calendar.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }
        assertNull(energy.present)
        assertTrue(energy.unavailable)
        assertEquals("Shower · Activity", shower.selectorLabel)
        val workout = profiles.single { it.identity.kind == BehaviorKind.WORKOUT }
        val minutes = workout.quantity as QuantityPresentation.Workout
        assertEquals(1, minutes.intervals)
        assertEquals(2, minutes.sessions)
        assertEquals("30 (n=2)", minutes.minutes)
        val usage = profiles.single { it.identity.kind == BehaviorKind.USAGE && it.intentLabel == "Coping" }
        val amounts = usage.quantity as QuantityPresentation.Usage
        assertEquals(2, amounts.parsedIntervals)
        assertEquals(1, amounts.missingIntervals)
        val byUnit = amounts.units.associateBy { it.unitLabel }
        assertEquals("2 (n=1)", byUnit.getValue("mg").total)
        assertNull(byUnit.getValue("mg").perHour)
        assertEquals("5 (n=2)", byUnit.getValue(BehaviorAssociationCopy.UNITLESS).total)
        assertEquals("2 (n=1)", byUnit.getValue(BehaviorAssociationCopy.UNITLESS).perHour)
        assertEquals("Walk · Workout", workout.selectorLabel)
        assertEquals("caffeine · Usage · Coping", usage.selectorLabel)
    }

    @Test
    fun selectionFollowsIdentityAndDoesNotFollowContrastSize() {
        val profiles = inspect(twoActivities()).profiles
        assertEquals(listOf("option:opt-run", "option:opt-shower"), profiles.map { it.identityKey })
        val shower = profiles.single { it.identityKey == "option:opt-shower" }.identity
        val run = profiles.single { it.identityKey == "option:opt-run" }.identity
        assertEquals(run, retainedBehaviorSelection(profiles, null))
        assertEquals(shower, retainedBehaviorSelection(profiles, shower))
        val remaining = inspect(oneActivity("opt-run")).profiles
        assertEquals(run, retainedBehaviorSelection(remaining, shower))
        assertNull(retainedBehaviorSelection(emptyList(), shower))
        val showerDifference = profiles.single { it.identity == shower }.axes
            .single { it.axisId == DefaultCheckInAxes.MOOD }
            .lenses.single { it.label == BehaviorAssociationCopy.AFTER }
            .difference!!.toInt()
        val runDifference = profiles.single { it.identity == run }.axes
            .single { it.axisId == DefaultCheckInAxes.MOOD }
            .lenses.single { it.label == BehaviorAssociationCopy.AFTER }
            .difference!!.toInt()
        assertTrue(showerDifference > runDifference)
        assertEquals(run, profiles.first().identity)
    }

    @Test
    fun choosingABehaviorDoesNotProjectAgain() = runBlocking {
        val fits = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val contents = mutableListOf<BehaviorAssociationContent>()
        val job = launch(Dispatchers.Default) {
            observeBehaviorAssociations(
                snapshots = flow {
                    emit(snapshot(axisDataset()))
                    release.await()
                },
                zone = ZoneOffset.UTC,
                computation = Dispatchers.Default,
            ) { dataset, zone ->
                fits.incrementAndGet()
                projectBehaviorResearch(dataset, zone)
            }.collect { contents += it }
        }
        withTimeout(5_000) { while (fits.get() < 1) delay(20) }
        val content = contents.single()
        val selected = content.profiles.single().identity
        repeat(3) {
            assertEquals(selected, retainedBehaviorSelection(content.profiles, selected))
            assertEquals(BehaviorAssociationCopy.CONTEXT_NOTICE, content.profiles.single().context.notice)
            assertEquals(1, content.profiles.single().episodes.episodeCount)
            assertNull(content.profiles.single().episodes.presenceContrastNote)
        }
        delay(40)
        assertEquals(1, fits.get())
        release.complete(Unit)
        job.cancel()
    }

    @Test
    fun theSuppliedZoneIsTheCalendarZone() = runBlocking {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val seen = mutableListOf<ZoneId>()
        withTimeout(5_000) {
            observeBehaviorAssociations(
                snapshots = flowOf(snapshot(zoneDataset())),
                zone = losAngeles,
                computation = Dispatchers.Default,
            ) { dataset, zone ->
                seen += zone
                projectBehaviorResearch(dataset, zone)
            }.first()
        }
        assertEquals(listOf(losAngeles), seen)
        val utcDays = projectBehavioralEvidence(zoneDataset(), ZoneOffset.UTC).calendarDays.map { it.date }
        val localDays = projectBehavioralEvidence(zoneDataset(), losAngeles).calendarDays.map { it.date }
        assertTrue(utcDays.contains(LocalDate.of(2026, 1, 15)))
        assertTrue(localDays.contains(LocalDate.of(2026, 1, 14)))
        assertFalse(localDays.contains(LocalDate.of(2026, 1, 15)))
    }

    @Test
    fun equalSnapshotsProjectOnceAndAChangeProjectsAgain() = runBlocking {
        val first = snapshot(axisDataset())
        val second = snapshot(dataset(emptyList()))
        val fits = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            observeBehaviorAssociations(
                snapshots = flow {
                    emit(first)
                    release.await()
                    emit(first)
                    emit(second)
                },
                zone = ZoneOffset.UTC,
                computation = Dispatchers.Default,
            ) { dataset, zone ->
                fits.incrementAndGet()
                projectBehaviorResearch(dataset, zone)
            }.collect { }
        }
        withTimeout(5_000) { while (fits.get() < 1) delay(20) }
        delay(40)
        assertEquals(1, fits.get())
        release.complete(Unit)
        withTimeout(5_000) { while (fits.get() < 2) delay(20) }
        assertEquals(2, fits.get())
        job.cancel()
    }

    @Test
    fun projectionRunsOffTheCallerAndKeepsOnlyTheLatestResult() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "behavior-association") }
        try {
            val names = mutableListOf<String>()
            withTimeout(5_000) {
                observeBehaviorAssociations(
                    snapshots = flowOf(snapshot(axisDataset())),
                    zone = ZoneOffset.UTC,
                    computation = executor.asCoroutineDispatcher(),
                ) { dataset, zone ->
                    names += Thread.currentThread().name
                    projectBehaviorResearch(dataset, zone)
                }.first()
            }
            assertTrue(names.single().contains("behavior-association"))
        } finally {
            executor.shutdown()
        }

        val started = CompletableDeferred<Unit>()
        val releaseNext = CompletableDeferred<Unit>()
        var slowFinished = false
        val counts = mutableListOf<Int>()
        val job = launch(Dispatchers.Default) {
            observeBehaviorAssociations(
                snapshots = flow {
                    emit(snapshot(dataset(emptyList())))
                    releaseNext.await()
                    emit(snapshot(oneActivity("opt-shower")))
                },
                zone = ZoneOffset.UTC,
                computation = Dispatchers.Default,
            ) { dataset, zone ->
                if (dataset.states.isEmpty()) {
                    started.complete(Unit)
                    delay(30_000)
                    slowFinished = true
                }
                projectBehaviorResearch(dataset, zone)
            }.collect { counts += it.behaviorCount }
        }
        withTimeout(5_000) { started.await() }
        releaseNext.complete(Unit)
        withTimeout(5_000) { while (counts.none { it == 1 }) delay(20) }
        assertFalse(slowFinished)
        assertFalse(counts.contains(0))
        job.cancel()
    }

    @Test
    fun researchPanesAndInsightsStayOnTheirOwnSubscriptions() {
        val screen = source(
            "src/main/java/com/orbit/wellness/ui/insights/ResearchPreviewScreen.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/ResearchPreviewScreen.kt",
        ).readText()
        val statePane = screen.substringAfter("fun StateMapResearchPane").substringBefore("fun AssociationResearchPane")
        val associationPane = screen.substringAfter("fun AssociationResearchPane")
        assertTrue(statePane.contains("stateMapViewModel.uiState.collectAsState()"))
        assertFalse(statePane.contains("associationViewModel"))
        assertFalse(statePane.contains("observeBehaviorAssociations"))
        assertTrue(associationPane.contains("associationViewModel.uiState.collectAsState()"))
        assertFalse(associationPane.contains("stateMapViewModel"))
        assertFalse(associationPane.contains("observeInsightsAnalysis"))
        val associationViewModel = source(
            "src/main/java/com/orbit/wellness/ui/insights/BehaviorAssociationViewModel.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/BehaviorAssociationViewModel.kt",
        ).readText()
        val projected = associationViewModel.indexOf("observeBehaviorAssociations")
        val selected = associationViewModel.indexOf("combine(presentation, selectedIdentity)")
        assertTrue(projected >= 0 && selected > projected)
        assertFalse(associationViewModel.contains("observeInsightsAnalysis"))
        assertFalse(associationViewModel.contains("projectStateMap"))
        val stateMapViewModel = source(
            "src/main/java/com/orbit/wellness/ui/insights/ResearchPreviewViewModel.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/ResearchPreviewViewModel.kt",
        ).readText()
        assertTrue(stateMapViewModel.contains("observeInsightsAnalysis"))
        assertFalse(stateMapViewModel.contains("observeBehaviorAssociations"))
        assertFalse(stateMapViewModel.contains("projectBehaviorAssociations"))
        val insights = source(
            "src/main/java/com/orbit/wellness/ui/insights/InsightsViewModel.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/InsightsViewModel.kt",
        ).readText()
        val insightsScreen = source(
            "src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
        ).readText()
        assertFalse(insights.contains("observeInsightsAnalysis"))
        assertFalse(insights.contains("observeBehaviorAssociations"))
        assertFalse(insights.contains("projectBehaviorAssociations"))
        assertFalse(insights.contains("projectStateMap"))
        assertFalse(insightsScreen.contains("StateMapSection"))
        assertFalse(insightsScreen.contains("BehaviorAssociationSection"))
        assertFalse(insightsScreen.contains("Context balance"))
        assertFalse(insightsScreen.contains("Exposure episodes"))
        assertFalse(insights.contains("projectBehaviorContextBalance"))
        assertFalse(insights.contains("projectBehaviorExposureEpisodes"))
        assertNull(com.orbit.wellness.ui.navigation.OrbitNavigation.researchPreviewRoute(debugBuild = false))
        val nav = source(
            "src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
            "app/src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
        ).readText()
        assertFalse(nav.substringBefore("if (BuildConfig.DEBUG)").contains("BehaviorAssociationViewModel.Factory"))
        val debug = nav.substringAfter("if (BuildConfig.DEBUG)")
        assertTrue(debug.contains("OrbitNavigation.RESEARCH_PREVIEW"))
        assertTrue(debug.contains("BehaviorAssociationViewModel"))
        assertTrue(debug.contains("ZoneId.systemDefault()"))
        assertFalse(debug.contains("behavior_associations"))
    }

    @Test
    fun theInspectorDoesNotWriteRoomOrRankADifference() {
        val files = listOf(
            "ui/insights/BehaviorAssociationContent.kt",
            "ui/insights/BehaviorAssociationInspection.kt",
            "ui/insights/BehaviorAssociationSection.kt",
            "ui/insights/BehaviorAssociationViewModel.kt",
        ).map { name ->
            source("src/main/java/com/orbit/wellness/$name", "app/src/main/java/com/orbit/wellness/$name").readText()
        }
        files.forEach { text ->
            assertFalse(text.contains("Dao"))
            assertFalse(text.contains("RoomDatabase"))
            assertFalse(text.contains(".insert"))
            assertFalse(text.contains("projectStateMap"))
            assertFalse(text.contains("fitRetrospective"))
            assertFalse(text.contains("pValue"))
            assertFalse(text.contains("significant"))
            assertFalse(text.contains("recommend"))
            assertFalse(text.contains("Color.Red"))
            assertFalse(text.contains("Color.Green"))
        }
        val fields = BehaviorAssociationProfileView::class.java.declaredFields.map { it.name }
        listOf("rank", "recommend", "predict", "effect", "pvalue", "coordinate").forEach { piece ->
            assertTrue(fields.none { it.contains(piece, ignoreCase = true) })
        }
        assertEquals(
            "Descriptive associations from recorded history; not causal or predictive.",
            BehaviorAssociationCopy.NOTICE,
        )
        val section = source(
            "src/main/java/com/orbit/wellness/ui/insights/BehaviorAssociationSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/BehaviorAssociationSection.kt",
        ).readText()
        assertTrue(section.contains("BehaviorAssociationCopy.NOT_ENOUGH_DATA"))
        assertTrue(section.contains("var showContext by remember(profile.identity)"))
        assertTrue(section.contains("var showEpisodes by remember(profile.identity)"))
        assertTrue(section.contains("BehaviorAssociationCopy.CONTEXT_BALANCE"))
        assertTrue(section.contains("BehaviorAssociationCopy.EXPOSURE_EPISODES"))
        assertFalse(section.contains("observeBehaviorAssociations"))
        assertFalse(section.contains("projectBehaviorContextBalance"))
        assertFalse(section.contains("projectBehaviorExposureEpisodes"))
        val contextFields = ContextBalancePresentation::class.java.declaredFields.map { it.name }
        assertTrue(contextFields.none { it.contains("pair", ignoreCase = true) })
    }

    private fun inspect(dataset: CheckInAnalysisDatasetV2): BehaviorAssociationContent {
        val research = projectBehaviorResearch(dataset, ZoneOffset.UTC)
        return research.associations.toBehaviorAssociationContent(research.balance, research.episodes)
    }

    private fun axisDataset(): CheckInAnalysisDatasetV2 {
        val start = 40L * DAY
        return dataset(
            states = listOf(
                state("p-start", start, mood = 0, anxiety = 2),
                state("present", start + 2 * HOUR, mood = 10, energy = 0, anxiety = 8),
                state("a-start", start + 3 * HOUR, mood = 4, anxiety = 1),
                state("absent", start + 4 * HOUR, mood = 2, anxiety = 2),
            ),
            intervals = listOf(
                interval(
                    "present",
                    "p-start",
                    start,
                    start + 2 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower", "Shower")),
                ),
                interval(
                    "absent",
                    "a-start",
                    start + 3 * HOUR,
                    start + 4 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                ),
            ),
        )
    }

    private fun twoActivities(): CheckInAnalysisDatasetV2 {
        val start = 60L * DAY
        return dataset(
            states = listOf(
                state("run-end", start + 2 * HOUR, mood = 1),
                state("shower-end", start + 4 * HOUR, mood = 10),
            ),
            intervals = listOf(
                interval(
                    "run-end",
                    null,
                    start,
                    start + 2 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("run", "opt-run", "Run")),
                ),
                interval(
                    "shower-end",
                    null,
                    start + 2 * HOUR,
                    start + 4 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower", "Shower")),
                ),
            ),
        )
    }

    private fun oneActivity(optionId: String) = dataset(
        states = listOf(state("end", HOUR, mood = 3)),
        intervals = listOf(
            interval(
                "end",
                null,
                0L,
                HOUR,
                CheckInContextStartBasis.WAKE_TIME,
                activity = CategoryObservationKindV2.ENTRIES,
                activities = listOf(activity(optionId, optionId, optionId)),
            ),
        ),
    )

    private fun coverageDataset(): CheckInAnalysisDatasetV2 {
        val zone = ZoneOffset.UTC
        val first = LocalDate.of(2026, 1, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val second = LocalDate.of(2026, 1, 16).atStartOfDay(zone).toInstant().toEpochMilli()
        val third = LocalDate.of(2026, 1, 17).atStartOfDay(zone).toInstant().toEpochMilli()
        return dataset(
            states = listOf(
                state("early", first + 10 * HOUR, mood = 0),
                state("later", first + 14 * HOUR, mood = 10),
                state("quiet", second + 10 * HOUR, mood = 4),
                state("open", third + 10 * HOUR, mood = 2),
                state("workout", first + 16 * HOUR),
                state("parsed", first + 18 * HOUR),
                state("malformed", first + 19 * HOUR),
                state("unitless", first + 20 * HOUR),
            ),
            intervals = listOf(
                interval(
                    "later",
                    "early",
                    first + 10 * HOUR,
                    first + 14 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower", "Shower")),
                ),
                interval("quiet", null, second + 8 * HOUR, second + 10 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                interval(
                    "open",
                    null,
                    third + 8 * HOUR,
                    third + 12 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    completed = false,
                ),
                interval(
                    "workout",
                    null,
                    first + 15 * HOUR,
                    first + 16 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    workout = CategoryObservationKindV2.ENTRIES,
                    workouts = listOf(
                        workout("w1", "Walk", 20),
                        workout("w2", "Walk", 40),
                    ),
                ),
                interval(
                    "parsed",
                    null,
                    first + 16 * HOUR,
                    first + 18 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    duration = 2 * HOUR,
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(
                        usageItem("caffeine", SubstanceIntent.COPING.name, "2 mg"),
                        usageItem("caffeine", SubstanceIntent.COPING.name, "4"),
                    ),
                ),
                interval(
                    "malformed",
                    null,
                    first + 18 * HOUR,
                    first + 19 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(usageItem("caffeine", SubstanceIntent.COPING.name, "nope")),
                ),
                interval(
                    "unitless",
                    null,
                    first + 20 * HOUR,
                    first + 20 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    duration = 0L,
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(usageItem("caffeine", SubstanceIntent.COPING.name, "6")),
                ),
            ),
        )
    }

    private fun zoneDataset(): CheckInAnalysisDatasetV2 {
        val at = LocalDate.of(2026, 1, 15).atTime(7, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        return dataset(
            states = listOf(state("end", at, mood = 4)),
            intervals = listOf(
                interval(
                    "end",
                    null,
                    at - HOUR,
                    at,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower", "Shower")),
                ),
            ),
        )
    }

    private fun snapshot(dataset: CheckInAnalysisDatasetV2) = CheckInAnalysisSnapshot(
        dataset = dataset,
        summary = summarizeCheckInAnalysis(dataset),
    )

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2> = emptyList(),
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun state(
        id: String,
        at: Long,
        mood: Int? = null,
        energy: Int? = null,
        anxiety: Int? = null,
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
            DefaultCheckInAxes.ANXIETY to anxiety,
        ).mapNotNull { (axis, raw) -> reading(axis, raw) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                null,
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ANXIETY },
            ),
        )
    }

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
        completed: Boolean = true,
        duration: Long = endAt - start,
        activity: CategoryObservationKindV2 = CategoryObservationKindV2.NONE,
        activities: List<ActivityObservationV2> = emptyList(),
        workout: CategoryObservationKindV2 = CategoryObservationKindV2.NONE,
        workouts: List<WorkoutObservationV2> = emptyList(),
        usage: CategoryObservationKindV2 = CategoryObservationKindV2.NONE,
        usageItems: List<UsageObservationV2> = emptyList(),
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = duration,
        startBasis = basis,
        completedAt = if (completed) endAt else null,
        activity = ActivityCategoryObservationV2(activity, if (completed) endAt else null, activities),
        workout = WorkoutCategoryObservationV2(workout, if (completed) endAt else null, workouts),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, if (completed) endAt else null, emptyList()),
        usage = UsageCategoryObservationV2(usage, if (completed) endAt else null, usageItems),
    )

    private fun activity(id: String, optionId: String, name: String) = ActivityObservationV2(id, optionId, name, 0L)

    private fun workout(id: String, name: String, minutes: Int) = WorkoutObservationV2(
        id = id,
        optionId = null,
        name = name,
        durationMinutes = minutes,
        reportedAt = 0L,
    )

    private fun usageItem(optionId: String, intent: String, amount: String) = UsageObservationV2(
        id = "$optionId-$intent-$amount",
        optionId = optionId,
        name = optionId,
        intent = intent,
        amountText = amount,
        reportedAt = 0L,
    )

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24L * HOUR
    }
}
