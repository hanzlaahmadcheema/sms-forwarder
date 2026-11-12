package com.smsforwarder.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.domain.model.DestinationSecrets
import com.smsforwarder.app.domain.repository.DeliveryQueueRepository
import com.smsforwarder.app.domain.repository.ForwardingRuleRepository
import com.smsforwarder.app.domain.repository.SecureVaultRepository
import com.smsforwarder.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


data class SettingsUiState(
    val retentionDays: Int = 7,
    val isEphemeralMode: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val isServiceRunning: Boolean = true,
    val networkTimeoutSeconds: Int = 30,
    val telegramRetryOnTimeout: Boolean = false,
    val deduplicationWindowSeconds: Int = 60,
    val defaultTemplate: String = "",
    val wipeCompleted: Boolean = false,
    val waApiKeyInput: String = "",
    val waRecipientId: String? = null,
    val waRecipientInput: String = "",
    val isWaVerified: Boolean = false,
    val isWaLoading: Boolean = false,
    val waStatusMessage: String? = null,
    // Webhook test state
    val isWebhookTesting: Boolean = false,
    val webhookTestResult: String? = null,
    val isWebhookTestSuccess: Boolean = false,
    // Telegram test state
    val isTelegramTesting: Boolean = false,
    val telegramTestResult: String? = null,
    val isTelegramTestSuccess: Boolean = false
)

class SettingsViewModel(
    private val smsRepository: SmsRepository,
    private val ruleRepository: ForwardingRuleRepository,
    private val queueRepository: DeliveryQueueRepository,
    private val vaultRepository: SecureVaultRepository
) : ViewModel() {

    private val prefs = SmsForwarderApp.instance.appPreferences
    private val dispatcher = SmsForwarderApp.instance.whatsAppAgentDispatcher

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            retentionDays = prefs.retentionDays,
            isEphemeralMode = prefs.isEphemeralMode,
            isBatteryOptimizationIgnored = com.smsforwarder.app.engine.reliability.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(
                SmsForwarderApp.instance
            ),
            isServiceRunning = prefs.isServiceRunning,
            networkTimeoutSeconds = prefs.networkTimeoutSeconds,
            telegramRetryOnTimeout = prefs.telegramRetryOnTimeout,
            deduplicationWindowSeconds = prefs.deduplicationWindowSeconds,
            defaultTemplate = prefs.defaultTemplate
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadWhatsAppAgentConfig()
    }

    fun loadWhatsAppAgentConfig() {
        viewModelScope.launch {
            val result = vaultRepository.getSecrets(GLOBAL_WA_ALIAS)
            if (result is AppResult.Success && result.data is DestinationSecrets.WhatsAppAgent) {
                val secrets = result.data as DestinationSecrets.WhatsAppAgent
                _uiState.value = _uiState.value.copy(
                    waApiKeyInput = secrets.apiKey,
                    waRecipientId = secrets.recipientId.ifBlank { null },
                    waRecipientInput = secrets.recipientId,
                    isWaVerified = secrets.recipientId.isNotBlank(),
                    waStatusMessage = if (secrets.recipientId.isNotBlank()) "Linked to ${secrets.recipientId}" else null
                )
            }
        }
    }

    fun onWaApiKeyChange(key: String) {
        _uiState.value = _uiState.value.copy(
            waApiKeyInput = key,
            waStatusMessage = null
        )
    }

    fun onWaRecipientIdChange(id: String) {
        _uiState.value = _uiState.value.copy(
            waRecipientInput = id,
            waStatusMessage = null
        )
    }

    fun saveWhatsAppAgentConfig() {
        val apiKey = _uiState.value.waApiKeyInput.trim()
        val recipientRaw = _uiState.value.waRecipientInput.trim()
        if (apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(waStatusMessage = "Please enter your WhatsApp Agent API key")
            return
        }

        val formattedRecipient = if (recipientRaw.isNotBlank()) {
            dispatcher.formatParticipantId(recipientRaw)
        } else ""

        viewModelScope.launch {
            vaultRepository.saveSecrets(
                GLOBAL_WA_ALIAS,
                DestinationSecrets.WhatsAppAgent(apiKey = apiKey, recipientId = formattedRecipient)
            )
            _uiState.value = _uiState.value.copy(
                waRecipientInput = formattedRecipient,
                waRecipientId = formattedRecipient.ifBlank { null },
                isWaVerified = formattedRecipient.isNotBlank(),
                waStatusMessage = if (formattedRecipient.isNotBlank()) {
                    "Credentials saved & linked to $formattedRecipient"
                } else {
                    "API key saved. Enter Participant ID or click Verify."
                }
            )
        }
    }

    fun verifyAndLinkWhatsApp() {
        val apiKey = _uiState.value.waApiKeyInput.trim()
        if (apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(waStatusMessage = "Please enter your WhatsApp Agent API key")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWaLoading = true, waStatusMessage = "Querying agent updates...")
            val result = dispatcher.verifyAndDeriveRecipient(apiKey)
            if (result.isSuccess) {
                val derivedId = result.getOrThrow()
                // Persist securely to Keystore vault
                vaultRepository.saveSecrets(
                    GLOBAL_WA_ALIAS,
                    DestinationSecrets.WhatsAppAgent(apiKey = apiKey, recipientId = derivedId)
                )
                _uiState.value = _uiState.value.copy(
                    isWaLoading = false,
                    isWaVerified = true,
                    waRecipientId = derivedId,
                    waRecipientInput = derivedId,
                    waStatusMessage = "Successfully verified & linked to $derivedId"
                )
            } else {
                val err = result.exceptionOrNull()?.message ?: "Verification failed"
                _uiState.value = _uiState.value.copy(
                    isWaLoading = false,
                    waStatusMessage = "$err (You can enter your User ID manually)"
                )
            }
        }
    }

    fun sendWhatsAppTestMessage() {
        val apiKey = _uiState.value.waApiKeyInput.trim()
        val recipientInput = _uiState.value.waRecipientInput.trim()
        val recipient = if (recipientInput.isNotBlank()) {
            dispatcher.formatParticipantId(recipientInput)
        } else {
            _uiState.value.waRecipientId?.trim() ?: ""
        }

        if (apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(waStatusMessage = "Please enter your WhatsApp Agent API key")
            return
        }
        if (recipient.isBlank()) {
            _uiState.value = _uiState.value.copy(waStatusMessage = "Please enter your Participant User ID (e.g. user:12345)")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWaLoading = true, waStatusMessage = "Sending single test message...")
            // Ensure entered secrets are saved to vault
            vaultRepository.saveSecrets(
                GLOBAL_WA_ALIAS,
                DestinationSecrets.WhatsAppAgent(apiKey = apiKey, recipientId = recipient)
            )
            _uiState.value = _uiState.value.copy(
                waRecipientInput = recipient,
                waRecipientId = recipient,
                isWaVerified = true
            )

            val result = dispatcher.sendTestMessage(apiKey, recipient)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isWaLoading = false,
                    waStatusMessage = "Test message delivered successfully to $recipient"
                )
            } else {
                val err = result.exceptionOrNull()?.message ?: "Test dispatch failed"
                _uiState.value = _uiState.value.copy(
                    isWaLoading = false,
                    waStatusMessage = "Test send error: $err"
                )
            }
        }
    }

    fun refreshBatteryOptimizationStatus() {
        val ignored = com.smsforwarder.app.engine.reliability.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(
            SmsForwarderApp.instance
        )
        _uiState.value = _uiState.value.copy(isBatteryOptimizationIgnored = ignored)
    }

    fun updateRetentionDays(days: Int) {
        prefs.retentionDays = days
        _uiState.value = _uiState.value.copy(retentionDays = days)
    }

    fun toggleEphemeralMode(enabled: Boolean) {
        prefs.isEphemeralMode = enabled
        _uiState.value = _uiState.value.copy(isEphemeralMode = enabled)
    }

    fun setBatteryOptimizationStatus(ignored: Boolean) {
        _uiState.value = _uiState.value.copy(isBatteryOptimizationIgnored = ignored)
    }

    fun toggleServiceState(running: Boolean) {
        prefs.isServiceRunning = running
        _uiState.value = _uiState.value.copy(isServiceRunning = running)
    }

    fun updateNetworkTimeout(seconds: Int) {
        prefs.networkTimeoutSeconds = seconds
        _uiState.value = _uiState.value.copy(networkTimeoutSeconds = seconds)
    }

    fun toggleTelegramRetryOnTimeout(enabled: Boolean) {
        prefs.telegramRetryOnTimeout = enabled
        _uiState.value = _uiState.value.copy(telegramRetryOnTimeout = enabled)
    }

    fun updateDeduplicationWindow(seconds: Int) {
        prefs.deduplicationWindowSeconds = seconds
        _uiState.value = _uiState.value.copy(deduplicationWindowSeconds = seconds)
    }

    fun updateDefaultTemplate(template: String) {
        prefs.defaultTemplate = template
        _uiState.value = _uiState.value.copy(defaultTemplate = template)
    }

    fun testWebhook(url: String, auth: String? = null, payload: String? = null) {
        if (url.isBlank()) {
            _uiState.value = _uiState.value.copy(
                webhookTestResult = "Please enter a valid HTTPS webhook URL",
                isWebhookTestSuccess = false
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isWebhookTesting = true,
                webhookTestResult = "Connecting to webhook endpoint..."
            )
            val res = SmsForwarderApp.instance.webhookDispatcher.sendTestWebhook(
                endpoint = url,
                authHeader = auth,
                customPayload = payload
            )
            if (res.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isWebhookTesting = false,
                    webhookTestResult = res.getOrNull() ?: "Success",
                    isWebhookTestSuccess = true
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isWebhookTesting = false,
                    webhookTestResult = res.exceptionOrNull()?.message ?: "Request failed",
                    isWebhookTestSuccess = false
                )
            }
        }
    }

    fun testTelegram(botToken: String, chatId: String) {
        if (botToken.isBlank() || chatId.isBlank()) {
            _uiState.value = _uiState.value.copy(
                telegramTestResult = "Bot token and Chat ID are required",
                isTelegramTestSuccess = false
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isTelegramTesting = true,
                telegramTestResult = "Sending Telegram message..."
            )
            val res = SmsForwarderApp.instance.telegramDispatcher.sendTestMessage(botToken, chatId)
            if (res.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isTelegramTesting = false,
                    telegramTestResult = res.getOrNull() ?: "Message delivered successfully",
                    isTelegramTestSuccess = true
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isTelegramTesting = false,
                    telegramTestResult = res.exceptionOrNull()?.message ?: "Delivery failed",
                    isTelegramTestSuccess = false
                )
            }
        }
    }

    fun executePanicDataWipe(onComplete: () -> Unit) {
        viewModelScope.launch {
            // Atomic purge of all application data
            smsRepository.deleteAllSms()
            ruleRepository.deleteAllRules()
            queueRepository.deleteAllTasks()
            vaultRepository.clearVault()

            _uiState.value = _uiState.value.copy(
                wipeCompleted = true,
                waApiKeyInput = "",
                waRecipientId = null,
                waRecipientInput = "",
                isWaVerified = false,
                waStatusMessage = null
            )
            onComplete()
        }
    }

    fun exportBackupJson(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val root = org.json.JSONObject()
            root.put("app", "smsforwarder")
            root.put("version", 1)
            root.put("exportedAt", System.currentTimeMillis())

            val prefsObj = org.json.JSONObject()
            prefsObj.put("retentionDays", prefs.retentionDays)
            prefsObj.put("isEphemeralMode", prefs.isEphemeralMode)
            prefsObj.put("isServiceRunning", prefs.isServiceRunning)
            prefsObj.put("networkTimeoutSeconds", prefs.networkTimeoutSeconds)
            prefsObj.put("telegramRetryOnTimeout", prefs.telegramRetryOnTimeout)
            prefsObj.put("deduplicationWindowSeconds", prefs.deduplicationWindowSeconds)
            prefsObj.put("defaultTemplate", prefs.defaultTemplate)
            root.put("preferences", prefsObj)

            val rulesList = ruleRepository.getAllRulesFlow().first()
            val rulesArr = org.json.JSONArray()

            for (rule in rulesList) {
                val rObj = org.json.JSONObject()
                rObj.put("id", rule.id)
                rObj.put("name", rule.name)
                rObj.put("isEnabled", rule.isEnabled)
                rObj.put("type", rule.targetDestinationType.name)
                rObj.put("endpoint", rule.destinationEndpoint)
                rObj.put("senderFilterType", rule.senderFilterType.name)
                rObj.put("senderFilterValues", rule.senderFilterValues)
                rObj.put("keywordFilterValues", rule.keywordFilterValues)
                rObj.put("simSlotFilter", rule.simSlotFilter)
                rObj.put("customTemplate", rule.customTemplate ?: "")

                val secretsRes = vaultRepository.getSecrets(rule.vaultAliasRef)
                if (secretsRes is AppResult.Success) {
                    val secObj = org.json.JSONObject()
                    when (val sec = secretsRes.data) {
                        is DestinationSecrets.Webhook -> {
                            secObj.put("authHeader", sec.authHeader ?: "")
                            secObj.put("customHeadersJson", sec.customHeadersJson ?: "")
                        }
                        is DestinationSecrets.Telegram -> {
                            secObj.put("botToken", sec.botToken)
                        }
                        is DestinationSecrets.WhatsAppAgent -> {
                            secObj.put("apiKey", sec.apiKey)
                            secObj.put("recipientId", sec.recipientId)
                        }
                        is DestinationSecrets.Smtp -> {
                            secObj.put("host", sec.host)
                            secObj.put("port", sec.port)
                            secObj.put("username", sec.username)
                            secObj.put("password", sec.password)
                            secObj.put("fromAddress", sec.fromAddress)
                            secObj.put("useStartTls", sec.useStartTls)
                        }
                    }
                    rObj.put("secrets", secObj)
                }
                rulesArr.put(rObj)
            }
            root.put("rules", rulesArr)
            onResult(root.toString(2))
        }
    }

    fun restoreBackupJson(jsonString: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val trimmed = jsonString.trim()
                if (trimmed.isEmpty()) {
                    onResult(false, "Backup data is empty.")
                    return@launch
                }
                val root = org.json.JSONObject(trimmed)

                // 1. Restore Preferences
                if (root.has("preferences")) {
                    val prefsObj = root.getJSONObject("preferences")
                    if (prefsObj.has("retentionDays")) {
                        val days = prefsObj.getInt("retentionDays")
                        prefs.retentionDays = days
                        _uiState.value = _uiState.value.copy(retentionDays = days)
                    }
                    if (prefsObj.has("isEphemeralMode")) {
                        val ephem = prefsObj.getBoolean("isEphemeralMode")
                        prefs.isEphemeralMode = ephem
                        _uiState.value = _uiState.value.copy(isEphemeralMode = ephem)
                    }
                    if (prefsObj.has("networkTimeoutSeconds")) {
                        val timeout = prefsObj.getInt("networkTimeoutSeconds")
                        prefs.networkTimeoutSeconds = timeout
                        _uiState.value = _uiState.value.copy(networkTimeoutSeconds = timeout)
                    }
                    if (prefsObj.has("telegramRetryOnTimeout")) {
                        val tgRetry = prefsObj.getBoolean("telegramRetryOnTimeout")
                        prefs.telegramRetryOnTimeout = tgRetry
                        _uiState.value = _uiState.value.copy(telegramRetryOnTimeout = tgRetry)
                    }
                    if (prefsObj.has("deduplicationWindowSeconds")) {
                        val dedup = prefsObj.getInt("deduplicationWindowSeconds")
                        prefs.deduplicationWindowSeconds = dedup
                        _uiState.value = _uiState.value.copy(deduplicationWindowSeconds = dedup)
                    }
                    if (prefsObj.has("defaultTemplate")) {
                        val tmpl = prefsObj.getString("defaultTemplate")
                        prefs.defaultTemplate = tmpl
                        _uiState.value = _uiState.value.copy(defaultTemplate = tmpl)
                    }
                }

                // 2. Restore Rules
                var restoredRuleCount = 0
                if (root.has("rules")) {
                    val rulesArr = root.getJSONArray("rules")
                    for (i in 0 until rulesArr.length()) {
                        val rObj = rulesArr.getJSONObject(i)
                        val ruleId = java.util.UUID.randomUUID().toString()
                        val vaultAlias = "rule_$ruleId"
                        val name = rObj.optString("name", "Restored Rule")
                        val typeStr = rObj.optString("type", "WEBHOOK").uppercase()
                        val type = when {
                            typeStr.contains("TELEGRAM") -> com.smsforwarder.app.domain.model.DestinationType.TELEGRAM
                            typeStr.contains("WHATSAPP") -> com.smsforwarder.app.domain.model.DestinationType.WHATSAPP_AGENT
                            typeStr.contains("EMAIL") || typeStr.contains("SMTP") -> com.smsforwarder.app.domain.model.DestinationType.EMAIL
                            else -> com.smsforwarder.app.domain.model.DestinationType.WEBHOOK
                        }
                        val endpoint = rObj.optString("endpoint", "")
                        val isEnabled = rObj.optBoolean("isEnabled", true)
                        val filterTypeStr = rObj.optString("senderFilterType", "ALL").uppercase()
                        val filterType = when {
                            filterTypeStr.contains("ALLOW") || filterTypeStr.contains("WHITE") -> com.smsforwarder.app.domain.model.SenderFilterType.ALLOWLIST
                            filterTypeStr.contains("BLOCK") || filterTypeStr.contains("BLACK") -> com.smsforwarder.app.domain.model.SenderFilterType.BLOCKLIST
                            else -> com.smsforwarder.app.domain.model.SenderFilterType.ALL
                        }

                        val senderFilterValues = rObj.optString("senderFilterValues", "")
                        val keywordFilterValues = rObj.optString("keywordFilterValues", "")
                        val simSlotFilter = rObj.optInt("simSlotFilter", -1)
                        val customTemplate = rObj.optString("customTemplate").takeIf { it.isNotBlank() }

                        val secObj = rObj.optJSONObject("secrets") ?: org.json.JSONObject()
                        val secrets: DestinationSecrets = when (type) {
                            com.smsforwarder.app.domain.model.DestinationType.WEBHOOK -> {
                                DestinationSecrets.Webhook(
                                    authHeader = secObj.optString("authHeader").takeIf { it.isNotBlank() },
                                    customHeadersJson = secObj.optString("customHeadersJson").takeIf { it.isNotBlank() }
                                )
                            }
                            com.smsforwarder.app.domain.model.DestinationType.TELEGRAM -> {
                                DestinationSecrets.Telegram(
                                    botToken = secObj.optString("botToken", "")
                                )
                            }
                            com.smsforwarder.app.domain.model.DestinationType.WHATSAPP_AGENT -> {
                                DestinationSecrets.WhatsAppAgent(
                                    apiKey = secObj.optString("apiKey", ""),
                                    recipientId = secObj.optString("recipientId", endpoint)
                                )
                            }
                            com.smsforwarder.app.domain.model.DestinationType.EMAIL -> {
                                DestinationSecrets.Smtp(
                                    host = secObj.optString("host", "smtp.gmail.com"),
                                    port = secObj.optInt("port", 465),
                                    username = secObj.optString("username", ""),
                                    password = secObj.optString("password", ""),
                                    fromAddress = secObj.optString("fromAddress", ""),
                                    useStartTls = secObj.optBoolean("useStartTls", false)
                                )
                            }
                        }

                        vaultRepository.saveSecrets(vaultAlias, secrets)
                        val rule = com.smsforwarder.app.domain.model.ForwardingRule(
                            id = ruleId,
                            name = name,
                            isEnabled = isEnabled,
                            targetDestinationType = type,
                            destinationEndpoint = endpoint,
                            senderFilterType = filterType,
                            senderFilterValues = senderFilterValues,
                            keywordFilterValues = keywordFilterValues,
                            simSlotFilter = simSlotFilter,
                            customTemplate = customTemplate,
                            vaultAliasRef = vaultAlias,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        ruleRepository.insertOrUpdateRule(rule)
                        restoredRuleCount++
                    }
                }

                onResult(true, "Restored preferences and $restoredRuleCount forwarding rule(s) successfully.")
            } catch (e: Exception) {
                onResult(false, "Failed to restore backup: ${e.message}")
            }
        }
    }

    companion object {

        const val GLOBAL_WA_ALIAS = "wa_agent_global"

        fun maskApiKey(key: String): String {
            if (key.length <= 4) return "••••"
            return "••••••••" + key.takeLast(4)
        }
    }
}
