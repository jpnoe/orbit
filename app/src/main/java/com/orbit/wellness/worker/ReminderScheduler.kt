package com.orbit.wellness.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.orbit.wellness.data.ReminderPreferences
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    private const val WORK_NAME = "daily_check_in_reminder"

    fun scheduleDailyReminder(context: Context, time: LocalTime = ReminderPreferences.DEFAULT_TIME) {
        enqueueNextReminder(context, calculateDelayToNextReminder(time))
    }

    fun enqueueNextReminder(context: Context, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<CheckInReminderWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun calculateDelayToNextReminder(
        time: LocalTime = ReminderPreferences.DEFAULT_TIME,
    ): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = now.withHour(time.hour)
            .withMinute(time.minute)
            .withSecond(0)
            .withNano(0)

        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }

        val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
        val targetMillis = target.atZone(zone).toInstant().toEpochMilli()
        return targetMillis - nowMillis
    }
}
