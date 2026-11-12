package com.smsforwarder.app.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.core.util.DomainError
import com.smsforwarder.app.domain.model.DestinationSecrets
import timber.log.Timber

/**
 * SecureVault encapsulates hardware-backed Android Keystore credential storage.
 * In accordance with Phase 1 & 2 Security Specifications:
 * - MasterKey uses AES-256-GCM.
 * - setUserAuthenticationRequired(false) is enforced to ensure headless background execution
 *   continues uninterrupted when the user changes or removes lockscreen credentials.
 * - Keys and values are protected using AES256_SIV and AES256_GCM schemes.
 */
class SecureVault(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true)
            .setUserAuthenticationRequired(false)
            .build()
    }

    @Volatile
    private var sharedPreferences: SharedPreferences? = null

    private fun getVaultPrefs(): SharedPreferences {
        return sharedPreferences ?: synchronized(this) {
            sharedPreferences ?: createVault().also { sharedPreferences = it }
        }
    }

    private fun createVault(): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                VAULT_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Timber.e(e, "EncryptedSharedPreferences init failed. Attempting clean recovery.")
            try {
                context.deleteSharedPreferences(VAULT_PREFS_NAME)
                EncryptedSharedPreferences.create(
                    context,
                    VAULT_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (fatal: Exception) {
                Timber.e(fatal, "Fatal Keystore failure during vault recovery.")
                throw fatal
            }
        }
    }

    fun saveTelegramSecrets(aliasRef: String, secrets: DestinationSecrets.Telegram): AppResult<Unit> {
        return try {
            getVaultPrefs().edit()
                .putString("${KEY_PREFIX_TG_BOT_TOKEN}_$aliasRef", secrets.botToken)
                .apply()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist Telegram secrets in vault")
            AppResult.Failure(DomainError.SecurityVaultError("Vault write failed", e))
        }
    }

    fun getTelegramSecrets(aliasRef: String): AppResult<DestinationSecrets.Telegram> {
        return try {
            val token = getVaultPrefs().getString("${KEY_PREFIX_TG_BOT_TOKEN}_$aliasRef", null)
            if (token != null) {
                AppResult.Success(DestinationSecrets.Telegram(botToken = token))
            } else {
                AppResult.Failure(DomainError.NotFoundError("Telegram secrets not found for alias $aliasRef"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read Telegram secrets from vault")
            AppResult.Failure(DomainError.SecurityVaultError("Vault read failed", e))
        }
    }

    fun saveWebhookSecrets(aliasRef: String, secrets: DestinationSecrets.Webhook): AppResult<Unit> {
        return try {
            getVaultPrefs().edit()
                .putString("${KEY_PREFIX_WH_AUTH}_$aliasRef", secrets.authHeader)
                .putString("${KEY_PREFIX_WH_HEADERS}_$aliasRef", secrets.customHeadersJson)
                .apply()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist Webhook secrets in vault")
            AppResult.Failure(DomainError.SecurityVaultError("Vault write failed", e))
        }
    }

    fun getWebhookSecrets(aliasRef: String): AppResult<DestinationSecrets.Webhook> {
        return try {
            val auth = getVaultPrefs().getString("${KEY_PREFIX_WH_AUTH}_$aliasRef", null)
            val headers = getVaultPrefs().getString("${KEY_PREFIX_WH_HEADERS}_$aliasRef", null)
            AppResult.Success(DestinationSecrets.Webhook(authHeader = auth, customHeadersJson = headers))
        } catch (e: Exception) {
            Timber.e(e, "Failed to read Webhook secrets from vault")
            AppResult.Failure(DomainError.SecurityVaultError("Vault read failed", e))
        }
    }

    fun saveSmtpSecrets(aliasRef: String, secrets: DestinationSecrets.Smtp): AppResult<Unit> {
        return try {
            getVaultPrefs().edit()
                .putString("${KEY_PREFIX_SMTP_HOST}_$aliasRef", secrets.host)
                .putInt("${KEY_PREFIX_SMTP_PORT}_$aliasRef", secrets.port)
                .putString("${KEY_PREFIX_SMTP_USER}_$aliasRef", secrets.username)
                .putString("${KEY_PREFIX_SMTP_PASS}_$aliasRef", secrets.password)
                .putString("${KEY_PREFIX_SMTP_FROM}_$aliasRef", secrets.fromAddress)
                .putBoolean("${KEY_PREFIX_SMTP_STARTTLS}_$aliasRef", secrets.useStartTls)
                .apply()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist SMTP secrets in vault")
            AppResult.Failure(DomainError.SecurityVaultError("Vault write failed", e))
        }
    }

    fun getSmtpSecrets(aliasRef: String): AppResult<DestinationSecrets.Smtp> {
        return try {
            val prefs = getVaultPrefs()
            val host = prefs.getString("${KEY_PREFIX_SMTP_HOST}_$aliasRef", null)
            val port = prefs.getInt("${KEY_PREFIX_SMTP_PORT}_$aliasRef", 465)
            val user = prefs.getString("${KEY_PREFIX_SMTP_USER}_$aliasRef", null)
            val pass = prefs.getString("${KEY_PREFIX_SMTP_PASS}_$aliasRef", null)
            val from = prefs.getString("${KEY_PREFIX_SMTP_FROM}_$aliasRef", null)
            val startTls = prefs.getBoolean("${KEY_PREFIX_SMTP_STARTTLS}_$aliasRef", true)

            if (host != null && user != null && pass != null && from != null) {
                AppResult.Success(
                    DestinationSecrets.Smtp(
                        host = host,
                        port = port,
                        username = user,
                        password = pass,
                        fromAddress = from,
                        useStartTls = startTls
                    )
                )
            } else {
                AppResult.Failure(DomainError.NotFoundError("SMTP secrets incomplete for alias $aliasRef"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read SMTP secrets from vault")
            AppResult.Failure(DomainError.SecurityVaultError("Vault read failed", e))
        }
    }

    fun saveWhatsAppAgentSecrets(aliasRef: String, secrets: DestinationSecrets.WhatsAppAgent): AppResult<Unit> {
        return try {
            getVaultPrefs().edit()
                .putString("${KEY_PREFIX_WA_API_KEY}_$aliasRef", secrets.apiKey)
                .putString("${KEY_PREFIX_WA_RECIPIENT_ID}_$aliasRef", secrets.recipientId)
                .apply()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist WhatsApp Agent secrets in vault")
            AppResult.Failure(DomainError.SecurityVaultError("Vault write failed", e))
        }
    }

    fun getWhatsAppAgentSecrets(aliasRef: String): AppResult<DestinationSecrets.WhatsAppAgent> {
        return try {
            val prefs = getVaultPrefs()
            val apiKey = prefs.getString("${KEY_PREFIX_WA_API_KEY}_$aliasRef", null)
            val recipientId = prefs.getString("${KEY_PREFIX_WA_RECIPIENT_ID}_$aliasRef", null)

            if (apiKey != null && recipientId != null) {
                AppResult.Success(
                    DestinationSecrets.WhatsAppAgent(
                        apiKey = apiKey,
                        recipientId = recipientId
                    )
                )
            } else {
                AppResult.Failure(DomainError.NotFoundError("WhatsApp Agent secrets incomplete for alias $aliasRef"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read WhatsApp Agent secrets from vault")
            AppResult.Failure(DomainError.SecurityVaultError("Vault read failed", e))
        }
    }

    fun deleteSecrets(aliasRef: String): AppResult<Unit> {
        return try {
            getVaultPrefs().edit()
                .remove("${KEY_PREFIX_TG_BOT_TOKEN}_$aliasRef")
                .remove("${KEY_PREFIX_WH_AUTH}_$aliasRef")
                .remove("${KEY_PREFIX_WH_HEADERS}_$aliasRef")
                .remove("${KEY_PREFIX_SMTP_HOST}_$aliasRef")
                .remove("${KEY_PREFIX_SMTP_PORT}_$aliasRef")
                .remove("${KEY_PREFIX_SMTP_USER}_$aliasRef")
                .remove("${KEY_PREFIX_SMTP_PASS}_$aliasRef")
                .remove("${KEY_PREFIX_SMTP_FROM}_$aliasRef")
                .remove("${KEY_PREFIX_SMTP_STARTTLS}_$aliasRef")
                .remove("${KEY_PREFIX_WA_API_KEY}_$aliasRef")
                .remove("${KEY_PREFIX_WA_RECIPIENT_ID}_$aliasRef")
                .apply()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove secrets for alias $aliasRef")
            AppResult.Failure(DomainError.SecurityVaultError("Vault delete failed", e))
        }
    }

    fun clearAllSecrets(): AppResult<Unit> {
        return try {
            getVaultPrefs().edit().clear().apply()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear vault")
            AppResult.Failure(DomainError.SecurityVaultError("Vault clear failed", e))
        }
    }

    companion object {
        private const val MASTER_KEY_ALIAS = "_sms_forwarder_master_key_"
        private const val VAULT_PREFS_NAME = "secure_forwarder_vault"

        private const val KEY_PREFIX_TG_BOT_TOKEN = "tg_bot_token"
        private const val KEY_PREFIX_WH_AUTH = "wh_auth"
        private const val KEY_PREFIX_WH_HEADERS = "wh_headers"
        private const val KEY_PREFIX_SMTP_HOST = "smtp_host"
        private const val KEY_PREFIX_SMTP_PORT = "smtp_port"
        private const val KEY_PREFIX_SMTP_USER = "smtp_user"
        private const val KEY_PREFIX_SMTP_PASS = "smtp_pass"
        private const val KEY_PREFIX_SMTP_FROM = "smtp_from"
        private const val KEY_PREFIX_SMTP_STARTTLS = "smtp_starttls"
        private const val KEY_PREFIX_WA_API_KEY = "wa_api_key"
        private const val KEY_PREFIX_WA_RECIPIENT_ID = "wa_recipient_id"
    }
}
