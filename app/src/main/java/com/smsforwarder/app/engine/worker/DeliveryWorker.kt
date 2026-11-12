package com.smsforwarder.app.engine.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.core.util.SecurityLogTree
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.TaskStatus
import com.smsforwarder.app.engine.dispatcher.DispatchResult
import com.smsforwarder.app.engine.queue.QueueDrainer
import com.smsforwarder.app.engine.service.SmsForwarderService
import timber.log.Timber
import kotlin.random.Random

/**
 * Expedited CoroutineWorker that dispatches pending and due retry delivery tasks
 * across Webhook, Telegram, and native SSLSocket SMTP protocol clients.
 * Manages task state machine transitions, exponential backoff with full jitter,
 * credential resolution via SecureVault, and logcat redaction.
 */
class DeliveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.i("DeliveryWorker initiated outbound dispatch cycle")
        return try {
            QueueDrainer.drainQueue(applicationContext)
            val app = SmsForwarderApp.instance
            val dueTasks = app.deliveryQueueRepository.getDueRetryTasks(System.currentTimeMillis())
            if (dueTasks.isNotEmpty()) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Timber.e(e, "DeliveryWorker error during queue drain")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME_EXPEDITED_DISPATCH = "sms_expedited_dispatch_work"
        const val MAX_RETRY_ATTEMPTS = 5
        private const val BASE_BACKOFF_SECONDS = 30L
        private const val MAX_BACKOFF_SECONDS = 1800L // 30 minutes

        /**
         * Calculates exponential backoff delay with full jitter:
         * CappedDelay = min(MaxDelay, BaseDelay * 2^attemptCount)
         * JitteredDelay = UniformRandom(CappedDelay / 2, CappedDelay)
         * Respects remote retryAfterSeconds if provided.
         */
        fun calculateExponentialBackoffMs(attemptCount: Int, retryAfterSeconds: Long? = null): Long {
            val exponent = (attemptCount - 1).coerceAtLeast(0).coerceAtMost(10)
            val calculatedDelaySec = (BASE_BACKOFF_SECONDS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_SECONDS)
            val minDelaySec = (calculatedDelaySec / 2).coerceAtLeast(1)
            val jitteredSec = Random.nextLong(minDelaySec, calculatedDelaySec + 1)

            val effectiveSec = if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                maxOf(retryAfterSeconds, jitteredSec)
            } else {
                jitteredSec
            }
            return effectiveSec * 1000L
        }

        fun enqueueExpedited(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_EXPEDITED_DISPATCH,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            Timber.i("Enqueued expedited DeliveryWorker with ExistingWorkPolicy.KEEP")
        }
    }
}
