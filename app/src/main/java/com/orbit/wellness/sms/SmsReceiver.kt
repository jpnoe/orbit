package com.orbit.wellness.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.orbit.wellness.OrbitApplication
import com.orbit.wellness.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages.firstOrNull()?.originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank()) return

        Log.i(TAG, "SMS_RECEIVED broadcast delivered to Orbit")

        val pending = goAsync()
        val app = context.applicationContext as? OrbitApplication
        if (app == null) {
            Log.e(TAG, "Application context was not OrbitApplication")
            pending.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.sharingRepository.handleInboundSms(
                    senderRaw = sender,
                    body = body,
                    onNeedsManualApproval = { request ->
                        NotificationHelper.showStatusRequest(context, request)
                    },
                )
            } catch (error: Exception) {
                Log.e(TAG, "Failed handling inbound SMS", error)
                app.sharingRepository.recordInboundFailure(error.message ?: error.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "OrbitSms"
    }
}
