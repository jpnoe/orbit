package com.orbit.wellness

import android.app.Application
import com.orbit.wellness.data.ActivityRepository
import com.orbit.wellness.data.CurrentMoodStore
import com.orbit.wellness.data.DietRepository
import com.orbit.wellness.data.ExerciseRepository
import com.orbit.wellness.data.HistoryRepository
import com.orbit.wellness.data.JournalRepository
import com.orbit.wellness.data.MoodRepository
import com.orbit.wellness.data.ReminderPreferences
import com.orbit.wellness.data.SleepRepository
import com.orbit.wellness.data.SubstanceRepository
import com.orbit.wellness.data.ThemePreferences
import com.orbit.wellness.data.biometrics.BiometricRepository
import com.orbit.wellness.data.biometrics.HealthConnectReader
import com.orbit.wellness.data.local.DatabaseKeyManager
import com.orbit.wellness.data.local.OrbitDatabase
import com.orbit.wellness.data.sharing.InboundSmsAudit
import com.orbit.wellness.data.sharing.SharingRepository
import com.orbit.wellness.data.sharing.StatusSummaryBuilder
import com.orbit.wellness.domain.state.StateEncoder
import com.orbit.wellness.domain.topology.TopologyHarness
import com.orbit.wellness.domain.topology.TopologyRepository
import com.orbit.wellness.util.NotificationHelper
import com.orbit.wellness.worker.BiometricSyncWorker
import com.orbit.wellness.worker.ReminderScheduler
import com.orbit.wellness.worker.TopologyCompactWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OrbitApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var moodRepository: MoodRepository
        private set
    lateinit var journalRepository: JournalRepository
        private set
    lateinit var activityRepository: ActivityRepository
        private set
    lateinit var dietRepository: DietRepository
        private set
    lateinit var exerciseRepository: ExerciseRepository
        private set
    lateinit var substanceRepository: SubstanceRepository
        private set
    lateinit var sleepRepository: SleepRepository
        private set
    lateinit var historyRepository: HistoryRepository
        private set
    lateinit var sharingRepository: SharingRepository
        private set
    lateinit var currentMoodStore: CurrentMoodStore
        private set
    lateinit var biometricRepository: BiometricRepository
        private set
    lateinit var topologyRepository: TopologyRepository
        private set
    lateinit var topologyHarness: TopologyHarness
        private set
    lateinit var themePreferences: ThemePreferences
        private set
    lateinit var reminderPreferences: ReminderPreferences
        private set

    override fun onCreate() {
        super.onCreate()

        val keyManager = DatabaseKeyManager(this)
        val database = OrbitDatabase.create(this, keyManager)
        currentMoodStore = CurrentMoodStore(this)
        themePreferences = ThemePreferences(this)
        reminderPreferences = ReminderPreferences(this)

        moodRepository = MoodRepository(
            dao = database.moodSampleDao(),
            emotionDao = database.emotionOptionDao(),
        )
        journalRepository = JournalRepository(database.dailyJournalDao())
        activityRepository = ActivityRepository(
            eventDao = database.activityEventDao(),
            optionDao = database.activityOptionDao(),
            moodRepository = moodRepository,
        )
        dietRepository = DietRepository(
            database = database,
            mealDao = database.mealEventDao(),
            mealItemDao = database.mealItemDao(),
            foodOptionDao = database.foodOptionDao(),
        )
        exerciseRepository = ExerciseRepository(
            eventDao = database.workoutEventDao(),
            optionDao = database.workoutOptionDao(),
            moodRepository = moodRepository,
        )
        substanceRepository = SubstanceRepository(
            eventDao = database.substanceUseEventDao(),
            optionDao = database.substanceOptionDao(),
        )
        sleepRepository = SleepRepository(database.sleepEventDao())
        historyRepository = HistoryRepository(
            moodSampleDao = database.moodSampleDao(),
            activityEventDao = database.activityEventDao(),
            mealEventDao = database.mealEventDao(),
            mealItemDao = database.mealItemDao(),
            workoutEventDao = database.workoutEventDao(),
            substanceUseEventDao = database.substanceUseEventDao(),
            sleepEventDao = database.sleepEventDao(),
        )
        val summaryBuilder = StatusSummaryBuilder(
            moodSampleDao = database.moodSampleDao(),
            dailyJournalDao = database.dailyJournalDao(),
            mealEventDao = database.mealEventDao(),
            workoutEventDao = database.workoutEventDao(),
            sleepEventDao = database.sleepEventDao(),
            substanceUseEventDao = database.substanceUseEventDao(),
            activityEventDao = database.activityEventDao(),
        )
        sharingRepository = SharingRepository(
            context = this,
            contactDao = database.trustedContactDao(),
            statusRequestDao = database.statusRequestDao(),
            shareEventDao = database.shareEventDao(),
            summaryBuilder = summaryBuilder,
            inboundAudit = InboundSmsAudit(this),
        )

        val stateEncoder = StateEncoder(
            moodSampleDao = database.moodSampleDao(),
            sleepEventDao = database.sleepEventDao(),
            activityEventDao = database.activityEventDao(),
            workoutEventDao = database.workoutEventDao(),
            substanceUseEventDao = database.substanceUseEventDao(),
            mealEventDao = database.mealEventDao(),
            biometricSnapshotDao = database.biometricSnapshotDao(),
            stateVectorSnapshotDao = database.stateVectorSnapshotDao(),
            currentMoodStore = currentMoodStore,
        )
        topologyRepository = TopologyRepository(
            snapshotDao = database.stateVectorSnapshotDao(),
            nodeDao = database.topologyNodeDao(),
            edgeDao = database.topologyEdgeDao(),
            clusterMetaDao = database.clusterMetaDao(),
            stateDao = database.topologyStateDao(),
        )
        topologyHarness = TopologyHarness(
            snapshotDao = database.stateVectorSnapshotDao(),
            topologyRepository = topologyRepository,
        )
        biometricRepository = BiometricRepository(
            reader = HealthConnectReader(this),
            biometricSnapshotDao = database.biometricSnapshotDao(),
            stateVectorSnapshotDao = database.stateVectorSnapshotDao(),
            stateEncoder = stateEncoder,
            topologyRepository = topologyRepository,
        )

        applicationScope.launch {
            OrbitDatabase.ensureDefaultActivities(database.activityOptionDao())
            OrbitDatabase.ensureDefaultFoods(database.foodOptionDao())
            OrbitDatabase.ensureDefaultSubstances(database.substanceOptionDao())
            OrbitDatabase.ensureDefaultWorkoutTypes(database.workoutOptionDao())
            OrbitDatabase.ensureDefaultEmotions(database.emotionOptionDao())
            moodRepository.getLatestSample()?.let { sample ->
                currentMoodStore.hydrateFromSample(sample.moodScore, sample.recordedAt)
            }
            topologyRepository.warmUp()
        }

        NotificationHelper.createReminderChannel(this)
        applicationScope.launch {
            ReminderScheduler.scheduleDailyReminder(
                this@OrbitApplication,
                reminderPreferences.reminderTime.first(),
            )
        }
        BiometricSyncWorker.schedule(this)
        TopologyCompactWorker.schedule(this)
    }
}
