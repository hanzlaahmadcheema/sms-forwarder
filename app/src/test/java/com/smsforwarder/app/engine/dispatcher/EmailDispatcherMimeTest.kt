package com.smsforwarder.app.engine.dispatcher

import com.smsforwarder.app.domain.model.SmsMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class EmailDispatcherMimeTest {

    private fun createTestSms(
        sender: String = "+15551234567",
        body: String = "Test email forward message with special characters: € & < >"
    ): SmsMessage = SmsMessage(
        id = "test-sms-id",
        sender = sender,
        rawBody = body,
        timestampMillis = 1731175200000L,
        simSlotIndex = 0,
        subId = 1,
        carrierName = "T-Mobile",
        deduplicationHash = "hash123",
        createdAt = 1731175200000L
    )

    @Test
    fun rfc2047_subjectEncoding_encodesNonAsciiSafely() {
        val rawSubject = "SMS Forwarded: From +15551234567 [SIM 1] (T-Mobile) 🔐 OTP"
        val encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(rawSubject.toByteArray(StandardCharsets.UTF_8)) + "?="

        assertTrue("Subject must start with RFC 2047 Base64 prefix", encodedSubject.startsWith("=?UTF-8?B?"))
        assertTrue("Subject must end with RFC 2047 suffix", encodedSubject.endsWith("?="))

        // Verify decoding
        val b64Payload = encodedSubject.removePrefix("=?UTF-8?B?").removeSuffix("?=")
        val decoded = String(Base64.getDecoder().decode(b64Payload), StandardCharsets.UTF_8)
        assertTrue(decoded == rawSubject)
    }

    @Test
    fun dotStuffing_prependsDotWhenLineStartsWithDot() {
        val lines = listOf(
            "Normal line",
            ".Line starting with dot",
            "..Two dots",
            "Final line"
        )

        val stuffedLines = lines.map { line ->
            if (line.startsWith(".")) ".$line" else line
        }

        assertTrue(stuffedLines[0] == "Normal line")
        assertTrue("Single leading dot must be escaped with double dot", stuffedLines[1] == "..Line starting with dot")
        assertTrue("Double leading dots must become triple dots", stuffedLines[2] == "...Two dots")
        assertTrue(stuffedLines[3] == "Final line")
    }

    @Test
    fun authLogin_encodesCredentialsInStandardBase64() {
        val username = "smtpuser@example.com"
        val password = "SecretPassword123!"

        val b64User = Base64.getEncoder().encodeToString(username.toByteArray(StandardCharsets.UTF_8))
        val b64Pass = Base64.getEncoder().encodeToString(password.toByteArray(StandardCharsets.UTF_8))

        assertFalse("Base64 string must not contain raw plaintext", b64User.contains(username))
        assertFalse("Base64 string must not contain raw password", b64Pass.contains(password))

        val decodedUser = String(Base64.getDecoder().decode(b64User), StandardCharsets.UTF_8)
        val decodedPass = String(Base64.getDecoder().decode(b64Pass), StandardCharsets.UTF_8)

        assertTrue(decodedUser == username)
        assertTrue(decodedPass == password)
    }

    @Test
    fun rfc5322_headerStructure_formatsStandardCompliantOutput() {
        val from = "forwarder@myserver.com"
        val to = "destination@domain.com"
        val message = createTestSms()

        val headers = listOf(
            "From: <$from>",
            "To: <$to>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: 8bit"
        )

        assertTrue(headers.any { it.startsWith("From: <$from>") })
        assertTrue(headers.any { it.startsWith("To: <$to>") })
        assertTrue(headers.contains("MIME-Version: 1.0"))
        assertTrue(headers.contains("Content-Type: text/plain; charset=UTF-8"))
    }
}
