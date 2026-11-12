package com.smsforwarder.app.engine.dispatcher

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.data.security.SecureVault
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.DestinationSecrets
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.model.TaskStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class WhatsAppAgentDispatcherTest {

    private lateinit var secureVault: SecureVault
    private lateinit var mockOkHttpClient: OkHttpClient
    private lateinit var mockCall: Call
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Before
    fun setUp() {
        secureVault = mockk(relaxed = true)
        mockOkHttpClient = mockk(relaxed = true)
        mockCall = mockk(relaxed = true)
        every { mockOkHttpClient.newCall(any()) } returns mockCall
        WhatsAppAgentDispatcher.RatePacer.resetForTesting()
    }

    private fun createTestSms(
        sender: String = "+15551234567",
        body: String = "Your banking security code is 839201"
    ): SmsMessage = SmsMessage(
        id = "sms-wa-001",
        sender = sender,
        rawBody = body,
        timestampMillis = 1731175200000L,
        simSlotIndex = 0,
        subId = 1,
        carrierName = "CarrierX",
        deduplicationHash = "hash-wa-001",
        createdAt = 1731175200000L
    )

    private fun createTestTask(
        endpoint: String = "user:987654321",
        alias: String = "wa_alias_1"
    ): DeliveryTask = DeliveryTask(
        id = "task-wa-001",
        smsRecordId = "sms-wa-001",
        ruleId = "rule-wa-001",
        destinationType = DestinationType.WHATSAPP_AGENT,
        destinationEndpoint = endpoint,
        status = TaskStatus.PENDING,
        attemptCount = 0,
        lastAttemptAt = null,
        nextRetryAt = null,
        errorMessage = null,
        vaultSecretAlias = alias,
        createdAt = 1731175200000L
    )

    private fun createMockResponse(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val request = Request.Builder().url("https://api.whatsapp.com/agent/v1/messages").build()
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(jsonMediaType))

        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.build()
    }

    @Test
    fun formatMessageBody_formatsStandardFramingWithSenderBound() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val sms = createTestSms(
            sender = "+15551234567",
            body = "Verification PIN: 482910"
        )

        val formatted = dispatcher.formatMessageBody(sms, null)
        val expected = "New SMS received\nFrom: +15551234567\n\n\nVerification PIN: 482910"
        assertEquals(expected, formatted)
    }

    @Test
    fun formatMessageBody_truncatesSenderAt100Chars() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val oversizedSender = "SENDER_".repeat(30) // 210 chars
        val sms = createTestSms(sender = oversizedSender, body = "Hello")

        val formatted = dispatcher.formatMessageBody(sms, null)
        assertTrue(formatted.contains("From: " + oversizedSender.take(100)))
        assertFalse(formatted.contains(oversizedSender))
    }

    @Test
    fun formatMessageBody_truncatesBodyAt4096Chars() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val longBody = "A".repeat(5000)
        val sms = createTestSms(body = longBody)

        val formatted = dispatcher.formatMessageBody(sms, null)
        assertEquals(4096, formatted.length)
        assertTrue(formatted.startsWith("New SMS received\nFrom: +15551234567\n\n\n"))
    }

    @Test
    fun formatMessageBody_rendersCustomTemplateWhenProvided() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val sms = createTestSms(sender = "GOOGLE", body = "G-123456 is your code")
        val rule = ForwardingRule(
            id = "rule-1",
            name = "Google OTP",
            isEnabled = true,
            targetDestinationType = DestinationType.WHATSAPP_AGENT,
            destinationEndpoint = "user:123",
            customTemplate = "Forwarded OTP: {{body}} from {{sender}}",
            vaultAliasRef = "alias"
        )

        val formatted = dispatcher.formatMessageBody(sms, rule)
        assertEquals("Forwarded OTP: G-123456 is your code from GOOGLE", formatted)
    }

    @Test
    fun formatParticipantId_enforcesUserPrefix() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)

        assertEquals("user:123456789", dispatcher.formatParticipantId("user:123456789"))
        assertEquals("user:123456789", dispatcher.formatParticipantId("123456789"))
        assertEquals("user:+15551234567", dispatcher.formatParticipantId("+15551234567"))
    }

    @Test
    fun extractParticipantFromUpdates_parsesFromMessagesArray() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val updatesJson = """
            {
                "messages": [
                    {
                        "from": "user:123456789",
                        "id": "wamid.ABC",
                        "timestamp": "1731175200",
                        "type": "text",
                        "text": { "body": "hi" }
                    }
                ]
            }
        """.trimIndent()

        val participant = dispatcher.extractParticipantFromUpdates(updatesJson)
        assertEquals("user:123456789", participant)
    }

    @Test
    fun extractParticipantFromUpdates_parsesFromStatusesRecipientId() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val updatesJson = """
            {
                "messages": [],
                "statuses": [
                    {
                        "recipient_id": "987654321",
                        "status": "delivered"
                    }
                ]
            }
        """.trimIndent()

        val participant = dispatcher.extractParticipantFromUpdates(updatesJson)
        assertEquals("user:987654321", participant)
    }

    @Test
    fun extractParticipantFromUpdates_returnsNullWhenEmpty() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val emptyJson = """{"messages": [], "statuses": []}"""

        val participant = dispatcher.extractParticipantFromUpdates(emptyJson)
        assertNull(participant)
    }

    @Test
    fun extractParticipantFromUpdates_parsesFromNestedUserObject() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val updatesJson = """
            {
                "updates": [
                    {
                        "user": {
                            "id": "11223344"
                        }
                    }
                ]
            }
        """.trimIndent()

        val participant = dispatcher.extractParticipantFromUpdates(updatesJson)
        assertEquals("user:11223344", participant)
    }

    @Test
    fun extractParticipantFromUpdates_parsesFromUserIdField() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val updatesJson = """
            {
                "data": [
                    {
                        "user_id": "99887766"
                    }
                ]
            }
        """.trimIndent()

        val participant = dispatcher.extractParticipantFromUpdates(updatesJson)
        assertEquals("user:99887766", participant)
    }

    @Test
    fun extractParticipantFromUpdates_parsesFromEntryContactsWaId() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val updatesJson = """
            {
                "entry": [
                    {
                        "changes": [
                            {
                                "value": {
                                    "contacts": [
                                        {
                                            "wa_id": "44332211"
                                        }
                                    ]
                                }
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val participant = dispatcher.extractParticipantFromUpdates(updatesJson)
        assertEquals("user:44332211", participant)
    }

    @Test
    fun extractParticipantFromUpdates_parsesFromSenderObject() {
        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val updatesJson = """
            {
                "updates": [
                    {
                        "sender": {
                            "id": "55667788"
                        }
                    }
                ]
            }
        """.trimIndent()

        val participant = dispatcher.extractParticipantFromUpdates(updatesJson)
        assertEquals("user:55667788", participant)
    }

    @Test
    fun dispatch_returnsSuccessOnHttp200() = runBlocking {
        every { secureVault.getWhatsAppAgentSecrets("wa_alias_1") } returns AppResult.Success(
            DestinationSecrets.WhatsAppAgent("wa_api_key_test", "user:987654321")
        )
        every { mockCall.execute() } returns createMockResponse(200, """{"messages": [{"id": "wamid.001"}]}""")

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.dispatch(createTestTask(), createTestSms(), null)

        assertTrue("Expected DispatchResult.Success", result is DispatchResult.Success)
        val success = result as DispatchResult.Success
        assertTrue(success.responseMessage.contains("user:987654321"))
    }

    @Test
    fun dispatch_returnsRetryableFailureOnHttp429WithRetryAfter() = runBlocking {
        every { secureVault.getWhatsAppAgentSecrets("wa_alias_1") } returns AppResult.Success(
            DestinationSecrets.WhatsAppAgent("wa_api_key_test", "user:987654321")
        )
        every { mockCall.execute() } returns createMockResponse(
            429,
            """{"error": {"message": "Rate limit exceeded", "retry_after": 25}}""",
            mapOf("Retry-After" to "25")
        )

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.dispatch(createTestTask(), createTestSms(), null)

        assertTrue("Expected DispatchResult.RetryableFailure", result is DispatchResult.RetryableFailure)
        val retryable = result as DispatchResult.RetryableFailure
        assertEquals(25L, retryable.retryAfterSeconds)
        assertTrue(retryable.reason.contains("429"))
    }

    @Test
    fun dispatch_returnsRetryableFailureOnHttp503WithCode131016() = runBlocking {
        every { secureVault.getWhatsAppAgentSecrets("wa_alias_1") } returns AppResult.Success(
            DestinationSecrets.WhatsAppAgent("wa_api_key_test", "user:987654321")
        )
        every { mockCall.execute() } returns createMockResponse(
            503,
            """{"error": {"message": "Service temporarily not accepting delivery", "code": 131016}}"""
        )

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.dispatch(createTestTask(), createTestSms(), null)

        assertTrue("Expected DispatchResult.RetryableFailure for code 131016", result is DispatchResult.RetryableFailure)
        val retryable = result as DispatchResult.RetryableFailure
        assertTrue(retryable.reason.contains("131016"))
    }

    @Test
    fun dispatch_returnsPermanentFailureOnGeneric500And503_zeroDuplicateInvariant() = runBlocking {
        every { secureVault.getWhatsAppAgentSecrets("wa_alias_1") } returns AppResult.Success(
            DestinationSecrets.WhatsAppAgent("wa_api_key_test", "user:987654321")
        )
        every { mockCall.execute() } returns createMockResponse(
            500,
            """{"error": {"message": "Internal Server Error", "code": 500}}"""
        )

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.dispatch(createTestTask(), createTestSms(), null)

        assertTrue("Expected PermanentFailure on 500 per zero double-send invariant", result is DispatchResult.PermanentFailure)
        val failure = result as DispatchResult.PermanentFailure
        assertTrue(failure.reason.contains("500") || failure.reason.contains("unknown outcome"))
    }

    @Test
    fun dispatch_returnsPermanentFailureOnSocketTimeout_zeroDuplicateInvariant() = runBlocking {
        every { secureVault.getWhatsAppAgentSecrets("wa_alias_1") } returns AppResult.Success(
            DestinationSecrets.WhatsAppAgent("wa_api_key_test", "user:987654321")
        )
        every { mockCall.execute() } throws SocketTimeoutException("connect timed out")

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.dispatch(createTestTask(), createTestSms(), null)

        assertTrue("Expected PermanentFailure on timeout per zero double-send invariant", result is DispatchResult.PermanentFailure)
        val failure = result as DispatchResult.PermanentFailure
        assertTrue(failure.reason.contains("timeout") && failure.reason.contains("no retry"))
    }

    @Test
    fun dispatch_mapsUnauthorizedHttp401() = runBlocking {
        every { secureVault.getWhatsAppAgentSecrets("wa_alias_1") } returns AppResult.Success(
            DestinationSecrets.WhatsAppAgent("invalid_key", "user:987654321")
        )
        every { mockCall.execute() } returns createMockResponse(401, """{"error": {"message": "Unauthorized"}}""")

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.dispatch(createTestTask(), createTestSms(), null)

        assertTrue(result is DispatchResult.PermanentFailure)
        val failure = result as DispatchResult.PermanentFailure
        assertTrue(failure.reason.contains("401") && failure.reason.contains("Unauthorized"))
    }

    @Test
    fun dispatch_mapsExpiredTokenHttp400Code190() = runBlocking {
        every { secureVault.getWhatsAppAgentSecrets("wa_alias_1") } returns AppResult.Success(
            DestinationSecrets.WhatsAppAgent("expired_key", "user:987654321")
        )
        every { mockCall.execute() } returns createMockResponse(
            400,
            """{"error": {"message": "Error validating access token: Session has expired", "code": 190}}"""
        )

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.dispatch(createTestTask(), createTestSms(), null)

        assertTrue(result is DispatchResult.PermanentFailure)
        val failure = result as DispatchResult.PermanentFailure
        assertTrue(failure.reason.contains("190") && failure.reason.contains("expired"))
    }

    @Test
    fun dispatch_mapsInvalidParticipantHttp400Code131009() = runBlocking {
        every { secureVault.getWhatsAppAgentSecrets("wa_alias_1") } returns AppResult.Success(
            DestinationSecrets.WhatsAppAgent("wa_key", "123456789")
        )
        every { mockCall.execute() } returns createMockResponse(
            400,
            """{"error": {"message": "Invalid participant ID format", "code": 131009}}"""
        )

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.dispatch(createTestTask(), createTestSms(), null)

        assertTrue(result is DispatchResult.PermanentFailure)
        val failure = result as DispatchResult.PermanentFailure
        assertTrue(failure.reason.contains("131009") && failure.reason.contains("participant"))
    }

    @Test
    fun dispatch_mapsNonCreatorHttp403Code131005() = runBlocking {
        every { secureVault.getWhatsAppAgentSecrets("wa_alias_1") } returns AppResult.Success(
            DestinationSecrets.WhatsAppAgent("wa_key", "user:999999999")
        )
        every { mockCall.execute() } returns createMockResponse(
            403,
            """{"error": {"message": "Participant is not the agent creator", "code": 131005}}"""
        )

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.dispatch(createTestTask(), createTestSms(), null)

        assertTrue(result is DispatchResult.PermanentFailure)
        val failure = result as DispatchResult.PermanentFailure
        assertTrue(failure.reason.contains("131005") && failure.reason.contains("creator"))
    }

    @Test
    fun ratePacer_enforcesUnder12SendsPerMinuteThreshold() {
        // 60,000 ms / 12 = 5,000 ms. A minimum interval >= 5000ms strictly ensures < 12 sends/min.
        assertTrue(
            "Rate pacer interval must be at least 5000ms to guarantee < 12 sends/min",
            WhatsAppAgentDispatcher.RatePacer.MIN_INTERVAL_MS >= 5000L
        )
    }

    @Test
    fun verifyAndDeriveRecipient_derivesUserSuccessfully() = runBlocking {
        every { mockCall.execute() } returns createMockResponse(
            200,
            """{"messages": [{"from": "user:555444333"}]}"""
        )

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.verifyAndDeriveRecipient("valid_api_key")

        assertTrue(result.isSuccess)
        assertEquals("user:555444333", result.getOrNull())
    }

    @Test
    fun sendTestMessage_executesSingleTestSend() = runBlocking {
        every { mockCall.execute() } returns createMockResponse(
            200,
            """{"messages": [{"id": "wamid.test_001"}]}"""
        )

        val dispatcher = WhatsAppAgentDispatcher(secureVault, mockOkHttpClient)
        val result = dispatcher.sendTestMessage("valid_api_key", "user:123456789")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.contains("user:123456789"))
    }
}
