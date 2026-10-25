package com.orbit.wellness.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orbit.wellness.OrbitApplication
import java.util.concurrent.TimeUnit

/**
 * Weekly topology rebuild: HNSW from the 90-day window, core distances,
 * λ = p90, then connected-component splits. Runs while charging and idle.
 */
class TopologyCompactWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OrbitApplication
            ?: return Result.retry()
        return try {
            app.topologyRepository.compact()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "weekly_topology_compact"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TopologyCompactWorker>(
                7,
                TimeUnit.DAYS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
