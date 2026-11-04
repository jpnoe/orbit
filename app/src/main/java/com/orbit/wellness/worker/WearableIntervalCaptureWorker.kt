package com.orbit.wellness.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.orbit.wellness.OrbitApplication
import com.orbit.wellness.data.WearableCaptureScheduler
import com.orbit.wellness.data.biometrics.IntervalCaptureOutcome
import com.orbit.wellness.data.intervalCaptureWorkName
import com.orbit.wellness.data.local.CheckInContextWindow
import java.util.concurrent.TimeUnit

/**
 * Imports Health Connect summaries already stored by the wearable provider
 * for one saved context interval. It does not ask the device to measure again.
 * An empty or failed read retries inside this same unique request, up to twice.
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
        val outcome = try {
            app.biometricRepository.captureInterval(window)
        } catch (error: Exception) {
            IntervalCaptureOutcome.Failed(error.message ?: "Interval import failed.")
        }
        return intervalCaptureWorkerResult(outcome, runAttemptCount)
    }

    companion object {
        const val KEY_CHECK_IN_ID = "check_in_id"
    }
}

/** A missing or unfinished window is not an interval import. */
internal fun intervalToCapture(window: CheckInContextWindow?): CheckInContextWindow? {
    return window?.takeIf { it.completedAt != null }
}

internal const val INTERVAL_CAPTURE_MAX_RETRIES = 2
internal const val INTERVAL_CAPTURE_BACKOFF_MINUTES = 15L

internal enum class IntervalCaptureAction {
    SUCCEED,
    RETRY,
}

/**
 * [runAttemptCount] is WorkManager's count for this same request. The first
 * run is 0. Empty and failed reads retry while that count is still below
 * [INTERVAL_CAPTURE_MAX_RETRIES]. Unavailable finishes without a retry.
 */
internal fun intervalCaptureAction(
    outcome: IntervalCaptureOutcome,
    runAttemptCount: Int,
): IntervalCaptureAction {
    val retryable = outcome is IntervalCaptureOutcome.Empty || outcome is IntervalCaptureOutcome.Failed
    return if (retryable && runAttemptCount < INTERVAL_CAPTURE_MAX_RETRIES) {
        IntervalCaptureAction.RETRY
    } else {
        IntervalCaptureAction.SUCCEED
    }
}

internal fun intervalCaptureWorkerResult(
    outcome: IntervalCaptureOutcome,
    runAttemptCount: Int,
): androidx.work.ListenableWorker.Result {
    return if (intervalCaptureAction(outcome, runAttemptCount) == IntervalCaptureAction.RETRY) {
        androidx.work.ListenableWorker.Result.retry()
    } else {
        androidx.work.ListenableWorker.Result.success()
    }
}

class WorkManagerWearableCaptureScheduler(
    context: Context,
) : WearableCaptureScheduler {
    private val appContext = context.applicationContext

    override fun scheduleIntervalCapture(checkInId: String) {
        val request = OneTimeWorkRequestBuilder<WearableIntervalCaptureWorker>()
            .setInputData(workDataOf(WearableIntervalCaptureWorker.KEY_CHECK_IN_ID to checkInId))
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                INTERVAL_CAPTURE_BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            intervalCaptureWorkName(checkInId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
