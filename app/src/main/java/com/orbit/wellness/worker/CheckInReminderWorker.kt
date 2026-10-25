package com.orbit.wellness.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orbit.wellness.data.ReminderPreferences
import com.orbit.wellness.util.NotificationHelper
import kotlinx.coroutines.flow.first

class CheckInReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        NotificationHelper.showCheckInReminder(applicationContext)
        val time = ReminderPreferences(applicationContext).reminderTime.first()
        ReminderScheduler.enqueueNextReminder(
            applicationContext,
            ReminderScheduler.calculateDelayToNextReminder(time),
        )
        return Result.success()
    }
}
