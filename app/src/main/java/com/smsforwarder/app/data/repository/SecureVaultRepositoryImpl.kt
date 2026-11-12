package com.smsforwarder.app.data.repository

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.core.util.DomainError
import com.smsforwarder.app.data.security.SecureVault
import com.smsforwarder.app.domain.model.DestinationSecrets
import com.smsforwarder.app.domain.repository.SecureVaultRepository

class SecureVaultRepositoryImpl(
    private val secureVault: SecureVault
) : SecureVaultRepository {

    override suspend fun saveSecrets(aliasRef: String, secrets: DestinationSecrets): AppResult<Unit> {
        return when (secrets) {
            is DestinationSecrets.Telegram -> secureVault.saveTelegramSecrets(aliasRef, secrets)
            is DestinationSecrets.Webhook -> secureVault.saveWebhookSecrets(aliasRef, secrets)
            is DestinationSecrets.Smtp -> secureVault.saveSmtpSecrets(aliasRef, secrets)
            is DestinationSecrets.WhatsAppAgent -> secureVault.saveWhatsAppAgentSecrets(aliasRef, secrets)
        }
    }

    override suspend fun getSecrets(aliasRef: String): AppResult<DestinationSecrets> {
        // Attempt WhatsApp Agent read first if applicable
        val waResult = secureVault.getWhatsAppAgentSecrets(aliasRef)
        if (waResult is AppResult.Success) return waResult

        // Attempt Telegram read
        val tgResult = secureVault.getTelegramSecrets(aliasRef)
        if (tgResult is AppResult.Success) return tgResult

        // Attempt Webhook read
        val whResult = secureVault.getWebhookSecrets(aliasRef)
        if (whResult is AppResult.Success && (whResult.data.authHeader != null || whResult.data.customHeadersJson != null)) {
            return whResult
        }

        // Attempt SMTP read
        val smtpResult = secureVault.getSmtpSecrets(aliasRef)
        if (smtpResult is AppResult.Success) return smtpResult

        return AppResult.Failure(DomainError.NotFoundError("No secrets found in vault for alias $aliasRef"))
    }

    override suspend fun deleteSecrets(aliasRef: String): AppResult<Unit> {
        return secureVault.deleteSecrets(aliasRef)
    }

    override suspend fun clearVault(): AppResult<Unit> {
        return secureVault.clearAllSecrets()
    }
}
