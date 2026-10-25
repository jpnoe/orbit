package com.orbit.wellness.data.sharing

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.orbit.wellness.data.local.ShareEvent
import com.orbit.wellness.data.local.ShareEventDao
import com.orbit.wellness.data.local.StatusRequest
import com.orbit.wellness.data.local.StatusRequestDao
import com.orbit.wellness.data.local.TrustedContact
import com.orbit.wellness.data.local.TrustedContactDao
import com.orbit.wellness.util.PhoneNumberUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class SharingRepository(
    private val context: Context,
    private val contactDao: TrustedContactDao,
    private val statusRequestDao: StatusRequestDao,
    private val shareEventDao: ShareEventDao,
    private val summaryBuilder: StatusSummaryBuilder,
    private val inboundAudit: InboundSmsAudit,
) {
    fun observeContacts(): Flow<List<TrustedContact>> = contactDao.observeAll()

    fun observePendingRequests(): Flow<List<StatusRequest>> = statusRequestDao.observePending()

    fun observeRecentShares(limit: Int = 20): Flow<List<ShareEvent>> = shareEventDao.observeRecent(limit)

    fun observeLastInboundAudit(): StateFlow<String?> = inboundAudit.lastError

    fun recordInboundFailure(detail: String) {
        inboundAudit.recordError("Error handling inbound SMS: $detail")
    }

    suspend fun getContact(id: String): TrustedContact? = contactDao.getById(id)

    suspend fun getStatusRequest(id: String): StatusRequest? = statusRequestDao.getById(id)

    suspend fun getLatestPendingRequest(): StatusRequest? = statusRequestDao.getLatestPending()

    suspend fun addContact(
        contactName: String,
        phoneRaw: String,
        triggerPhrase: String = TrustedContact.DEFAULT_TRIGGER,
    ): Result<TrustedContact> {
        val name = contactName.trim()
        val phone = PhoneNumberUtils.normalize(phoneRaw)
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("Enter a contact name."))
        if (phone.length < 10) {
            return Result.failure(IllegalArgumentException("Enter a valid phone number."))
        }
        val contact = TrustedContact(
            contactName = name,
            phoneE164 = phone,
            triggerPhrase = triggerPhrase.trim().ifBlank { TrustedContact.DEFAULT_TRIGGER },
        )
        return try {
            contactDao.insert(contact)
            Result.success(contact)
        } catch (error: Exception) {
            Result.failure(IllegalStateException("That phone number is already saved.", error))
        }
    }

    suspend fun updateContact(contact: TrustedContact) {
        contactDao.update(contact)
    }

    suspend fun deleteContact(id: String) {
        contactDao.deleteById(id)
    }

    /**
     * Manually send (or queue for review) a status update to [contact].
     * Inbound SMS trigger matching is disabled — this is the supported share path.
     */
    suspend fun sendUpdate(
        contact: TrustedContact,
        onNeedsManualApproval: suspend (StatusRequest) -> Unit,
    ): Result<Unit> {
        if (!contact.enabled) {
            return Result.failure(IllegalStateException("Enable this contact before sending."))
        }
        val summary = summaryBuilder.build(contact)
        val modulesCsv = summary.modulesShared.joinToString(",")

        if (contact.autoApprove) {
            return try {
                sendSms(contact.phoneE164, summary.smsChunks)
                logShare(
                    contact = contact,
                    modulesShared = modulesCsv,
                    body = SmsChunkCodec.forDisplay(summary.body),
                    autoApproved = true,
                )
                inboundAudit.clear()
                Result.success(Unit)
            } catch (error: Exception) {
                val message =
                    "Couldn't send status SMS to ${contact.contactName}: " +
                        (error.message ?: error.javaClass.simpleName)
                Log.e(TAG, message, error)
                inboundAudit.recordError(message)
                Result.failure(error)
            }
        }

        val request = StatusRequest(
            contactId = contact.id,
            contactName = contact.contactName,
            phoneE164 = contact.phoneE164,
            generatedBody = summary.body,
            modulesShared = modulesCsv,
        )
        statusRequestDao.insert(request)
        onNeedsManualApproval(request)
        inboundAudit.clear()
        return Result.success(Unit)
    }

    /**
     * Inbound SMS triggers are disabled. Status shares go through [sendUpdate] only.
     */
    suspend fun handleInboundSms(
        @Suppress("UNUSED_PARAMETER") senderRaw: String,
        @Suppress("UNUSED_PARAMETER") body: String,
        @Suppress("UNUSED_PARAMETER") onNeedsManualApproval: suspend (StatusRequest) -> Unit,
    ): Boolean {
        Log.i(TAG, "Ignoring inbound SMS (triggers disabled).")
        return false
    }

    suspend fun approveRequest(requestId: String): Result<Unit> {
        val request = statusRequestDao.getById(requestId)
            ?: return Result.failure(IllegalStateException("Request not found."))
        if (request.resolved) {
            return Result.failure(IllegalStateException("Request already handled."))
        }
        return try {
            sendSms(request.phoneE164, SmsChunkCodec.decode(request.generatedBody))
            statusRequestDao.update(request.copy(resolved = true))
            logShare(
                contactId = request.contactId,
                contactName = request.contactName,
                phoneE164 = request.phoneE164,
                modulesShared = request.modulesShared,
                body = SmsChunkCodec.forDisplay(request.generatedBody),
                autoApproved = false,
            )
            inboundAudit.clear()
            Result.success(Unit)
        } catch (error: Exception) {
            val message = "Couldn't send status SMS to ${request.contactName}: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, message, error)
            inboundAudit.recordError(message)
            Result.failure(error)
        }
    }

    suspend fun denyRequest(requestId: String): Result<Unit> {
        val request = statusRequestDao.getById(requestId)
            ?: return Result.failure(IllegalStateException("Request not found."))
        statusRequestDao.update(request.copy(resolved = true))
        inboundAudit.clear()
        return Result.success(Unit)
    }

    private fun getSmsManager(): SmsManager {
        val subId = try {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } catch (_: SecurityException) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }

        return if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private suspend fun sendSms(phoneE164: String, chunks: List<String>) {
        val destination = if (phoneE164.startsWith("+")) phoneE164 else "+$phoneE164"
        try {
            val manager = getSmsManager()
            chunks.forEachIndexed { index, chunk ->
                if (index > 0) {
                    delay(1500)
                }
                val parts = manager.divideMessage(chunk)
                if (parts.size <= 1) {
                    manager.sendTextMessage(destination, null, chunk, null, null)
                } else {
                    manager.sendMultipartTextMessage(destination, null, parts, null, null)
                }
            }
        } catch (error: SecurityException) {
            throw IllegalStateException(
                "SMS send blocked by the system (often dual-SIM). " +
                    (error.message ?: "SecurityException"),
                error,
            )
        }
    }

    private suspend fun logShare(
        contact: TrustedContact,
        modulesShared: String,
        body: String,
        autoApproved: Boolean,
    ) {
        logShare(
            contactId = contact.id,
            contactName = contact.contactName,
            phoneE164 = contact.phoneE164,
            modulesShared = modulesShared,
            body = body,
            autoApproved = autoApproved,
        )
    }

    private suspend fun logShare(
        contactId: String,
        contactName: String,
        phoneE164: String,
        modulesShared: String,
        body: String,
        autoApproved: Boolean,
    ) {
        shareEventDao.insert(
            ShareEvent(
                contactId = contactId,
                contactName = contactName,
                phoneE164 = phoneE164,
                modulesShared = modulesShared,
                bodyPreview = body.take(120),
                autoApproved = autoApproved,
            ),
        )
    }

    companion object {
        private const val TAG = "OrbitSms"
    }
}
