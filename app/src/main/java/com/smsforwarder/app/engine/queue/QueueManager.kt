package com.smsforwarder.app.engine.queue

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.engine.service.SmsForwarderService
import com.smsforwarder.app.engine.worker.DeliveryWorker
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * QueueManager centralizes delivery task lifecycle management, WorkManager scheduling,
 * expedited and periodic background execution, dead-letter monitoring, and manual bulk retries.
 */
object QueueManager {

    const val WORK_NAME_EXPEDITED = "sms_expedited_dispatch_work"
    const val WORK_NAME_PERIODIC = "sms_periodic_dispatch_work"
    const val TAG_DELIVERY_WORK = "sms_delivery_work"

    /**
     * Enqueues an expedited DeliveryWorker job to immediately dispatch pending or due tasks.
     * Requires network connectivity. Falls back to standard execution if expedited quota is exceeded.
     */
    /**
     * Enqueues an expedited DeliveryWorker job to immediately dispatch pending or due tasks.
     * Also triggers direct QueueDrainer and notifies SmsForwarderService to bypass JobScheduler throttling.
     */
    fun enqueueExpedited(context: Context) {
        // 1. Notify persistent foreground service for immediate execution
        SmsForwarderService.notifyNewTasks(context)

        // 2. Direct coroutine trigger on Dispatchers.IO
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            QueueDrainer.drainQueue(context)
        }

        // 3. Fallback WorkManager registration
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(constraints)
                .addTag(TAG_DELIVERY_WORK)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_EXPEDITED,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Timber.i("QueueManager: Enqueued expedited DeliveryWorker with ExistingWorkPolicy.REPLACE")
        } catch (e: Exception) {
            Timber.w(e, "QueueManager: WorkManager expedited enqueue suppressed or throttled by system")
        }
    }

    /**
     * Schedules a periodic fallback DeliveryWorker running every 15 minutes
     * to ensure retryable or deferred tasks are processed even during extended sleep states.
     */
    fun schedulePeriodicWork(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<DeliveryWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES // 5-minute flex window
        )
            .setConstraints(constraints)
            .addTag(TAG_DELIVERY_WORK)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
        Timber.i("QueueManager: Scheduled 15-minute periodic DeliveryWorker fallback")
    }

    /**
     * Immediately flushes the delivery queue. Typically invoked when network connectivity
     * is restored via NetworkConnectivityMonitor or manually triggered by user.
     */
    fun flushQueue(context: Context) {
        Timber.i("QueueManager: Flushing delivery queue via direct QueueDrainer")
        enqueueExpedited(context)
    }

    /**
     * Bulk resets all FAILED_RETRYABLE and FAILED_EXHAUSTED (dead-letter) tasks
     * back to PENDING and triggers an immediate direct drain cycle.
     */
    suspend fun retryAllFailedTasks(context: Context): AppResult<Int> {
        val app = SmsForwarderApp.instance
        val result = app.deliveryQueueRepository.retryAllFailedTasks()
        if (result is AppResult.Success && result.data > 0) {
            Timber.i("QueueManager: Reset %d failed tasks to PENDING. Triggering dispatch...", result.data)
            enqueueExpedited(context)
        }
        // Always trigger a drain in case there are pending tasks with 0 attempts
        QueueDrainer.drainQueue(context)
        return result
    }

    /**
     * Cancels all scheduled or active delivery work.
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_DELIVERY_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_EXPEDITED)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        Timber.i("QueueManager: Cancelled all delivery work")
    }
}
