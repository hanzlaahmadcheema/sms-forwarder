package com.smsforwarder.app.domain.usecase

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.model.TaskStatus
import com.smsforwarder.app.domain.repository.DeliveryQueueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnqueueForwardingUseCaseTest {

    private lateinit var fakeQueueRepository: FakeQueueRepository
    private lateinit var enqueueForwardingUseCase: EnqueueForwardingUseCase

    @Before
    fun setUp() {
        fakeQueueRepository = FakeQueueRepository()
        enqueueForwardingUseCase = EnqueueForwardingUseCase(fakeQueueRepository)
    }

    private fun createTestSms(): SmsMessage = SmsMessage(
        id = "sms-uuid-101",
        sender = "+15551234567",
        rawBody = "Test message content",
        timestampMillis = 1731175200000L,
        simSlotIndex = 0,
        subId = 1,
        carrierName = "CarrierA",
        deduplicationHash = "hash101",
        createdAt = 1731175200000L
    )

    private fun createRule(id: String, type: DestinationType, endpoint: String): ForwardingRule = ForwardingRule(
        id = id,
        name = "Rule $id",
        isEnabled = true,
        targetDestinationType = type,
        destinationEndpoint = endpoint,
        vaultAliasRef = "vault_ref_$id",
        createdAt = System.currentTimeMillis()
    )

    @Test
    fun invoke_returnsEmptyWhenNoRulesMatched() = runBlocking {
        val sms = createTestSms()
        val result = enqueueForwardingUseCase(sms, emptyList())
        assertTrue(result is AppResult.Success)
        assertTrue((result as AppResult.Success).data.isEmpty())
        assertTrue(fakeQueueRepository.tasks.isEmpty())
    }

    @Test
    fun invoke_createsDeliveryTasksWithCorrectAttributes() = runBlocking {
        val sms = createTestSms()
        val rules = listOf(
            createRule("rule-wh", DestinationType.WEBHOOK, "https://api.example.com/sms"),
            createRule("rule-tg", DestinationType.TELEGRAM, "-1001234567890"),
            createRule("rule-email", DestinationType.EMAIL, "forward@example.com")
        )

        val result = enqueueForwardingUseCase(sms, rules)
        assertTrue(result is AppResult.Success)
        val tasks = (result as AppResult.Success).data
        assertEquals(3, tasks.size)

        // Verify task mapping
        tasks.forEach { task ->
            assertEquals(sms.id, task.smsRecordId)
            assertEquals(TaskStatus.PENDING, task.status)
            assertEquals(0, task.attemptCount)
            assertEquals(null, task.lastAttemptAt)
            assertEquals(null, task.nextRetryAt)
            assertEquals(null, task.errorMessage)
        }

        val whTask = tasks.find { it.destinationType == DestinationType.WEBHOOK }!!
        assertEquals("rule-wh", whTask.ruleId)
        assertEquals("https://api.example.com/sms", whTask.destinationEndpoint)
        assertEquals("vault_ref_rule-wh", whTask.vaultSecretAlias)

        val tgTask = tasks.find { it.destinationType == DestinationType.TELEGRAM }!!
        assertEquals("rule-tg", tgTask.ruleId)
        assertEquals("-1001234567890", tgTask.destinationEndpoint)

        val emailTask = tasks.find { it.destinationType == DestinationType.EMAIL }!!
        assertEquals("rule-email", emailTask.ruleId)
        assertEquals("forward@example.com", emailTask.destinationEndpoint)

        // Verify persisted in repository
        assertEquals(3, fakeQueueRepository.tasks.size)
    }

    private class FakeQueueRepository : DeliveryQueueRepository {
        val tasks = mutableListOf<DeliveryTask>()

        override fun getAllTasksFlow(): Flow<List<DeliveryTask>> = flowOf(tasks)
        override fun getTasksByStatusFlow(status: TaskStatus): Flow<List<DeliveryTask>> = flowOf(tasks.filter { it.status == status })
        override suspend fun getTaskById(id: String): DeliveryTask? = tasks.find { it.id == id }
        override suspend fun getPendingTasks(): List<DeliveryTask> = tasks.filter { it.status == TaskStatus.PENDING }
        override suspend fun getDueRetryTasks(nowMillis: Long): List<DeliveryTask> = tasks.filter {
            it.status == TaskStatus.FAILED_RETRYABLE && it.nextRetryAt != null && it.nextRetryAt!! <= nowMillis
        }

        override suspend fun insertTasks(newTasks: List<DeliveryTask>): AppResult<Unit> {
            tasks.addAll(newTasks)
            return AppResult.Success(Unit)
        }

        override suspend fun updateTaskStatus(
            taskId: String,
            status: TaskStatus,
            attemptCount: Int,
            lastAttemptAt: Long?,
            nextRetryAt: Long?,
            errorMessage: String?
        ): AppResult<Unit> {
            val idx = tasks.indexOfFirst { it.id == taskId }
            if (idx >= 0) {
                tasks[idx] = tasks[idx].copy(
                    status = status,
                    attemptCount = attemptCount,
                    lastAttemptAt = lastAttemptAt,
                    nextRetryAt = nextRetryAt,
                    errorMessage = errorMessage
                )
            }
            return AppResult.Success(Unit)
        }

        override suspend fun claimTask(taskId: String, now: Long): Boolean {
            val idx = tasks.indexOfFirst { it.id == taskId }
            if (idx >= 0 && (tasks[idx].status == TaskStatus.PENDING || tasks[idx].status == TaskStatus.FAILED_RETRYABLE)) {
                tasks[idx] = tasks[idx].copy(status = TaskStatus.IN_PROGRESS, lastAttemptAt = now)
                return true
            }
            return false
        }

        override suspend fun resetInProgressTasksToPending(): Int = 0
        override suspend fun resetOrphanedInProgressTasks(cutoffMillis: Long): Int = 0
        override suspend fun retryTaskManually(taskId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun retryAllFailedTasks(): AppResult<Int> = AppResult.Success(0)
        override suspend fun deleteTasksOlderThan(cutoffMillis: Long): Int = 0
        override suspend fun deleteFinishedTasksOlderThan(cutoffMillis: Long): Int = 0
        override suspend fun deleteAllTasks() { tasks.clear() }
    }
}
