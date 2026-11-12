package com.smsforwarder.app.domain.model

data class SmsMessage(
    val id: String,
    val sender: String,
    val rawBody: String,
    val timestampMillis: Long,
    val simSlotIndex: Int,
    val subId: Int,
    val carrierName: String?,
    val deduplicationHash: String,
    val createdAt: Long,
    val contactName: String? = null
)

data class ForwardingRule(
    val id: String,
    val name: String,
    val isEnabled: Boolean,
    val targetDestinationType: DestinationType,
    val destinationEndpoint: String,
    val senderFilterType: SenderFilterType = SenderFilterType.ALL,
    val senderFilterValues: String = "",
    val keywordFilterValues: String = "",
    val simSlotFilter: Int = -1,
    val customTemplate: String? = null,
    val vaultAliasRef: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

data class DeliveryTask(
    val id: String,
    val smsRecordId: String,
    val ruleId: String?,
    val destinationType: DestinationType,
    val destinationEndpoint: String,
    val status: TaskStatus,
    val attemptCount: Int,
    val lastAttemptAt: Long?,
    val nextRetryAt: Long?,
    val errorMessage: String?,
    val vaultSecretAlias: String,
    val createdAt: Long
)

sealed class DestinationSecrets {
    data class Telegram(
        val botToken: String
    ) : DestinationSecrets()

    data class Webhook(
        val authHeader: String? = null,
        val customHeadersJson: String? = null
    ) : DestinationSecrets()

    data class Smtp(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val fromAddress: String,
        val useStartTls: Boolean = true
    ) : DestinationSecrets()

    data class WhatsAppAgent(
        val apiKey: String,
        val recipientId: String
    ) : DestinationSecrets()
}
