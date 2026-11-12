package com.smsforwarder.app.engine.dispatcher

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.data.security.SecureVault
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Dispatches outbound SMS forwarding tasks to HTTP/HTTPS Webhook endpoints.
 * Enforces strict TLS 1.2/1.3, handles auth headers from SecureVault, and classifies HTTP responses.
 */
class WebhookDispatcher(
    private val secureVault: SecureVault
) {
    private val client = OkHttpClientProvider.client
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun dispatch(
        task: DeliveryTask,
        message: SmsMessage,
        rule: ForwardingRule?
    ): DispatchResult = withContext(Dispatchers.IO) {
        val endpoint = task.destinationEndpoint.trim()

        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            return@withContext DispatchResult.PermanentFailure("Webhook endpoint rejected: Strict HTTPS protocol required ($endpoint)")
        }

        // Retrieve webhook secret headers from SecureVault via opaque alias
        val vaultResult = secureVault.getWebhookSecrets(task.vaultSecretAlias)
        val webhookSecrets = if (vaultResult is AppResult.Success) vaultResult.data else null

        // Check for rule custom template or global default template
        val activeTemplate = rule?.customTemplate?.takeIf { it.isNotBlank() }
            ?: com.smsforwarder.app.SmsForwarderApp.instance.appPreferences.defaultTemplate.takeIf { it.isNotBlank() }

        val payloadJson = if (activeTemplate != null) {
            val isJson = activeTemplate.trim().startsWith("{") || activeTemplate.contains("\"")
            TemplateRenderer.render(activeTemplate, message, escapeForJson = isJson)
        } else {
            buildJsonObject {
                put("sender", message.sender)
                put("body", message.rawBody)
                put("message", message.rawBody)
                put("text", message.rawBody)
                put("timestamp", message.timestampMillis)
                put("simSlot", message.simSlotIndex)
                put("carrier", message.carrierName ?: "Unknown")
                put("deduplicationHash", message.deduplicationHash)
            }.toString()
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payloadJson.toRequestBody(jsonMediaType))

        // Attach Authorization header if configured
        webhookSecrets?.authHeader?.takeIf { it.isNotBlank() }?.let { auth ->
            requestBuilder.header("Authorization", auth)
        }

        // Attach custom headers if configured
        webhookSecrets?.customHeadersJson?.takeIf { it.isNotBlank() }?.let { customHeadersJson ->
            try {
                val parsedHeaders = Json.decodeFromString<JsonObject>(customHeadersJson)
                parsedHeaders.forEach { (key, element) ->
                    val value = element.jsonPrimitive.content
                    if (key.isNotBlank() && value.isNotBlank()) {
                        requestBuilder.header(key, value)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse custom webhook headers JSON for task %s", task.id)
            }
        }

        val request = requestBuilder.build()
        Timber.i("Dispatching Webhook task %s to %s", task.id, endpoint)

        val prefs = com.smsforwarder.app.SmsForwarderApp.instance.appPreferences
        val timeoutSec = maxOf(45L, prefs.networkTimeoutSeconds.toLong())
        val callClient = client.newBuilder()
            .connectTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            callClient.newCall(request).execute().use { response ->
                val code = response.code
                val responseSnippet = response.body?.string()?.take(250)?.trim() ?: ""

                when {
                    response.isSuccessful -> {
                        DispatchResult.Success("HTTP $code: Webhook delivery confirmed")
                    }
                    code == 429 -> {
                        val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                        DispatchResult.RetryableFailure(
                            reason = "HTTP 429: Remote endpoint rate limit exceeded",
                            retryAfterSeconds = retryAfterSeconds
                        )
                    }
                    code in 500..599 -> {
                        DispatchResult.RetryableFailure("HTTP $code: Remote server error ($responseSnippet)")
                    }
                    code in listOf(400, 401, 403, 404, 405, 410, 422) -> {
                        DispatchResult.PermanentFailure("HTTP $code: Non-retryable client error ($responseSnippet)")
                    }
                    else -> {
                        DispatchResult.PermanentFailure("HTTP $code: Request failed with status $code ($responseSnippet)")
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            DispatchResult.RetryableFailure("Webhook socket timeout: ${e.message}")
        } catch (e: UnknownHostException) {
            DispatchResult.RetryableFailure("Webhook DNS resolution failed: ${e.message}")
        } catch (e: IOException) {
            DispatchResult.RetryableFailure("Webhook network I/O error: ${e.message}")
        } catch (e: Exception) {
            DispatchResult.PermanentFailure("Webhook unexpected execution error: ${e.message}")
        }
    }

    /**
     * Sends an immediate test POST to the target webhook without saving to database.
     */
    suspend fun sendTestWebhook(
        endpoint: String,
        authHeader: String? = null,
        customHeadersJson: String? = null,
        customPayload: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanEndpoint = endpoint.trim()
        if (!cleanEndpoint.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Webhook endpoint must strictly use HTTPS"))
        }

        val bodyJson = customPayload?.ifBlank { null } ?: buildJsonObject {
            put("sender", "+15551234567")
            put("message", "Verification test from SMS Forwarder App")
            put("body", "Verification test from SMS Forwarder App")
            put("timestamp", System.currentTimeMillis())
            put("sim", "SIM 1")
        }.toString()

        val requestBuilder = Request.Builder()
            .url(cleanEndpoint)
            .post(bodyJson.toRequestBody(jsonMediaType))

        authHeader?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Authorization", it) }

        customHeadersJson?.takeIf { it.isNotBlank() }?.let {
            try {
                val parsed = Json.decodeFromString<JsonObject>(it)
                parsed.forEach { (k, v) -> requestBuilder.header(k, v.jsonPrimitive.content) }
            } catch (_: Exception) {}
        }

        val prefs = com.smsforwarder.app.SmsForwarderApp.instance.appPreferences
        val timeoutSec = maxOf(45L, prefs.networkTimeoutSeconds.toLong())
        val callClient = client.newBuilder()
            .connectTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            callClient.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string()?.take(500) ?: ""
                if (response.isSuccessful) {
                    Result.success("HTTP ${response.code}: $body")
                } else {
                    Result.failure(IOException("HTTP ${response.code}: $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
