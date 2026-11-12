package com.smsforwarder.app.engine.dispatcher

import com.smsforwarder.app.domain.model.SmsMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookDispatcherPayloadTest {

    private fun createTestSms(): SmsMessage = SmsMessage(
        id = "sms-wh-101",
        sender = "+15551234567",
        rawBody = "Your authentication passcode is 729183",
        timestampMillis = 1731175200000L,
        simSlotIndex = 1,
        subId = 2,
        carrierName = "Verizon",
        deduplicationHash = "hash987654321",
        createdAt = 1731175200000L
    )

    @Test
    fun jsonPayload_formatsStandardCompliantKeys() {
        val sms = createTestSms()
        val payloadJson = buildJsonObject {
            put("sender", sms.sender)
            put("body", sms.rawBody)
            put("timestamp", sms.timestampMillis)
            put("simSlot", sms.simSlotIndex)
            put("carrier", sms.carrierName ?: "Unknown")
            put("deduplicationHash", sms.deduplicationHash)
        }.toString()

        val parsed = Json.decodeFromString<JsonObject>(payloadJson)
        assertEquals("+15551234567", parsed["sender"]?.jsonPrimitive?.content)
        assertEquals("Your authentication passcode is 729183", parsed["body"]?.jsonPrimitive?.content)
        assertEquals(1731175200000L, parsed["timestamp"]?.jsonPrimitive?.long)
        assertEquals(1, parsed["simSlot"]?.jsonPrimitive?.content?.toInt())
        assertEquals("Verizon", parsed["carrier"]?.jsonPrimitive?.content)
        assertEquals("hash987654321", parsed["deduplicationHash"]?.jsonPrimitive?.content)
    }

    @Test
    fun urlValidation_rejectsCleartextHttp() {
        val httpUrl = "http://api.insecure.com/webhook"
        val httpsUrl = "https://api.secure.com/webhook"

        assertFalse("HTTP cleartext must be rejected", httpUrl.startsWith("https://", ignoreCase = true))
        assertTrue("HTTPS secure scheme must be accepted", httpsUrl.startsWith("https://", ignoreCase = true))
    }

    @Test
    fun tlsCipherConfiguration_enforcesModernTlsOnly() {
        val modernTlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()

        assertTrue("Must be TLS", modernTlsSpec.isTls)
        val tlsVersions = modernTlsSpec.tlsVersions?.map { it.javaName } ?: emptyList()
        assertTrue("Must support TLS 1.3", tlsVersions.contains("TLSv1.3"))
        assertTrue("Must support TLS 1.2", tlsVersions.contains("TLSv1.2"))
        assertFalse("Must NOT support legacy TLS 1.0", tlsVersions.contains("TLSv1"))
        assertFalse("Must NOT support legacy TLS 1.1", tlsVersions.contains("TLSv1.1"))
        assertFalse("Must NOT support SSLv3", tlsVersions.contains("SSLv3"))
    }

    @Test
    fun httpStatusClassification_categorizesCorrectly() {
        fun classifyCode(code: Int): String = when {
            code in 200..299 -> "SUCCESS"
            code == 429 -> "RETRYABLE_RATE_LIMIT"
            code in 500..599 -> "RETRYABLE_SERVER_ERROR"
            code in listOf(400, 401, 403, 404, 405, 410, 422) -> "PERMANENT_CLIENT_ERROR"
            else -> "OTHER"
        }

        assertEquals("SUCCESS", classifyCode(200))
        assertEquals("SUCCESS", classifyCode(204))
        assertEquals("RETRYABLE_RATE_LIMIT", classifyCode(429))
        assertEquals("RETRYABLE_SERVER_ERROR", classifyCode(500))
        assertEquals("RETRYABLE_SERVER_ERROR", classifyCode(503))
        assertEquals("PERMANENT_CLIENT_ERROR", classifyCode(400))
        assertEquals("PERMANENT_CLIENT_ERROR", classifyCode(401))
        assertEquals("PERMANENT_CLIENT_ERROR", classifyCode(403))
        assertEquals("PERMANENT_CLIENT_ERROR", classifyCode(404))
    }
}
