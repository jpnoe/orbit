package com.orbit.wellness

import android.app.Application
import com.orbit.wellness.data.ActivityRepository
import com.orbit.wellness.data.CheckInAnalysisRepository
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.CurrentMoodStore
import com.orbit.wellness.data.DietRepository
import com.orbit.wellness.data.ExerciseRepository
import com.orbit.wellness.data.HistoryRepository
import com.orbit.wellness.data.JournalRepository
import com.orbit.wellness.data.MoodRepository
import com.orbit.wellness.data.MoodSampleClock
import com.orbit.wellness.data.MoodSampleRemover
import com.orbit.wellness.data.MoodSampleWriter
import com.orbit.wellness.data.RoomDatabaseTransaction
import com.orbit.wellness.data.ReminderPreferences
import com.orbit.wellness.data.SleepRepository
import com.orbit.wellness.data.SubstanceRepository
import com.orbit.wellness.data.ThemePreferences
import com.orbit.wellness.data.WearableObservationRepository
import com.orbit.wellness.data.biometrics.BiometricRepository
import com.orbit.wellness.data.biometrics.HealthConnectReader
import com.orbit.wellness.data.local.DatabaseKeyManager
import com.orbit.wellness.data.local.OrbitDatabase
import com.orbit.wellness.data.sharing.InboundSmsAudit
import com.orbit.wellness.data.sharing.SharingRepository
import com.orbit.wellness.data.sharing.StatusSummaryBuilder
import com.orbit.wellness.util.NotificationHelper
import com.orbit.wellness.worker.BiometricSyncWorker
import com.orbit.wellness.worker.ReminderScheduler
import com.orbit.wellness.worker.TopologyCompactWorker
import com.orbit.wellness.worker.WorkManagerWearableCaptureScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OrbitApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var moodRepository: MoodRepository
        private set
    lateinit var checkInRepository: CheckInRepository
        private set
    lateinit var checkInAnalysisRepository: CheckInAnalysisRepository
        private set
    lateinit var wearableObservationRepository: WearableObservationRepository
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
        checkInAnalysisRepository = CheckInAnalysisRepository(database.checkInDao())
        wearableObservationRepository = WearableObservationRepository(database.wearableObservationDao())
        checkInRepository = CheckInRepository(
            dao = database.checkInDao(),
            moodSamples = MoodSampleWriter { sample -> database.moodSampleDao().insert(sample) },
            moodRemover = MoodSampleRemover { id -> database.moodSampleDao().deleteById(id) },
            transaction = RoomDatabaseTransaction(database),
            moodClock = MoodSampleClock { id, recordedAt, localDate ->
                val sample = database.moodSampleDao().getById(id) ?: return@MoodSampleClock
                database.moodSampleDao().update(
                    sample.copy(recordedAt = recordedAt, localDate = localDate),
                )
            },
            wakeTimes = { localDate ->
                database.sleepEventDao().getForDate(localDate)?.wakeTime
            },
            captures = WorkManagerWearableCaptureScheduler(this),
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
            checkInDao = database.checkInDao(),
            wearableObservationDao = database.wearableObservationDao(),
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

        biometricRepository = BiometricRepository(
            reader = HealthConnectReader(this),
            wearableObservationDao = database.wearableObservationDao(),
        )

        applicationScope.launch {
            OrbitDatabase.ensureDefaultActivities(database.activityOptionDao())
            OrbitDatabase.ensureDefaultFoods(database.foodOptionDao())
            OrbitDatabase.ensureDefaultSubstances(database.substanceOptionDao())
            OrbitDatabase.ensureDefaultWorkoutTypes(database.workoutOptionDao())
            OrbitDatabase.ensureDefaultEmotions(database.emotionOptionDao())
            OrbitDatabase.ensureDefaultCheckInAxes(database.checkInDao())
            moodRepository.getLatestSample()?.let { sample ->
                currentMoodStore.hydrateFromSample(sample.moodScore, sample.recordedAt)
            }
        }

        NotificationHelper.createReminderChannel(this)
        applicationScope.launch {
            ReminderScheduler.scheduleDailyReminder(
                this@OrbitApplication,
                reminderPreferences.reminderTime.first(),
            )
        }
        BiometricSyncWorker.schedule(this)
        TopologyCompactWorker.cancel(this)
    }
}
