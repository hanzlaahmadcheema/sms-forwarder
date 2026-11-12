package com.smsforwarder.app.engine.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.engine.service.SmsForwarderService
import com.smsforwarder.app.engine.worker.DeliveryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Boot & Package Update Receiver.
 * Strictly internal (android:exported="false").
 * Handles post-reboot recovery:
 * 1. Restarts persistent SmsForwarderService if enabled.
 * 2. Reconciles tasks stuck in IN_PROGRESS back to PENDING.
 * 3. Enqueues expedited DeliveryWorker to flush overdue tasks.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Timber.i("BootReceiver triggered with action: %s", action)

        // 1. Restart Foreground Service
        try {
            SmsForwarderService.start(context)
            Timber.i("SmsForwarderService revived after boot/update")
        } catch (e: Exception) {
            Timber.e(e, "Failed to restart SmsForwarderService from BootReceiver")
        }

        // 2. Reconcile pending queue and trigger worker
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope.launch {
            try {
                val queueRepo = SmsForwarderApp.instance.deliveryQueueRepository
                val resetCount = queueRepo.resetInProgressTasksToPending()
                Timber.i("Boot reconciliation reset %d tasks from IN_PROGRESS to PENDING", resetCount)

                // 3. Trigger WorkManager to deliver any overdue tasks
                DeliveryWorker.enqueueExpedited(context)
            } catch (t: Throwable) {
                Timber.e(t, "Error during boot queue reconciliation")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
