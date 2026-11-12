package com.smsforwarder.app.engine.dispatcher

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.data.security.SecureVault
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Dispatcher for Native Meta WhatsApp Agent Platform (API v1).
 * Target API: https://api.whatsapp.com/agent/v1
 * Developer Manual Reference: "WhatsApp Agent Platform — Developer manual", v1 (2026-08-25).
 *
 * Enforces:
 * - Single-credential architecture: user configures only the WhatsApp Agent API key.
 * - Key-only participant auto-derivation via GET /agent/v1/updates.
 * - Outbound recipient format restricted strictly to participant identifier "user:<id>".
 * - Deterministic message body framing with 100-character sender bound and 4096-character max limit.
 * - Invariant: Exactly ONE send attempt per SMS; never retry on 500/timeout (unknown outcome).
 * - Only retryable errors: HTTP 429 and HTTP 503 with code 131016 ("not accepted for delivery").
 * - Rate limit pacing strictly under 12 sends/minute.
 */
class WhatsAppAgentDispatcher(
    private val secureVault: SecureVault,
    private val client: OkHttpClient = OkHttpClientProvider.client
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Rate pacer enforcing strictly under 12 sends/minute (minimum 5.1s between consecutive dispatches).
     */
    object RatePacer {
        private val lock = Any()
        private var lastDispatchTimestamp = 0L
        const val MIN_INTERVAL_MS = 5100L

        suspend fun pace() {
            val delayNeeded: Long
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastDispatchTimestamp
                if (elapsed < MIN_INTERVAL_MS) {
                    delayNeeded = MIN_INTERVAL_MS - elapsed
                    lastDispatchTimestamp = now + delayNeeded
                } else {
                    delayNeeded = 0L
                    lastDispatchTimestamp = now
                }
            }
            if (delayNeeded > 0) {
                delay(delayNeeded)
            }
        }

        fun resetForTesting() {
            synchronized(lock) {
                lastDispatchTimestamp = 0L
            }
        }
    }

    /**
     * Dispatches an outbound SMS message to the Meta WhatsApp Agent Platform.
     */
    suspend fun dispatch(
        task: DeliveryTask,
        message: SmsMessage,
        rule: ForwardingRule?
    ): DispatchResult = withContext(Dispatchers.IO) {
        // Enforce rate pacing under 12 sends/minute
        RatePacer.pace()

        // Retrieve credentials from SecureVault
        val vaultResult = secureVault.getWhatsAppAgentSecrets(task.vaultSecretAlias)
        val secrets = (vaultResult as? AppResult.Success)?.data
            ?: return@withContext DispatchResult.PermanentFailure("WhatsApp Agent credentials not found in vault for alias ${task.vaultSecretAlias}")

        val apiKey = secrets.apiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext DispatchResult.PermanentFailure("WhatsApp Agent API key is blank")
        }

        // Determine destination recipient ID: prioritize rule/task endpoint or derived recipient
        val targetRecipient = when {
            task.destinationEndpoint.isNotBlank() -> formatParticipantId(task.destinationEndpoint)
            secrets.recipientId.isNotBlank() -> formatParticipantId(secrets.recipientId)
            else -> return@withContext DispatchResult.PermanentFailure("No linked participant recipient ID (expected user:<id>)")
        }

        val formattedBody = formatMessageBody(message, rule)

        val requestPayload = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", targetRecipient)
            put("type", "text")
            putJsonObject("text") {
                put("body", formattedBody)
            }
        }.toString()

        val request = Request.Builder()
            .url("$BASE_URL/messages")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestPayload.toRequestBody(jsonMediaType))
            .build()

        Timber.i("Dispatching WhatsApp Agent message for task %s to recipient %s", task.id, targetRecipient)

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                when {
                    response.isSuccessful -> {
                        DispatchResult.Success("WhatsApp Agent message delivered successfully to $targetRecipient")
                    }

                    code == 429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            ?: extractRetryAfterSeconds(responseBody)
                        DispatchResult.RetryableFailure(
                            reason = "WhatsApp HTTP 429: Rate limit exceeded (retry after ${retryAfter ?: "default"}s)",
                            retryAfterSeconds = retryAfter
                        )
                    }

                    code == 503 -> {
                        val errorCode = extractMetaErrorCode(responseBody)
                        if (errorCode == 131016 || responseBody.contains("131016")) {
                            DispatchResult.RetryableFailure(
                                reason = "WhatsApp HTTP 503 (code 131016): Service temporarily not accepting delivery"
                            )
                        } else {
                            // Non-131016 503: per manual invariant, never retry on 500/timeout (unknown outcome)
                            DispatchResult.PermanentFailure(
                                reason = "WhatsApp HTTP 503: Service unavailable (unknown outcome - no retry)"
                            )
                        }
                    }

                    code == 401 -> {
                        DispatchResult.PermanentFailure(
                            reason = "WhatsApp HTTP 401: Unauthorized (Invalid or missing API key)"
                        )
                    }

                    code == 400 -> {
                        val errorCode = extractMetaErrorCode(responseBody)
                        when {
                            errorCode == 190 || responseBody.contains("190") -> {
                                DispatchResult.PermanentFailure(
                                    reason = "WhatsApp HTTP 400 (code 190): Token expired or revoked"
                                )
                            }
                            errorCode == 131009 || responseBody.contains("131009") -> {
                                DispatchResult.PermanentFailure(
                                    reason = "WhatsApp HTTP 400 (code 131009): Invalid participant ID (expected user:<id>)"
                                )
                            }
                            else -> {
                                val messageDetails = extractMetaErrorMessage(responseBody)
                                DispatchResult.PermanentFailure(
                                    reason = "WhatsApp HTTP 400: Bad request ($messageDetails)"
                                )
                            }
                        }
                    }

                    code == 403 -> {
                        val errorCode = extractMetaErrorCode(responseBody)
                        if (errorCode == 131005 || responseBody.contains("131005")) {
                            DispatchResult.PermanentFailure(
                                reason = "WhatsApp HTTP 403 (code 131005): Participant is not the agent creator"
                            )
                        } else {
                            val messageDetails = extractMetaErrorMessage(responseBody)
                            DispatchResult.PermanentFailure(
                                reason = "WhatsApp HTTP 403: Forbidden ($messageDetails)"
                            )
                        }
                    }

                    // Any other 5xx: per manual invariant, treat as permanent failure to avoid double send
                    code in 500..599 -> {
                        DispatchResult.PermanentFailure(
                            reason = "WhatsApp HTTP $code: Server error (unknown outcome - no retry)"
                        )
                    }

                    else -> {
                        val messageDetails = extractMetaErrorMessage(responseBody)
                        DispatchResult.PermanentFailure(
                            reason = "WhatsApp HTTP $code: Delivery rejected ($messageDetails)"
                        )
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            // Per manual invariant: never retry on timeout (unknown outcome)
            DispatchResult.PermanentFailure("WhatsApp network socket timeout (outcome unknown - no retry): ${e.message}")
        } catch (e: IOException) {
            // Per manual invariant: never retry on network I/O error to prevent duplicate sends
            DispatchResult.PermanentFailure("WhatsApp network I/O error (outcome unknown - no retry): ${e.message}")
        } catch (e: Exception) {
            DispatchResult.PermanentFailure("WhatsApp unexpected error: ${e.message}")
        }
    }

    /**
     * Calls GET /agent/v1/updates?limit=50&offset=0 to automatically derive the creator's user:<id>.
     * The creator sends any WhatsApp message to the agent, then triggers this verification.
     */
    suspend fun verifyAndDeriveRecipient(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("WhatsApp Agent API key cannot be blank"))
        }

        val request = Request.Builder()
            .url("$BASE_URL/updates?limit=50&offset=0")
            .header("Authorization", "Bearer $cleanKey")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = when (code) {
                        401 -> "HTTP 401 Unauthorized: Invalid WhatsApp Agent API key"
                        400 -> {
                            val errCode = extractMetaErrorCode(responseBody)
                            if (errCode == 190) "HTTP 400 (code 190): Token expired or revoked"
                            else "HTTP 400: ${extractMetaErrorMessage(responseBody)}"
                        }
                        else -> "HTTP $code: ${extractMetaErrorMessage(responseBody)}"
                    }
                    return@withContext Result.failure(IllegalStateException(errorMsg))
                }

                val derivedParticipantId = extractParticipantFromUpdates(responseBody)
                if (derivedParticipantId != null) {
                    Result.success(derivedParticipantId)
                } else {
                    Result.failure(
                        IllegalStateException(
                            "No user messages found in updates. Send any message to your WhatsApp Agent from WhatsApp, then click Verify."
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes a single test message dispatch to confirm linkage.
     * Guaranteed strictly ONE attempt (no loops or retries).
     */
    suspend fun sendTestMessage(apiKey: String, recipientId: String): Result<String> = withContext(Dispatchers.IO) {
        RatePacer.pace()

        val cleanKey = apiKey.trim()
        val targetRecipient = formatParticipantId(recipientId)

        val payload = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", targetRecipient)
            put("type", "text")
            putJsonObject("text") {
                put("body", "SMS Forwarder: WhatsApp Agent integration test verified successfully.")
            }
        }.toString()

        val request = Request.Builder()
            .url("$BASE_URL/messages")
            .header("Authorization", "Bearer $cleanKey")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success("Test message delivered successfully to $targetRecipient")
                } else {
                    val code = response.code
                    val details = extractMetaErrorMessage(body)
                    Result.failure(IllegalStateException("HTTP $code test send failed: $details"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parses updates payload to find user identifier across all known Meta formats:
     * - user.id or user_id
     * - messages[].from or updates[].from
     * - sender.id or sender
     * - contacts[].wa_id
     * - statuses[].recipient_id
     * - Any string starting with "user:"
     */
    fun extractParticipantFromUpdates(jsonString: String): String? {
        return try {
            val element = Json.parseToJsonElement(jsonString)
            findParticipantInElement(element)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse WhatsApp Agent updates JSON")
            null
        }
    }

    private fun findParticipantInElement(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                // 1. Check user object: { "user": { "id": "12345" } } or { "user": "12345" }
                val userElem = element["user"]
                if (userElem is JsonObject) {
                    val uid = extractPrimitiveString(userElem["id"])
                    if (!uid.isNullOrBlank()) return formatParticipantId(uid)
                } else if (userElem is JsonPrimitive) {
                    val uid = extractPrimitiveString(userElem)
                    if (!uid.isNullOrBlank()) return formatParticipantId(uid)
                }

                // 2. Check direct user_id / userId
                val userId = extractPrimitiveString(element["user_id"])
                    ?: extractPrimitiveString(element["userId"])
                if (!userId.isNullOrBlank()) return formatParticipantId(userId)

                // 3. Check "from": "user:12345" or { "from": { "id": "12345" } }
                val fromElem = element["from"]
                if (fromElem is JsonObject) {
                    val fromId = extractPrimitiveString(fromElem["id"])
                    if (!fromId.isNullOrBlank()) return formatParticipantId(fromId)
                } else {
                    val fromStr = extractPrimitiveString(fromElem)
                    if (!fromStr.isNullOrBlank()) return formatParticipantId(fromStr)
                }

                // 4. Check "sender": { "id": "12345" } or "sender": "user:12345"
                val senderElem = element["sender"]
                if (senderElem is JsonObject) {
                    val senderId = extractPrimitiveString(senderElem["id"])
                    if (!senderId.isNullOrBlank()) return formatParticipantId(senderId)
                } else {
                    val senderStr = extractPrimitiveString(senderElem)
                    if (!senderStr.isNullOrBlank()) return formatParticipantId(senderStr)
                }

                // 5. Check sender_id / senderId / wa_id / waId / participant_id / participant / recipient_id
                val directId = extractPrimitiveString(element["sender_id"])
                    ?: extractPrimitiveString(element["senderId"])
                    ?: extractPrimitiveString(element["wa_id"])
                    ?: extractPrimitiveString(element["waId"])
                    ?: extractPrimitiveString(element["participant_id"])
                    ?: extractPrimitiveString(element["participant"])
                    ?: extractPrimitiveString(element["recipient_id"])
                if (!directId.isNullOrBlank()) return formatParticipantId(directId)

                // 6. Check for any string starting with "user:"
                for ((_, value) in element) {
                    val str = extractPrimitiveString(value)
                    if (str != null && str.startsWith("user:", ignoreCase = true) && str.length > 5) {
                        return formatParticipantId(str)
                    }
                }

                // 7. Check known nested arrays/objects in reverse order (latest updates first)
                val preferredKeys = listOf("messages", "updates", "data", "entry", "changes", "value", "statuses", "contacts")
                for (key in preferredKeys) {
                    val child = element[key]
                    if (child != null) {
                        val result = findParticipantInElement(child)
                        if (result != null) return result
                    }
                }

                // 8. Traverse all remaining children
                for ((key, value) in element) {
                    if (key !in preferredKeys) {
                        val result = findParticipantInElement(value)
                        if (result != null) return result
                    }
                }
            }

            is JsonArray -> {
                // Traverse array in reverse order so newest updates/messages are inspected first
                for (i in (element.size - 1) downTo 0) {
                    val result = findParticipantInElement(element[i])
                    if (result != null) return result
                }
            }

            is JsonPrimitive -> {
                val str = extractPrimitiveString(element)
                if (str != null && str.startsWith("user:", ignoreCase = true) && str.length > 5) {
                    return formatParticipantId(str)
                }
            }
        }
        return null
    }

    private fun extractPrimitiveString(elem: JsonElement?): String? {
        val prim = elem as? JsonPrimitive ?: return null
        if (prim is kotlinx.serialization.json.JsonNull) return null
        return prim.content.takeIf { it.isNotBlank() }
    }

    /**
     * Formats SMS into WhatsApp Agent plain text format:
     * "New SMS received\nFrom: <sender (<=100 chars)>\n\n\n<body>"
     * Truncated to a strict maximum of 4096 characters.
     */
    fun formatMessageBody(message: SmsMessage, rule: ForwardingRule?): String {
        val safeSender = message.sender.take(MAX_SENDER_LENGTH)
        val renderedContent = if (!rule?.customTemplate.isNullOrBlank()) {
            TemplateRenderer.render(rule?.customTemplate!!, message)
        } else {
            "New SMS received\nFrom: $safeSender\n\n\n${message.rawBody}"
        }
        return renderedContent.take(MAX_BODY_LENGTH)
    }

    /**
     * Enforces the "user:<id>" participant identifier requirement.
     */
    fun formatParticipantId(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("user:", ignoreCase = true)) {
            trimmed
        } else {
            "user:$trimmed"
        }
    }

    private fun extractMetaErrorCode(responseBody: String): Int? {
        return try {
            val root = Json.parseToJsonElement(responseBody).jsonObject
            val error = root["error"]?.jsonObject
            error?.get("code")?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun extractMetaErrorMessage(responseBody: String): String {
        return try {
            val root = Json.parseToJsonElement(responseBody).jsonObject
            val error = root["error"]?.jsonObject
            error?.get("message")?.jsonPrimitive?.content ?: responseBody.take(150)
        } catch (_: Exception) {
            responseBody.take(150)
        }
    }

    private fun extractRetryAfterSeconds(responseBody: String): Long? {
        return try {
            val root = Json.parseToJsonElement(responseBody).jsonObject
            val error = root["error"]?.jsonObject
            error?.get("retry_after")?.jsonPrimitive?.longOrNull
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val BASE_URL = "https://api.whatsapp.com/agent/v1"
        const val MAX_SENDER_LENGTH = 100
        const val MAX_BODY_LENGTH = 4096
    }
}
