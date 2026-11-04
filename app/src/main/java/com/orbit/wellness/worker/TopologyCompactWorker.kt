package com.orbit.wellness.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Retained so a previously enqueued compaction job can be cancelled.
 * [doWork] does not compact, ingest, or write. Startup calls [cancel].
 */
class TopologyCompactWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = Result.success()

    companion object {
        const val UNIQUE_WORK_NAME = "weekly_topology_compact"

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
