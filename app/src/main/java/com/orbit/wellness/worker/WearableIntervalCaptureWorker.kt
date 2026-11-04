package com.orbit.wellness.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.orbit.wellness.OrbitApplication
import com.orbit.wellness.data.WearableCaptureScheduler
import com.orbit.wellness.data.intervalCaptureWorkName
import com.orbit.wellness.data.local.CheckInContextWindow

/**
 * Imports Health Connect summaries already stored by the wearable provider
 * for one saved context interval. It does not ask the device to measure again.
 * An empty result is success: the provider may not have written anything yet.
 */
class WearableIntervalCaptureWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OrbitApplication ?: return Result.success()
        val checkInId = inputData.getString(KEY_CHECK_IN_ID).orEmpty()
        if (checkInId.isBlank()) return Result.success()
        val window = intervalToCapture(app.checkInRepository.getContextWindow(checkInId))
            ?: return Result.success()
        return try {
            app.biometricRepository.captureInterval(window)
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }

    companion object {
        const val KEY_CHECK_IN_ID = "check_in_id"
    }
}

/** A missing or unfinished window is not an interval import. */
internal fun intervalToCapture(window: CheckInContextWindow?): CheckInContextWindow? {
    return window?.takeIf { it.completedAt != null }
}

class WorkManagerWearableCaptureScheduler(
    context: Context,
) : WearableCaptureScheduler {
    private val appContext = context.applicationContext

    override fun scheduleIntervalCapture(checkInId: String) {
        val request = OneTimeWorkRequestBuilder<WearableIntervalCaptureWorker>()
            .setInputData(workDataOf(WearableIntervalCaptureWorker.KEY_CHECK_IN_ID to checkInId))
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            intervalCaptureWorkName(checkInId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
