package com.orbit.wellness.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.orbit.wellness.MainActivity
import com.orbit.wellness.R
import com.orbit.wellness.data.local.StatusRequest
import com.orbit.wellness.data.sharing.SmsChunkCodec

object NotificationHelper {
    const val CHANNEL_ID = "orbit_daily_reminder"
    const val STATUS_REQUEST_CHANNEL_ID = "orbit_status_requests"
    const val NOTIFICATION_ID = 1001
    const val STATUS_REQUEST_NOTIFICATION_ID = 1002
    const val EXTRA_NAVIGATE_TO_CHECK_IN = "navigate_to_check_in"
    const val EXTRA_START_DAILY_GUIDE = "start_daily_guide"
    const val EXTRA_STATUS_REQUEST_ID = "status_request_id"

    fun createReminderChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val reminder = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(reminder)

        val status = NotificationChannel(
            STATUS_REQUEST_CHANNEL_ID,
            context.getString(R.string.status_request_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.status_request_channel_description)
        }
        manager.createNotificationChannel(status)
    }

    fun showCheckInReminder(context: Context) {
        createReminderChannel(context)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_START_DAILY_GUIDE, true)
            putExtra(EXTRA_NAVIGATE_TO_CHECK_IN, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun showStatusRequest(context: Context, request: StatusRequest) {
        createReminderChannel(context)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_STATUS_REQUEST_ID, request.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            request.id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, STATUS_REQUEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(R.string.status_request_notification_title, request.contactName),
            )
            .setContentText(context.getString(R.string.status_request_notification_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(SmsChunkCodec.forDisplay(request.generatedBody)),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        NotificationManagerCompat.from(context).notify(
            STATUS_REQUEST_NOTIFICATION_ID + request.id.hashCode().and(0xFF),
            notification,
        )
    }

    fun notificationPermission(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    }
}
