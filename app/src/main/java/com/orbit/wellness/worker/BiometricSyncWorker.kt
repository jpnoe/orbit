package com.orbit.wellness.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orbit.wellness.OrbitApplication
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Daily fallback import of selected Health Connect summaries.
 * Check-in interval imports are separate one-time work.
 */
class BiometricSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OrbitApplication
            ?: return Result.retry()
        return try {
            app.biometricRepository.sync()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily_wearable_sync"
        const val LEGACY_HOURLY_WORK_NAME = "hourly_biometric_sync"
        val REPEAT_INTERVAL: Duration = Duration.ofDays(1)

        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(LEGACY_HOURLY_WORK_NAME)
            val request = PeriodicWorkRequestBuilder<BiometricSyncWorker>(
                REPEAT_INTERVAL.toDays(),
                TimeUnit.DAYS,
            ).build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
