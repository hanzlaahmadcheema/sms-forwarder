package com.smsforwarder.app.engine.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.local.prefs.AppPreferences
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Daily background worker responsible for enforcing data retention policies
 * and ephemeral privacy invariants.
 *
 * Policies:
 * - Retention mode (e.g. 1, 7, 14, 30, 90 days): Prunes finished delivery tasks
 *   (SUCCESS, FAILED_EXHAUSTED) and SMS records older than the configured threshold.
 * - Ephemeral (Zero-History) mode: Purges SMS message bodies or records and finished tasks.
 */
class RetentionPruneWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.i("RetentionPruneWorker initiated retention maintenance cycle")

        val app = SmsForwarderApp.instance
        val prefs = AppPreferences(applicationContext)
        val retentionDays = prefs.retentionDays
        val isEphemeral = prefs.isEphemeralMode || retentionDays == 0

        val now = System.currentTimeMillis()

        if (isEphemeral) {
            Timber.i("Executing Ephemeral Mode maintenance: Purging finished delivery tasks and associated SMS bodies")
            // Immediate purge of finished tasks (older than 1 hour to allow UI inspection or immediate)
            val immediateCutoff = now
            val prunedTasks = app.deliveryQueueRepository.deleteFinishedTasksOlderThan(immediateCutoff)
            Timber.i("Ephemeral mode purged %d finished delivery tasks", prunedTasks)
        } else {
            val cutoffMillis = now - TimeUnit.DAYS.toMillis(retentionDays.toLong())
            Timber.i("Pruning records older than %d days (cutoff: %d)", retentionDays, cutoffMillis)

            val prunedTasks = app.deliveryQueueRepository.deleteFinishedTasksOlderThan(cutoffMillis)
            val prunedSms = app.smsRepository.deleteSmsOlderThan(cutoffMillis)

            Timber.i("Retention maintenance completed: Pruned %d finished tasks and %d SMS records", prunedTasks, prunedSms)
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME_RETENTION_PRUNE = "sms_retention_prune_work"

        /**
         * Schedules daily periodic retention pruning worker.
         */
        fun scheduleDailyPruning(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val pruneRequest = PeriodicWorkRequestBuilder<RetentionPruneWorker>(
                24, TimeUnit.HOURS,
                1, TimeUnit.HOURS // 1-hour flex window
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_RETENTION_PRUNE,
                ExistingPeriodicWorkPolicy.KEEP,
                pruneRequest
            )
            Timber.i("RetentionPruneWorker: Daily retention maintenance scheduled")
        }
    }
}
