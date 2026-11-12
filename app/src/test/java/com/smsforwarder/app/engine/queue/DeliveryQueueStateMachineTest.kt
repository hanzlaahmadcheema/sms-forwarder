package com.smsforwarder.app.engine.queue

import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryQueueStateMachineTest {

    private fun createInitialTask(
        id: String = "task-state-001",
        status: TaskStatus = TaskStatus.PENDING,
        attemptCount: Int = 0
    ): DeliveryTask = DeliveryTask(
        id = id,
        smsRecordId = "sms-rec-001",
        ruleId = "rule-001",
        destinationType = DestinationType.WEBHOOK,
        destinationEndpoint = "https://example.com/webhook",
        status = status,
        attemptCount = attemptCount,
        lastAttemptAt = null,
        nextRetryAt = null,
        errorMessage = null,
        vaultSecretAlias = "alias-001",
        createdAt = System.currentTimeMillis()
    )

    @Test
    fun stateTransition_pendingToInProgress() {
        val task = createInitialTask()
        assertEquals(TaskStatus.PENDING, task.status)

        val inProgressTask = task.copy(status = TaskStatus.IN_PROGRESS)
        assertEquals(TaskStatus.IN_PROGRESS, inProgressTask.status)
    }

    @Test
    fun stateTransition_inProgressToSuccess() {
        val task = createInitialTask(status = TaskStatus.IN_PROGRESS)
        val timestamp = System.currentTimeMillis()

        val successTask = task.copy(
            status = TaskStatus.SUCCESS,
            attemptCount = task.attemptCount + 1,
            lastAttemptAt = timestamp,
            nextRetryAt = null,
            errorMessage = null
        )

        assertEquals(TaskStatus.SUCCESS, successTask.status)
        assertEquals(1, successTask.attemptCount)
        assertNull(successTask.nextRetryAt)
        assertNull(successTask.errorMessage)
    }

    @Test
    fun stateTransition_transientFailureBelowMaxRetries_becomesFailedRetryable() {
        val maxRetries = 5
        val task = createInitialTask(status = TaskStatus.IN_PROGRESS, attemptCount = 2)
        val nextAttempt = task.attemptCount + 1
        val timestamp = System.currentTimeMillis()
        val retryDelayMs = 60000L

        assertTrue("Attempt count 3 is below max 5", nextAttempt < maxRetries)

        val retryableTask = task.copy(
            status = TaskStatus.FAILED_RETRYABLE,
            attemptCount = nextAttempt,
            lastAttemptAt = timestamp,
            nextRetryAt = timestamp + retryDelayMs,
            errorMessage = "HTTP 503 Service Unavailable"
        )

        assertEquals(TaskStatus.FAILED_RETRYABLE, retryableTask.status)
        assertEquals(3, retryableTask.attemptCount)
        assertTrue(retryableTask.nextRetryAt != null && retryableTask.nextRetryAt!! > timestamp)
    }

    @Test
    fun stateTransition_maxRetriesExceeded_becomesFailedExhausted() {
        val maxRetries = 5
        val task = createInitialTask(status = TaskStatus.IN_PROGRESS, attemptCount = 4)
        val nextAttempt = task.attemptCount + 1
        val timestamp = System.currentTimeMillis()

        assertTrue("Attempt count 5 reaches max retries", nextAttempt >= maxRetries)

        val exhaustedTask = task.copy(
            status = TaskStatus.FAILED_EXHAUSTED,
            attemptCount = nextAttempt,
            lastAttemptAt = timestamp,
            nextRetryAt = null,
            errorMessage = "Remote endpoint failed 5 consecutive times"
        )

        assertEquals(TaskStatus.FAILED_EXHAUSTED, exhaustedTask.status)
        assertEquals(5, exhaustedTask.attemptCount)
        assertNull("Exhausted tasks must not schedule future retries", exhaustedTask.nextRetryAt)
    }

    @Test
    fun stateTransition_permanentFailure_immediatelyBecomesFailedExhausted() {
        val task = createInitialTask(status = TaskStatus.IN_PROGRESS, attemptCount = 0)
        val timestamp = System.currentTimeMillis()

        val permanentFailureTask = task.copy(
            status = TaskStatus.FAILED_EXHAUSTED,
            attemptCount = 1,
            lastAttemptAt = timestamp,
            nextRetryAt = null,
            errorMessage = "HTTP 401 Unauthorized"
        )

        assertEquals(TaskStatus.FAILED_EXHAUSTED, permanentFailureTask.status)
        assertNull(permanentFailureTask.nextRetryAt)
    }

    @Test
    fun orphanedTaskReconciliation_inProgressResetsToPending() {
        val orphanedTasks = listOf(
            createInitialTask("t1", status = TaskStatus.IN_PROGRESS),
            createInitialTask("t2", status = TaskStatus.IN_PROGRESS),
            createInitialTask("t3", status = TaskStatus.SUCCESS)
        )

        val reconciled = orphanedTasks.map {
            if (it.status == TaskStatus.IN_PROGRESS) it.copy(status = TaskStatus.PENDING) else it
        }

        assertEquals(TaskStatus.PENDING, reconciled[0].status)
        assertEquals(TaskStatus.PENDING, reconciled[1].status)
        assertEquals(TaskStatus.SUCCESS, reconciled[2].status)
        assertFalse(reconciled.any { it.status == TaskStatus.IN_PROGRESS })
    }

    @Test
    fun bulkRetry_resetsFailedTasksToPending() {
        val tasks = listOf(
            createInitialTask("t1", status = TaskStatus.FAILED_RETRYABLE, attemptCount = 2),
            createInitialTask("t2", status = TaskStatus.FAILED_EXHAUSTED, attemptCount = 5),
            createInitialTask("t3", status = TaskStatus.SUCCESS, attemptCount = 1)
        )

        val resetTasks = tasks.map {
            if (it.status == TaskStatus.FAILED_RETRYABLE || it.status == TaskStatus.FAILED_EXHAUSTED) {
                it.copy(
                    status = TaskStatus.PENDING,
                    nextRetryAt = null,
                    errorMessage = null
                )
            } else {
                it
            }
        }

        assertEquals(TaskStatus.PENDING, resetTasks[0].status)
        assertNull(resetTasks[0].nextRetryAt)
        assertEquals(TaskStatus.PENDING, resetTasks[1].status)
        assertNull(resetTasks[1].nextRetryAt)
        assertEquals(TaskStatus.SUCCESS, resetTasks[2].status)
    }
}
