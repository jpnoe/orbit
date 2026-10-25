package com.orbit.wellness.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orbit.wellness.OrbitApplication
import java.util.concurrent.TimeUnit

/**
 * Hourly Health Connect → Room biometric sync + state-vector encode.
 * Requires HC read permissions (incl. background read when available).
 */
class BiometricSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OrbitApplication
            ?: return Result.retry()
        return try {
            app.biometricRepository.syncAndEncode()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "hourly_biometric_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BiometricSyncWorker>(
                1,
                TimeUnit.HOURS,
            ).build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
