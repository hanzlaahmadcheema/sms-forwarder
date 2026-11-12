package com.smsforwarder.app.domain.repository

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.DestinationSecrets
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

interface SmsRepository {
    fun getAllSmsFlow(): Flow<List<SmsMessage>>
    suspend fun insertSms(sms: SmsMessage): AppResult<Long>
    suspend fun getSmsById(id: String): SmsMessage?
    suspend fun findByDeduplicationHash(hash: String): SmsMessage?
    suspend fun findRecentDuplicate(sender: String, body: String, timestamp: Long, windowMillis: Long = 60000L): SmsMessage?
    suspend fun getRecentSms(limit: Int): List<SmsMessage>
    suspend fun deleteSmsOlderThan(cutoffMillis: Long): Int
    suspend fun purgeSmsBody(id: String): AppResult<Unit>
    suspend fun deleteSmsById(id: String): AppResult<Unit>
    suspend fun deleteAllSms()
}

interface ForwardingRuleRepository {
    fun getAllRulesFlow(): Flow<List<ForwardingRule>>
    suspend fun getActiveRules(): List<ForwardingRule>
    suspend fun getRuleById(id: String): ForwardingRule?
    suspend fun insertOrUpdateRule(rule: ForwardingRule): AppResult<Unit>
    suspend fun updateRuleEnabled(id: String, isEnabled: Boolean): AppResult<Unit>
    suspend fun deleteRule(id: String): AppResult<Unit>
    suspend fun deleteAllRules()
}

interface DeliveryQueueRepository {
    fun getAllTasksFlow(): Flow<List<DeliveryTask>>
    fun getTasksByStatusFlow(status: TaskStatus): Flow<List<DeliveryTask>>
    suspend fun getTaskById(id: String): DeliveryTask?
    suspend fun getPendingTasks(): List<DeliveryTask>
    suspend fun getDueRetryTasks(nowMillis: Long): List<DeliveryTask>
    suspend fun claimTask(taskId: String, now: Long = System.currentTimeMillis()): Boolean
    suspend fun insertTasks(tasks: List<DeliveryTask>): AppResult<Unit>
    suspend fun updateTaskStatus(
        taskId: String,
        status: TaskStatus,
        attemptCount: Int,
        lastAttemptAt: Long?,
        nextRetryAt: Long?,
        errorMessage: String?
    ): AppResult<Unit>
    suspend fun resetInProgressTasksToPending(): Int
    suspend fun resetOrphanedInProgressTasks(cutoffMillis: Long): Int
    suspend fun retryTaskManually(taskId: String): AppResult<Unit>
    suspend fun retryAllFailedTasks(): AppResult<Int>
    suspend fun deleteTasksOlderThan(cutoffMillis: Long): Int
    suspend fun deleteFinishedTasksOlderThan(cutoffMillis: Long): Int
    suspend fun deleteAllTasks()
}

interface SecureVaultRepository {
    suspend fun saveSecrets(aliasRef: String, secrets: DestinationSecrets): AppResult<Unit>
    suspend fun getSecrets(aliasRef: String): AppResult<DestinationSecrets>
    suspend fun deleteSecrets(aliasRef: String): AppResult<Unit>
    suspend fun clearVault(): AppResult<Unit>
}
