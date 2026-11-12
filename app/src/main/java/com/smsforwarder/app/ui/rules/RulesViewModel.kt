package com.smsforwarder.app.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.data.local.prefs.AppPreferences
import com.smsforwarder.app.domain.model.DestinationSecrets
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SenderFilterType
import com.smsforwarder.app.domain.repository.ForwardingRuleRepository
import com.smsforwarder.app.domain.repository.SecureVaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

data class RulesUiState(
    val rules: List<ForwardingRule> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class RulesViewModel(
    private val ruleRepository: ForwardingRuleRepository,
    private val vaultRepository: SecureVaultRepository,
    private val appPreferences: AppPreferences? = null
) : ViewModel() {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val uiState: StateFlow<RulesUiState> = ruleRepository.getAllRulesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        ).let { flow ->
            MutableStateFlow(RulesUiState()).apply {
                viewModelScope.launch {
                    flow.collect { rulesList ->
                        value = RulesUiState(rules = rulesList, isLoading = false)
                    }
                }
            }
        }.asStateFlow()

    fun toggleRule(ruleId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            ruleRepository.updateRuleEnabled(ruleId, isEnabled)
        }
    }

    fun deleteRule(ruleId: String, vaultAliasRef: String = "") {
        viewModelScope.launch {
            ruleRepository.deleteRule(ruleId)
            if (vaultAliasRef.isNotBlank()) {
                vaultRepository.deleteSecrets(vaultAliasRef)
            }
        }
    }

    suspend fun loadRule(ruleId: String): ForwardingRule? {
        return ruleRepository.getRuleById(ruleId)
    }

    suspend fun loadSecrets(aliasRef: String): DestinationSecrets? {
        val result = vaultRepository.getSecrets(aliasRef)
        return if (result is AppResult.Success) result.data else null
    }

    fun saveRule(
        existingId: String?,
        name: String,
        destinationType: DestinationType,
        destinationEndpoint: String,
        senderFilterType: SenderFilterType,
        senderFilterValues: String,
        keywordFilterValues: String,
        simSlotFilter: Int,
        customTemplate: String?,
        secrets: DestinationSecrets,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val ruleId = existingId ?: UUID.randomUUID().toString()
            val vaultAlias = "rule_$ruleId"

            val vaultResult = vaultRepository.saveSecrets(vaultAlias, secrets)
            if (vaultResult is AppResult.Failure) {
                onError("Failed to securely store credentials: ${vaultResult.error.message}")
                return@launch
            }

            val rule = ForwardingRule(
                id = ruleId,
                name = name,
                isEnabled = true,
                targetDestinationType = destinationType,
                destinationEndpoint = destinationEndpoint,
                senderFilterType = senderFilterType,
                senderFilterValues = senderFilterValues,
                keywordFilterValues = keywordFilterValues,
                simSlotFilter = simSlotFilter,
                customTemplate = customTemplate,
                vaultAliasRef = vaultAlias,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val dbResult = ruleRepository.insertOrUpdateRule(rule)
            if (dbResult is AppResult.Success) {
                onSuccess()
            } else if (dbResult is AppResult.Failure) {
                onError("Failed to save rule: ${dbResult.error.message}")
            }
        }
    }

    fun saveRuleWithSecrets(
        rule: ForwardingRule,
        secrets: DestinationSecrets,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val vaultResult = vaultRepository.saveSecrets(rule.vaultAliasRef, secrets)
            if (vaultResult is AppResult.Failure) {
                onError("Failed to encrypt secrets: ${vaultResult.error.message}")
                return@launch
            }

            val dbResult = ruleRepository.insertOrUpdateRule(rule)
            if (dbResult is AppResult.Success) {
                onSuccess()
            } else if (dbResult is AppResult.Failure) {
                onError("Failed to save rule: ${dbResult.error.message}")
            }
        }
    }

    private fun getPreferences(): AppPreferences? {
        return appPreferences ?: try {
            com.smsforwarder.app.SmsForwarderApp.instance.appPreferences
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonObject.getStringOrNull(vararg keys: String): String? {
        for (k in keys) {
            val element = this[k]
            if (element != null && element is JsonPrimitive) {
                val content = element.contentOrNull?.trim()
                if (!content.isNullOrEmpty()) return content
            }
        }
        return null
    }

    private fun JsonObject.getIntOrNull(vararg keys: String): Int? {
        for (k in keys) {
            val element = this[k]
            if (element != null && element is JsonPrimitive) {
                val intVal = element.intOrNull
                if (intVal != null) return intVal
                val strVal = element.contentOrNull?.toIntOrNull()
                if (strVal != null) return strVal
            }
        }
        return null
    }

    private fun JsonObject.getBooleanOrNull(vararg keys: String): Boolean? {
        for (k in keys) {
            val element = this[k]
            if (element != null && element is JsonPrimitive) {
                val boolVal = element.booleanOrNull
                if (boolVal != null) return boolVal
                val strVal = element.contentOrNull?.toBooleanStrictOrNull()
                if (strVal != null) return strVal
            }
        }
        return null
    }

    private fun JsonObject.getObjectOrNull(vararg keys: String): JsonObject? {
        for (k in keys) {
            val element = this[k]
            if (element != null && element is JsonObject) {
                return element
            }
        }
        return null
    }

    private fun JsonObject.getArrayOrNull(vararg keys: String): JsonArray? {
        for (k in keys) {
            val element = this[k]
            if (element != null && element is JsonArray) {
                return element
            }
        }
        return null
    }

    private fun hasRuleFields(json: JsonObject): Boolean {
        return json.containsKey("endpoint") ||
                json.containsKey("destinationEndpoint") ||
                json.containsKey("url") ||
                json.containsKey("chat_id") ||
                json.containsKey("chatId") ||
                json.containsKey("recipient") ||
                json.containsKey("recipient_id") ||
                json.containsKey("recipientId") ||
                json.containsKey("type") ||
                json.containsKey("destinationType")
    }

    private fun parseSingleProvider(json: JsonObject): Pair<ForwardingRule, DestinationSecrets> {
        val name = json.getStringOrNull("name") ?: "Imported Destination Rule"
        val typeStr = (json.getStringOrNull("type", "destinationType", "targetDestinationType") ?: "WEBHOOK").uppercase()

        val destinationType = when {
            typeStr.contains("TELEGRAM") -> DestinationType.TELEGRAM
            typeStr.contains("WHATSAPP") -> DestinationType.WHATSAPP_AGENT
            typeStr.contains("EMAIL") || typeStr.contains("SMTP") -> DestinationType.EMAIL
            typeStr.contains("WEBHOOK") || typeStr.contains("HTTP") || typeStr.contains("REST") -> DestinationType.WEBHOOK
            else -> DestinationType.WEBHOOK
        }

        val endpoint = json.getStringOrNull(
            "endpoint",
            "destinationEndpoint",
            "url",
            "chatId",
            "chat_id",
            "recipient",
            "recipient_id",
            "recipientId",
            "to"
        )

        if (endpoint.isNullOrBlank()) {
            throw IllegalArgumentException("Rule '$name' is missing an endpoint/URL/Chat ID in JSON.")
        }

        val template = json.getStringOrNull("template", "customTemplate", "custom_template")

        val simSlot = json.getIntOrNull("sim_slot", "simSlotFilter", "simSlot") ?: -1

        val filterTypeStr = (json.getStringOrNull("sender_filter", "senderFilterType") ?: "ALL").uppercase()
        val senderFilterType = when {
            filterTypeStr.contains("ALLOW") || filterTypeStr.contains("WHITE") -> SenderFilterType.ALLOWLIST
            filterTypeStr.contains("BLOCK") || filterTypeStr.contains("BLACK") -> SenderFilterType.BLOCKLIST
            else -> SenderFilterType.ALL
        }

        val senderFilterValues = json.getStringOrNull("sender_filter_values", "senderFilterValues") ?: ""
        val keywordFilterValues = json.getStringOrNull("keyword_filter_values", "keywordFilterValues") ?: ""

        val secretsObj = json.getObjectOrNull("secrets")

        val secrets: DestinationSecrets = when (destinationType) {
            DestinationType.WEBHOOK -> {
                val auth = secretsObj?.getStringOrNull("auth_header", "authHeader", "authorization")
                    ?: json.getStringOrNull("auth_header", "authHeader", "authorization")
                val customHeaders = secretsObj?.getStringOrNull("custom_headers", "customHeadersJson")
                    ?: secretsObj?.getObjectOrNull("custom_headers", "headers")?.toString()
                    ?: json.getStringOrNull("custom_headers", "customHeadersJson")
                    ?: json.getObjectOrNull("custom_headers", "headers")?.toString()
                DestinationSecrets.Webhook(authHeader = auth, customHeadersJson = customHeaders)
            }
            DestinationType.TELEGRAM -> {
                val botToken = secretsObj?.getStringOrNull("bot_token", "botToken", "token")
                    ?: json.getStringOrNull("bot_token", "botToken", "token")
                if (botToken.isNullOrBlank()) {
                    throw IllegalArgumentException("Telegram rule '$name' requires 'bot_token' in JSON.")
                }
                DestinationSecrets.Telegram(botToken = botToken)
            }
            DestinationType.WHATSAPP_AGENT -> {
                val apiKey = secretsObj?.getStringOrNull("api_key", "apiKey", "key")
                    ?: json.getStringOrNull("api_key", "apiKey", "key")
                if (apiKey.isNullOrBlank()) {
                    throw IllegalArgumentException("WhatsApp Agent rule '$name' requires 'api_key' in JSON.")
                }
                val recipient = secretsObj?.getStringOrNull("recipient_id", "recipientId")
                    ?: endpoint
                val formattedRecipient = if (recipient.startsWith("user:", ignoreCase = true)) {
                    recipient.trim()
                } else {
                    "user:${recipient.trim()}"
                }
                DestinationSecrets.WhatsAppAgent(apiKey = apiKey, recipientId = formattedRecipient)
            }
            DestinationType.EMAIL -> {
                val host = secretsObj?.getStringOrNull("host") ?: json.getStringOrNull("host") ?: "smtp.gmail.com"
                val port = secretsObj?.getIntOrNull("port") ?: json.getIntOrNull("port") ?: 465
                val user = secretsObj?.getStringOrNull("username") ?: json.getStringOrNull("username") ?: ""
                val pass = secretsObj?.getStringOrNull("password") ?: json.getStringOrNull("password") ?: ""
                val from = secretsObj?.getStringOrNull("from_address") ?: json.getStringOrNull("from_address") ?: user
                val startTls = secretsObj?.getBooleanOrNull("use_starttls") ?: json.getBooleanOrNull("use_starttls") ?: false
                DestinationSecrets.Smtp(
                    host = host,
                    port = port,
                    username = user,
                    password = pass,
                    fromAddress = from,
                    useStartTls = startTls
                )
            }
        }

        val ruleId = UUID.randomUUID().toString()
        val vaultAlias = "rule_$ruleId"

        val rule = ForwardingRule(
            id = ruleId,
            name = name,
            isEnabled = true,
            targetDestinationType = destinationType,
            destinationEndpoint = endpoint,
            senderFilterType = senderFilterType,
            senderFilterValues = senderFilterValues,
            keywordFilterValues = keywordFilterValues,
            simSlotFilter = simSlot,
            customTemplate = template,
            vaultAliasRef = vaultAlias,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        return Pair(rule, secrets)
    }

    private fun applySettingsFromJson(settingsObj: JsonObject): Boolean {
        val prefs = getPreferences() ?: return false
        var updated = false

        settingsObj.getStringOrNull("defaultTemplate", "default_template")?.let {
            prefs.defaultTemplate = it
            updated = true
        }
        settingsObj.getIntOrNull("retentionDays", "retention_days")?.let {
            prefs.retentionDays = it
            updated = true
        }
        settingsObj.getIntOrNull("deduplicationWindowSeconds", "deduplication_window_seconds")?.let {
            prefs.deduplicationWindowSeconds = it
            updated = true
        }
        settingsObj.getIntOrNull("networkTimeoutSeconds", "network_timeout_seconds")?.let {
            prefs.networkTimeoutSeconds = it
            updated = true
        }
        settingsObj.getBooleanOrNull("telegramRetryOnTimeout", "telegram_retry_on_timeout")?.let {
            prefs.telegramRetryOnTimeout = it
            updated = true
        }
        settingsObj.getBooleanOrNull("ephemeralMode", "ephemeral_mode")?.let {
            prefs.isEphemeralMode = it
            updated = true
        }

        return updated
    }

    fun importRuleFromJson(
        jsonString: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val trimmed = jsonString.trim()
                if (trimmed.isEmpty()) {
                    onError("Please paste a valid JSON configuration.")
                    return@launch
                }

                val parsedItems = mutableListOf<Pair<ForwardingRule, DestinationSecrets>>()
                var settingsUpdated = false

                val rootElement = jsonParser.parseToJsonElement(trimmed)

                if (rootElement is JsonArray) {
                    if (rootElement.isEmpty()) {
                        onError("JSON array is empty. Please provide at least one provider configuration.")
                        return@launch
                    }
                    for ((index, item) in rootElement.withIndex()) {
                        val itemObj = item as? JsonObject
                            ?: throw IllegalArgumentException("Element at index $index is not a valid JSON object.")
                        parsedItems.add(parseSingleProvider(itemObj))
                    }
                } else if (rootElement is JsonObject) {
                    val settingsObj = rootElement.getObjectOrNull("settings", "preferences")
                    if (settingsObj != null) {
                        settingsUpdated = applySettingsFromJson(settingsObj)
                    }

                    val providersArray = rootElement.getArrayOrNull("providers", "rules", "destinations", "forwarders")
                    if (providersArray != null) {
                        if (providersArray.isEmpty() && !settingsUpdated) {
                            onError("Providers array is empty. Please provide at least one provider configuration.")
                            return@launch
                        }
                        for ((index, item) in providersArray.withIndex()) {
                            val itemObj = item as? JsonObject
                                ?: throw IllegalArgumentException("Provider at index $index is not a valid JSON object.")
                            parsedItems.add(parseSingleProvider(itemObj))
                        }
                    } else if (hasRuleFields(rootElement)) {
                        parsedItems.add(parseSingleProvider(rootElement))
                    } else if (!settingsUpdated) {
                        onError("Invalid JSON structure: missing 'providers' array or rule endpoint.")
                        return@launch
                    }
                } else {
                    onError("Invalid JSON format: root element must be a JSON object or array.")
                    return@launch
                }

                // Persist all parsed providers: encrypt secrets in vault and insert into Room
                var configuredCount = 0
                for ((rule, secrets) in parsedItems) {
                    val vaultResult = vaultRepository.saveSecrets(rule.vaultAliasRef, secrets)
                    if (vaultResult is AppResult.Failure) {
                        onError("Failed to encrypt credentials for '${rule.name}': ${vaultResult.error.message}")
                        return@launch
                    }

                    val dbResult = ruleRepository.insertOrUpdateRule(rule)
                    if (dbResult is AppResult.Failure) {
                        onError("Database error saving '${rule.name}': ${dbResult.error.message}")
                        return@launch
                    }
                    configuredCount++
                }

                // Generate user-friendly summary message
                val message = when {
                    configuredCount > 1 && settingsUpdated ->
                        "Successfully configured $configuredCount providers and updated settings!"
                    configuredCount > 1 ->
                        "Successfully configured $configuredCount providers!"
                    configuredCount == 1 && settingsUpdated ->
                        "Rule '${parsedItems.first().first.name}' successfully created and settings updated!"
                    configuredCount == 1 ->
                        "Rule '${parsedItems.first().first.name}' successfully created and activated!"
                    settingsUpdated ->
                        "Settings successfully updated!"
                    else ->
                        "Operation completed successfully."
                }

                onSuccess(message)
            } catch (e: Exception) {
                onError("Invalid JSON format: ${e.message}")
            }
        }
    }
}
