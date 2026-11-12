package com.smsforwarder.app.engine.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.core.util.ContactHelper
import com.smsforwarder.app.domain.model.SimInfo
import com.smsforwarder.app.engine.service.SmsForwarderService
import com.smsforwarder.app.engine.worker.DeliveryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * High-priority (999) BroadcastReceiver intercepting incoming SMS broadcasts.
 * Protected by android.permission.BROADCAST_SMS to prevent intent spoofing from third-party apps.
 * Utilizes goAsync() with Dispatchers.IO to perform multipart reassembly, dual-SIM extraction,
 * Room persistence, rule matching, and queue enrollment.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        Timber.i("SmsReceiver intercepted SMS_RECEIVED broadcast")

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope.launch {
            try {
                // 1. Reassemble multi-part PDUs
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) {
                    Timber.w("SMS_RECEIVED intent contained no SMS PDUs")
                    return@launch
                }

                val fullBody = StringBuilder()
                var originatingAddress: String? = null
                var timestampMillis = 0L

                for (msg in messages) {
                    fullBody.append(msg.displayMessageBody ?: msg.messageBody ?: "")
                    if (originatingAddress == null) {
                        originatingAddress = msg.displayOriginatingAddress ?: msg.originatingAddress
                        timestampMillis = msg.timestampMillis
                    }
                }

                val sender = originatingAddress ?: "UNKNOWN"
                val body = fullBody.toString()

                if (body.isEmpty()) {
                    Timber.w("SMS_RECEIVED body is empty after reassembly")
                    return@launch
                }

                // Broadcast-level deduplication guard (prevents dual-SIM or multi-PDU broadcast duplicate firing)
                val broadcastKey = "$sender:$body"
                val now = System.currentTimeMillis()
                synchronized(seenBroadcasts) {
                    val lastSeen = seenBroadcasts[broadcastKey]
                    if (lastSeen != null && (now - lastSeen) < 60_000L) {
                        Timber.w("SmsReceiver: duplicate broadcast intercepted and dropped for sender %s", sender)
                        return@launch
                    }
                    seenBroadcasts[broadcastKey] = now
                    if (seenBroadcasts.size > 200) {
                        val cutoff = now - 60_000L
                        seenBroadcasts.entries.removeIf { it.value < cutoff }
                    }
                }

                // 2. Extract Dual-SIM metadata
                val simInfo = extractSimMetadata(context, intent)
                Timber.i(
                    "SMS reassembled: simSlotIndex=%d, subId=%d, carrier=%s",
                    simInfo.simSlotIndex,
                    simInfo.subId,
                    simInfo.carrierName
                )

                // 3. Resolve Contact Name via local ContentResolver
                val contactName = ContactHelper.resolveContactName(context, sender)
                Timber.i("Resolved contact name for %s: %s", sender, contactName)

                // 4. Ingest and deduplicate in Room DB
                val app = SmsForwarderApp.instance
                val receiveResult = app.receiveSmsUseCase(
                    sender = sender,
                    body = body,
                    timestampMillis = timestampMillis,
                    simInfo = simInfo,
                    contactName = contactName
                )

                if (receiveResult is AppResult.Success) {
                    val sms = receiveResult.data
                    // 4. Evaluate forwarding rules
                    val matchingRules = app.filterSmsUseCase(sms)
                    if (matchingRules.isNotEmpty()) {
                        // 5. Create delivery tasks in queue
                        val enqueueResult = app.enqueueForwardingUseCase(sms, matchingRules)
                        if (enqueueResult is AppResult.Success) {
                            // 6. Trigger a single expedited dispatch cycle
                            com.smsforwarder.app.engine.queue.QueueManager.enqueueExpedited(context)
                        }
                    } else {
                        Timber.i("SMS %s matched zero forwarding rules", sms.id)
                    }
                } else if (receiveResult is AppResult.Failure) {
                    Timber.w("SMS ingestion rejected: %s", receiveResult.error.message)
                }

            } catch (t: Throwable) {
                Timber.e(t, "Unexpected error in SmsReceiver async processing")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun extractSimMetadata(context: Context, intent: Intent): SimInfo {
        var subId = -1
        var slotIndex = -1
        var carrierName: String? = null

        // 1. Check intent extras for subscription ID
        if (intent.hasExtra("subscription")) {
            subId = intent.getIntExtra("subscription", -1)
        } else if (intent.hasExtra("android.telephony.extra.SUBSCRIPTION_INDEX")) {
            subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
        } else if (intent.hasExtra("sub_id")) {
            subId = intent.getIntExtra("sub_id", -1)
        }

        // 2. Check intent extras for SIM slot index
        if (intent.hasExtra("simSlot")) {
            slotIndex = intent.getIntExtra("simSlot", -1)
        } else if (intent.hasExtra("slot")) {
            slotIndex = intent.getIntExtra("slot", -1)
        } else if (intent.hasExtra("android.telephony.extra.SLOT_INDEX")) {
            slotIndex = intent.getIntExtra("android.telephony.extra.SLOT_INDEX", -1)
        }

        // 3. Query SubscriptionManager if permission is granted
        val hasPhonePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPhonePermission) {
            try {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                if (subManager != null) {
                    if (subId != -1) {
                        val subInfo: SubscriptionInfo? = subManager.getActiveSubscriptionInfo(subId)
                        if (subInfo != null) {
                            if (slotIndex == -1) slotIndex = subInfo.simSlotIndex
                            carrierName = subInfo.displayName?.toString() ?: subInfo.carrierName?.toString()
                        }
                    } else if (slotIndex != -1) {
                        val subInfo: SubscriptionInfo? = subManager.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
                        if (subInfo != null) {
                            subId = subInfo.subscriptionId
                            carrierName = subInfo.displayName?.toString() ?: subInfo.carrierName?.toString()
                        }
                    } else {
                        // Default to active subscription list
                        val activeList = subManager.activeSubscriptionInfoList
                        if (!activeList.isNullOrEmpty()) {
                            val defaultInfo = activeList.first()
                            subId = defaultInfo.subscriptionId
                            slotIndex = defaultInfo.simSlotIndex
                            carrierName = defaultInfo.displayName?.toString() ?: defaultInfo.carrierName?.toString()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to resolve SIM metadata from SubscriptionManager")
            }
        }

        return SimInfo(
            simSlotIndex = slotIndex,
            subId = subId,
            carrierName = carrierName
        )
    }

    companion object {
        private val seenBroadcasts = mutableMapOf<String, Long>()

        fun resetSeenBroadcastsForTesting() {
            synchronized(seenBroadcasts) {
                seenBroadcasts.clear()
            }
        }
    }
}
