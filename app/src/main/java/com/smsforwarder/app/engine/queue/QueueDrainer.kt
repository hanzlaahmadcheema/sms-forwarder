package com.smsforwarder.app.engine.queue

import android.content.Context
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.core.util.SecurityLogTree
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.TaskStatus
import com.smsforwarder.app.engine.dispatcher.DispatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.random.Random

/**
 * QueueDrainer handles direct, thread-safe queue execution on Dispatchers.IO.
 * Operates independently of Android JobScheduler / WorkManager quotas, guaranteeing
 * instantaneous dispatch when called from foreground services, receivers, or UI actions.
 */
object QueueDrainer {

    private val drainMutex = Mutex()
    const val MAX_RETRY_ATTEMPTS = 5
    private const val BASE_BACKOFF_SECONDS = 30L
    private const val MAX_BACKOFF_SECONDS = 1800L

    suspend fun drainQueue(context: Context): Int = withContext(Dispatchers.IO) {
        if (!drainMutex.tryLock()) {
            Timber.d("QueueDrainer: Drain already in progress, skipping concurrent duplicate run")
            return@withContext 0
        }
        var processedCount = 0
        try {
            val app = SmsForwarderApp.instance
            val queueRepository = app.deliveryQueueRepository
            val smsRepository = app.smsRepository
            val ruleRepository = app.forwardingRuleRepository

            // Reconcile tasks stuck in IN_PROGRESS for more than 5 minutes due to any unexpected kill
            val orphanCutoff = System.currentTimeMillis() - 300_000L
            val resetCount = queueRepository.resetOrphanedInProgressTasks(orphanCutoff)
            if (resetCount > 0) {
                Timber.i("QueueDrainer: Reconciled %d orphaned IN_PROGRESS tasks to PENDING", resetCount)
            }

            while (true) {
                val now = System.currentTimeMillis()
                val pendingTasks = queueRepository.getPendingTasks()
                val dueTasks = queueRepository.getDueRetryTasks(now)
                val tasksToExecute = (pendingTasks + dueTasks).distinctBy { it.id }

                if (tasksToExecute.isEmpty()) {
                    break
                }

                Timber.i("QueueDrainer: Draining %d pending/due tasks", tasksToExecute.size)

                for (task in tasksToExecute) {
                    val claimed = queueRepository.claimTask(task.id, now)
                    if (!claimed) {
                        Timber.d("QueueDrainer: Task %s already claimed, skipping", task.id)
                        continue
                    }

                    val sms = smsRepository.getSmsById(task.smsRecordId)
                    if (sms == null) {
                        val errorMsg = "Associated SMS record ${task.smsRecordId} not found in database"
                        Timber.e(errorMsg)
                        queueRepository.updateTaskStatus(
                            taskId = task.id,
                            status = TaskStatus.FAILED_EXHAUSTED,
                            attemptCount = task.attemptCount + 1,
                            lastAttemptAt = System.currentTimeMillis(),
                            nextRetryAt = null,
                            errorMessage = SecurityLogTree.sanitize(errorMsg)
                        )
                        processedCount++
                        continue
                    }

                    val rule = task.ruleId?.let { ruleRepository.getRuleById(it) }

                    val dispatchResult = when (task.destinationType) {
                        DestinationType.WEBHOOK -> app.webhookDispatcher.dispatch(task, sms, rule)
                        DestinationType.TELEGRAM -> app.telegramDispatcher.dispatch(task, sms, rule)
                        DestinationType.EMAIL -> app.emailDispatcher.dispatch(task, sms, rule)
                        DestinationType.WHATSAPP_AGENT -> app.whatsAppAgentDispatcher.dispatch(task, sms, rule)
                    }

                    val timestamp = System.currentTimeMillis()
                    val nextAttempt = task.attemptCount + 1

                    when (dispatchResult) {
                        is DispatchResult.Success -> {
                            Timber.i("QueueDrainer: Task %s delivered successfully: %s", task.id, dispatchResult.responseMessage)
                            queueRepository.updateTaskStatus(
                                taskId = task.id,
                                status = TaskStatus.SUCCESS,
                                attemptCount = nextAttempt,
                                lastAttemptAt = timestamp,
                                nextRetryAt = null,
                                errorMessage = null
                            )

                            val prefs = app.appPreferences
                            if (prefs.isEphemeralMode || prefs.retentionDays == 0) {
                                Timber.i("Ephemeral Mode: Purging SMS body for record %s", task.smsRecordId)
                                smsRepository.purgeSmsBody(task.smsRecordId)
                            }
                        }

                        is DispatchResult.RetryableFailure -> {
                            val sanitizedError = SecurityLogTree.sanitize(dispatchResult.reason)
                            if (nextAttempt >= MAX_RETRY_ATTEMPTS) {
                                Timber.w("QueueDrainer: Task %s exhausted retries (%d): %s", task.id, nextAttempt, sanitizedError)
                                queueRepository.updateTaskStatus(
                                    taskId = task.id,
                                    status = TaskStatus.FAILED_EXHAUSTED,
                                    attemptCount = nextAttempt,
                                    lastAttemptAt = timestamp,
                                    nextRetryAt = null,
                                    errorMessage = sanitizedError
                                )
                            } else {
                                val backoffDelayMs = calculateExponentialBackoffMs(nextAttempt, dispatchResult.retryAfterSeconds)
                                val nextRetryAt = timestamp + backoffDelayMs
                                Timber.w(
                                    "QueueDrainer: Task %s failed transiently (attempt %d/%d), rescheduling in %d ms: %s",
                                    task.id, nextAttempt, MAX_RETRY_ATTEMPTS, backoffDelayMs, sanitizedError
                                )
                                queueRepository.updateTaskStatus(
                                    taskId = task.id,
                                    status = TaskStatus.FAILED_RETRYABLE,
                                    attemptCount = nextAttempt,
                                    lastAttemptAt = timestamp,
                                    nextRetryAt = nextRetryAt,
                                    errorMessage = sanitizedError
                                )
                            }
                        }

                        is DispatchResult.PermanentFailure -> {
                            val sanitizedError = SecurityLogTree.sanitize(dispatchResult.reason)
                            Timber.e("QueueDrainer: Task %s failed permanently: %s", task.id, sanitizedError)
                            queueRepository.updateTaskStatus(
                                taskId = task.id,
                                status = TaskStatus.FAILED_EXHAUSTED,
                                attemptCount = nextAttempt,
                                lastAttemptAt = timestamp,
                                nextRetryAt = null,
                                errorMessage = sanitizedError
                            )
                        }
                    }
                    processedCount++
                }
            }
        } finally {
            drainMutex.unlock()
        }
        processedCount
    }

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
}
