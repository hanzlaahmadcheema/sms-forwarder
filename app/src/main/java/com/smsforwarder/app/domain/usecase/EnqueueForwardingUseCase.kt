package com.smsforwarder.app.domain.usecase

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.model.TaskStatus
import com.smsforwarder.app.domain.repository.DeliveryQueueRepository
import timber.log.Timber
import java.util.UUID

/**
 * EnqueueForwardingUseCase creates outbound DeliveryTaskEntity queue records
 * for each matched forwarding destination, with status PENDING, and triggers
 * the background delivery dispatcher.
 */
class EnqueueForwardingUseCase(
    private val queueRepository: DeliveryQueueRepository
) {

    suspend operator fun invoke(
        sms: SmsMessage,
        matchingRules: List<ForwardingRule>
    ): AppResult<List<DeliveryTask>> {
        if (matchingRules.isEmpty()) {
            return AppResult.Success(emptyList())
        }

        val tasks = matchingRules.map { rule ->
            DeliveryTask(
                id = UUID.randomUUID().toString(),
                smsRecordId = sms.id,
                ruleId = rule.id,
                destinationType = rule.targetDestinationType,
                destinationEndpoint = rule.destinationEndpoint,
                status = TaskStatus.PENDING,
                attemptCount = 0,
                lastAttemptAt = null,
                nextRetryAt = null,
                errorMessage = null,
                vaultSecretAlias = rule.vaultAliasRef,
                createdAt = System.currentTimeMillis()
            )
        }

        val insertResult = queueRepository.insertTasks(tasks)
        return when (insertResult) {
            is AppResult.Success -> {
                Timber.i("Enqueued %d delivery tasks for SMS %s", tasks.size, sms.id)
                AppResult.Success(tasks)
            }
            is AppResult.Failure -> {
                Timber.e("Failed to enqueue delivery tasks for SMS %s: %s", sms.id, insertResult.error.message)
                AppResult.Failure(insertResult.error)
            }
        }
    }
}
