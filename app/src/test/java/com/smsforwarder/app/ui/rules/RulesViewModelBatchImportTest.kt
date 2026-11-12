package com.smsforwarder.app.ui.rules

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.domain.model.DestinationSecrets
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.repository.ForwardingRuleRepository
import com.smsforwarder.app.domain.repository.SecureVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelBatchImportTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRuleRepository: FakeRuleRepository
    private lateinit var fakeVaultRepository: FakeSecureVaultRepository
    private lateinit var viewModel: RulesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRuleRepository = FakeRuleRepository()
        fakeVaultRepository = FakeSecureVaultRepository()
        viewModel = RulesViewModel(
            ruleRepository = fakeRuleRepository,
            vaultRepository = fakeVaultRepository,
            appPreferences = null
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun importRuleFromJson_directJsonArray_configuresAllProviders() {
        val jsonArray = """
        [
          {
            "name": "Discord Webhook",
            "type": "WEBHOOK",
            "endpoint": "https://discord.com/api/webhooks/123/abc",
            "template": "{\"content\": \"SMS from {senderNumber}: {body}\"}",
            "secrets": {
              "auth_header": "Bearer discord-secret"
            }
          },
          {
            "name": "Telegram Alert",
            "type": "TELEGRAM",
            "endpoint": "123456789",
            "bot_token": "987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            "template": "*Alert* from {senderNumber}: {body}"
          },
          {
            "name": "WhatsApp Agent",
            "type": "WHATSAPP_AGENT",
            "endpoint": "923001234567",
            "api_key": "wa_live_secret_key_12345"
          }
        ]
        """.trimIndent()

        var successMessage: String? = null
        var errorMessage: String? = null

        viewModel.importRuleFromJson(
            jsonString = jsonArray,
            onSuccess = { successMessage = it },
            onError = { errorMessage = it }
        )

        assertNull("Should not fail with error: $errorMessage", errorMessage)
        assertNotNull("Success message should be delivered", successMessage)
        assertEquals("Successfully configured 3 providers!", successMessage)

        // Verify rules in repository
        assertEquals(3, fakeRuleRepository.rules.size)

        val webhookRule = fakeRuleRepository.rules.find { it.name == "Discord Webhook" }
        assertNotNull("Discord Webhook rule must be created", webhookRule)
        assertEquals(DestinationType.WEBHOOK, webhookRule!!.targetDestinationType)
        assertEquals("https://discord.com/api/webhooks/123/abc", webhookRule.destinationEndpoint)
        assertTrue(webhookRule.isEnabled)

        val telegramRule = fakeRuleRepository.rules.find { it.name == "Telegram Alert" }
        assertNotNull("Telegram Alert rule must be created", telegramRule)
        assertEquals(DestinationType.TELEGRAM, telegramRule!!.targetDestinationType)
        assertEquals("123456789", telegramRule.destinationEndpoint)
        assertTrue(telegramRule.isEnabled)

        val waRule = fakeRuleRepository.rules.find { it.name == "WhatsApp Agent" }
        assertNotNull("WhatsApp Agent rule must be created", waRule)
        assertEquals(DestinationType.WHATSAPP_AGENT, waRule!!.targetDestinationType)
        assertEquals("923001234567", waRule.destinationEndpoint)
        assertTrue(waRule.isEnabled)

        // Verify secrets in vault
        val waSecrets = fakeVaultRepository.secretsMap[waRule.vaultAliasRef] as? DestinationSecrets.WhatsAppAgent
        assertNotNull("WhatsApp secrets must be encrypted in vault", waSecrets)
        assertEquals("wa_live_secret_key_12345", waSecrets!!.apiKey)
        assertEquals("user:923001234567", waSecrets.recipientId)

        val tgSecrets = fakeVaultRepository.secretsMap[telegramRule.vaultAliasRef] as? DestinationSecrets.Telegram
        assertNotNull("Telegram secrets must be encrypted in vault", tgSecrets)
        assertEquals("987654321:ABC-DEF1234ghIkl-zyx57W2v1u123ew11", tgSecrets!!.botToken)

        val whSecrets = fakeVaultRepository.secretsMap[webhookRule.vaultAliasRef] as? DestinationSecrets.Webhook
        assertNotNull("Webhook secrets must be encrypted in vault", whSecrets)
        assertEquals("Bearer discord-secret", whSecrets!!.authHeader)
    }

    @Test
    fun importRuleFromJson_objectWithProviders_configuresAllProviders() {
        val jsonPayload = """
        {
          "providers": [
            {
              "name": "Company Slack",
              "type": "WEBHOOK",
              "endpoint": "https://hooks.slack.com/services/T00/B00/X00",
              "template": "{\"text\": \"SMS: {body}\"}"
            },
            {
              "name": "Ops Telegram",
              "type": "TELEGRAM",
              "endpoint": "chat_ops_group",
              "bot_token": "tg_token_999"
            }
          ]
        }
        """.trimIndent()

        var successMessage: String? = null
        var errorMessage: String? = null

        viewModel.importRuleFromJson(
            jsonString = jsonPayload,
            onSuccess = { successMessage = it },
            onError = { errorMessage = it }
        )

        assertNull("Should not fail with error: $errorMessage", errorMessage)
        assertEquals("Successfully configured 2 providers!", successMessage)
        assertEquals(2, fakeRuleRepository.rules.size)
    }

    @Test
    fun importRuleFromJson_objectWithRulesArray_configuresAllRules() {
        val jsonPayload = """
        {
          "rules": [
            {
              "name": "Audit Webhook",
              "type": "WEBHOOK",
              "endpoint": "https://audit.internal/sms"
            },
            {
              "name": "WhatsApp Manager",
              "type": "WHATSAPP_AGENT",
              "endpoint": "user:ops-bot",
              "api_key": "wa_key_xyz"
            }
          ]
        }
        """.trimIndent()

        var successMessage: String? = null
        var errorMessage: String? = null

        viewModel.importRuleFromJson(
            jsonString = jsonPayload,
            onSuccess = { successMessage = it },
            onError = { errorMessage = it }
        )

        assertNull("Should not fail with error: $errorMessage", errorMessage)
        assertEquals("Successfully configured 2 providers!", successMessage)
        assertEquals(2, fakeRuleRepository.rules.size)
    }

    @Test
    fun importRuleFromJson_singleRuleObject_maintainsBackwardCompatibility() {
        val singleJson = """
        {
          "name": "Legacy Single Rule",
          "type": "WEBHOOK",
          "endpoint": "https://legacy.example.com/webhook",
          "auth_header": "Token secret123"
        }
        """.trimIndent()

        var successMessage: String? = null
        var errorMessage: String? = null

        viewModel.importRuleFromJson(
            jsonString = singleJson,
            onSuccess = { successMessage = it },
            onError = { errorMessage = it }
        )

        assertNull("Should not fail: $errorMessage", errorMessage)
        assertEquals("Rule 'Legacy Single Rule' successfully created and activated!", successMessage)
        assertEquals(1, fakeRuleRepository.rules.size)
        assertEquals("https://legacy.example.com/webhook", fakeRuleRepository.rules.first().destinationEndpoint)
    }

    @Test
    fun importRuleFromJson_missingEndpoint_returnsValidationError() {
        val invalidJson = """
        [
          {
            "name": "Invalid Provider Without Endpoint",
            "type": "WEBHOOK"
          }
        ]
        """.trimIndent()

        var successMessage: String? = null
        var errorMessage: String? = null

        viewModel.importRuleFromJson(
            jsonString = invalidJson,
            onSuccess = { successMessage = it },
            onError = { errorMessage = it }
        )

        assertNull(successMessage)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("missing an endpoint"))
        assertEquals(0, fakeRuleRepository.rules.size)
    }

    @Test
    fun importRuleFromJson_telegramMissingBotToken_returnsValidationError() {
        val invalidTelegramJson = """
        {
          "providers": [
            {
              "name": "Broken Telegram",
              "type": "TELEGRAM",
              "endpoint": "123456"
            }
          ]
        }
        """.trimIndent()

        var successMessage: String? = null
        var errorMessage: String? = null

        viewModel.importRuleFromJson(
            jsonString = invalidTelegramJson,
            onSuccess = { successMessage = it },
            onError = { errorMessage = it }
        )

        assertNull(successMessage)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("bot_token"))
        assertEquals(0, fakeRuleRepository.rules.size)
    }

    @Test
    fun importRuleFromJson_whatsappMissingApiKey_returnsValidationError() {
        val invalidWaJson = """
        [
          {
            "name": "Broken WhatsApp",
            "type": "WHATSAPP_AGENT",
            "endpoint": "user:123"
          }
        ]
        """.trimIndent()

        var successMessage: String? = null
        var errorMessage: String? = null

        viewModel.importRuleFromJson(
            jsonString = invalidWaJson,
            onSuccess = { successMessage = it },
            onError = { errorMessage = it }
        )

        assertNull(successMessage)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("api_key"))
        assertEquals(0, fakeRuleRepository.rules.size)
    }

    @Test
    fun importRuleFromJson_malformedJson_returnsError() {
        var successMessage: String? = null
        var errorMessage: String? = null

        viewModel.importRuleFromJson(
            jsonString = "{ not valid json ...",
            onSuccess = { successMessage = it },
            onError = { errorMessage = it }
        )

        assertNull(successMessage)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("Invalid JSON format"))
    }

    private class FakeRuleRepository : ForwardingRuleRepository {
        val rules = mutableListOf<ForwardingRule>()

        override fun getAllRulesFlow(): Flow<List<ForwardingRule>> = flowOf(rules)
        override suspend fun getActiveRules(): List<ForwardingRule> = rules.filter { it.isEnabled }
        override suspend fun getRuleById(id: String): ForwardingRule? = rules.find { it.id == id }

        override suspend fun insertOrUpdateRule(rule: ForwardingRule): AppResult<Unit> {
            val idx = rules.indexOfFirst { it.id == rule.id }
            if (idx != -1) {
                rules[idx] = rule
            } else {
                rules.add(rule)
            }
            return AppResult.Success(Unit)
        }

        override suspend fun updateRuleEnabled(id: String, isEnabled: Boolean): AppResult<Unit> {
            val idx = rules.indexOfFirst { it.id == id }
            if (idx != -1) {
                rules[idx] = rules[idx].copy(isEnabled = isEnabled)
            }
            return AppResult.Success(Unit)
        }

        override suspend fun deleteRule(id: String): AppResult<Unit> {
            rules.removeIf { it.id == id }
            return AppResult.Success(Unit)
        }

        override suspend fun deleteAllRules() {
            rules.clear()
        }
    }

    private class FakeSecureVaultRepository : SecureVaultRepository {
        val secretsMap = mutableMapOf<String, DestinationSecrets>()

        override suspend fun saveSecrets(aliasRef: String, secrets: DestinationSecrets): AppResult<Unit> {
            secretsMap[aliasRef] = secrets
            return AppResult.Success(Unit)
        }

        override suspend fun getSecrets(aliasRef: String): AppResult<DestinationSecrets> {
            val s = secretsMap[aliasRef] ?: return AppResult.Failure(com.smsforwarder.app.core.util.DomainError.SecurityVaultError("Not found"))
            return AppResult.Success(s)
        }

        override suspend fun deleteSecrets(aliasRef: String): AppResult<Unit> {
            secretsMap.remove(aliasRef)
            return AppResult.Success(Unit)
        }

        override suspend fun clearVault(): AppResult<Unit> {
            secretsMap.clear()
            return AppResult.Success(Unit)
        }
    }
}
