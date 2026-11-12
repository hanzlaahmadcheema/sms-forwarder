package com.smsforwarder.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityLogTreeTest {

    @Test
    fun sanitize_redactsTelegramBotToken() {
        val rawMessage = "Requesting sendMessage to https://api.telegram.org/bot123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345678/sendMessage"
        val sanitized = SecurityLogTree.sanitize(rawMessage)

        assertFalse("Sanitized string must not contain actual bot token", sanitized.contains("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345678"))
        assertTrue("Sanitized string must contain redacted placeholder", sanitized.contains("[REDACTED_BOT_TOKEN]"))
    }

    @Test
    fun sanitize_redactsBearerTokens() {
        val rawMessage = "Setting request header Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abc.xyz"
        val sanitized = SecurityLogTree.sanitize(rawMessage)

        assertFalse("Sanitized string must not contain raw bearer token", sanitized.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abc.xyz"))
        assertTrue("Sanitized string must contain Bearer [REDACTED_TOKEN]", sanitized.contains("Bearer [REDACTED_TOKEN]"))
    }

    @Test
    fun sanitize_redactsBasicAuth() {
        val rawMessage = "Setting request header Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ="
        val sanitized = SecurityLogTree.sanitize(rawMessage)

        assertFalse("Sanitized string must not contain raw base64 basic credentials", sanitized.contains("dXNlcm5hbWU6cGFzc3dvcmQ="))
        assertTrue("Sanitized string must contain Basic [REDACTED]", sanitized.contains("Basic [REDACTED]"))
    }

    @Test
    fun sanitize_masksPhoneNumbers() {
        val rawMessage = "Incoming SMS from +15551234567 processed"
        val sanitized = SecurityLogTree.sanitize(rawMessage)

        assertFalse("Sanitized string must not reveal full telephone number", sanitized.contains("5551234"))
        assertTrue("Sanitized string should preserve country code prefix and suffix with mask", sanitized.contains("+15***67") || sanitized.contains("***67"))
    }

    @Test
    fun sanitize_masksOtpCodes() {
        val rawMessage = "Your security OTP code is 948201 for login"
        val sanitized = SecurityLogTree.sanitize(rawMessage)

        assertFalse("Sanitized string must not expose 6-digit OTP code", sanitized.contains("948201"))
        assertTrue("Sanitized string must replace OTP with [REDACTED_CODE]", sanitized.contains("[REDACTED_CODE]"))
    }

    @Test
    fun sanitize_preservesNonSensitiveText() {
        val regularMessage = "Worker cycle completed successfully with 0 remaining tasks"
        val sanitized = SecurityLogTree.sanitize(regularMessage)
        assertEquals("Non-sensitive messages should remain intact", regularMessage, sanitized)
    }
}
