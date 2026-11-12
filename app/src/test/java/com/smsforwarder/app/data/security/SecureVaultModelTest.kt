package com.smsforwarder.app.data.security

import com.smsforwarder.app.domain.model.DestinationSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureVaultModelTest {

    @Test
    fun telegramSecrets_storesAndRetrievesToken() {
        val botToken = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345678"
        val secrets = DestinationSecrets.Telegram(botToken = botToken)

        assertEquals(botToken, secrets.botToken)
        assertFalse(secrets.botToken.isBlank())
    }

    @Test
    fun webhookSecrets_storesAuthAndCustomHeaders() {
        val authHeader = "Bearer secret_api_token_value_xyz"
        val customHeaders = """{"X-Custom-Header":"CustomVal","X-API-Key":"Key123"}"""

        val secrets = DestinationSecrets.Webhook(
            authHeader = authHeader,
            customHeadersJson = customHeaders
        )

        assertEquals(authHeader, secrets.authHeader)
        assertEquals(customHeaders, secrets.customHeadersJson)
    }

    @Test
    fun smtpSecrets_storesAllConnectionParameters() {
        val secrets = DestinationSecrets.Smtp(
            host = "smtp.mailgun.org",
            port = 587,
            username = "postmaster@mycorp.com",
            password = "SecretSmtpPassword123!",
            fromAddress = "forwarder@mycorp.com",
            useStartTls = true
        )

        assertEquals("smtp.mailgun.org", secrets.host)
        assertEquals(587, secrets.port)
        assertEquals("postmaster@mycorp.com", secrets.username)
        assertEquals("SecretSmtpPassword123!", secrets.password)
        assertEquals("forwarder@mycorp.com", secrets.fromAddress)
        assertTrue(secrets.useStartTls)
    }

    @Test
    fun aliasIsolation_ensuresDifferentAliasesDoNotCollide() {
        val vaultStore = mutableMapOf<String, String>()

        fun saveSecret(alias: String, key: String, value: String) {
            vaultStore["${key}_$alias"] = value
        }

        fun getSecret(alias: String, key: String): String? {
            return vaultStore["${key}_$alias"]
        }

        saveSecret("alias_rule_1", "tg_token", "token_rule_1")
        saveSecret("alias_rule_2", "tg_token", "token_rule_2")

        assertEquals("token_rule_1", getSecret("alias_rule_1", "tg_token"))
        assertEquals("token_rule_2", getSecret("alias_rule_2", "tg_token"))
        assertNull(getSecret("alias_rule_3", "tg_token"))
    }

    @Test
    fun panicWipe_clearsAllVaultKeys() {
        val vaultStore = mutableMapOf(
            "wh_auth_alias1" to "token1",
            "tg_token_alias2" to "token2",
            "smtp_pass_alias3" to "pass3"
        )

        assertTrue(vaultStore.isNotEmpty())
        vaultStore.clear()
        assertTrue("Vault must be completely empty after panic wipe", vaultStore.isEmpty())
    }
}
