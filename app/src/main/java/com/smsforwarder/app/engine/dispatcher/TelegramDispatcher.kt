package com.smsforwarder.app.engine.dispatcher

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.data.security.SecureVault
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dispatches outbound SMS forwarding tasks to Telegram Bot API.
 * Uses strict HTML parse mode, escapes HTML entities, sanitizes bot tokens from all logs,
 * and extracts 429 rate limit backoff parameters.
 */
class TelegramDispatcher(
    private val secureVault: SecureVault
) {
    private val client = OkHttpClientProvider.client
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    suspend fun dispatch(
        task: DeliveryTask,
        message: SmsMessage,
        rule: ForwardingRule?
    ): DispatchResult = withContext(Dispatchers.IO) {
        val chatId = task.destinationEndpoint.trim()
        if (chatId.isBlank()) {
            return@withContext DispatchResult.PermanentFailure("Telegram chat_id is missing or blank")
        }

        // Retrieve bot token securely from Keystore vault
        val vaultResult = secureVault.getTelegramSecrets(task.vaultSecretAlias)
        if (vaultResult !is AppResult.Success || vaultResult.data.botToken.isBlank()) {
            return@withContext DispatchResult.PermanentFailure("Telegram bot token not found in secure vault for alias ${task.vaultSecretAlias}")
        }
        val botToken = vaultResult.data.botToken.trim()

        val formattedHtml = formatTelegramHtml(message, rule)

        val requestPayload = buildJsonObject {
            put("chat_id", chatId)
            put("text", formattedHtml)
            put("parse_mode", "HTML")
            put("disable_web_page_preview", true)
        }.toString()

        // Telegram Bot API endpoint
        val apiUrl = "https://api.telegram.org/bot$botToken/sendMessage"

        // Redacted URL strictly for safe logging to prevent token leakage
        val safeLogUrl = "https://api.telegram.org/bot[REDACTED_BOT_TOKEN]/sendMessage"
        Timber.i("Dispatching Telegram task %s to %s for chat_id %s", task.id, safeLogUrl, chatId)

        val request = Request.Builder()
            .url(apiUrl)
            .post(requestPayload.toRequestBody(jsonMediaType))
            .build()

        val prefs = com.smsforwarder.app.SmsForwarderApp.instance.appPreferences
        val timeoutSec = prefs.networkTimeoutSeconds.toLong()
        val callClient = client.newBuilder()
            .connectTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            callClient.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                when {
                    response.isSuccessful -> {
                        DispatchResult.Success("Telegram message delivered successfully to chat $chatId")
                    }
                    code == 429 -> {
                        val retryAfterSeconds = extractRetryAfter(responseBody, response.header("Retry-After"))
                        DispatchResult.RetryableFailure(
                            reason = "Telegram HTTP 429: Rate limit exceeded (retry after ${retryAfterSeconds ?: "default"}s)",
                            retryAfterSeconds = retryAfterSeconds
                        )
                    }
                    code in 500..599 -> {
                        DispatchResult.RetryableFailure("Telegram HTTP $code: Server error")
                    }
                    code in listOf(400, 401, 403, 404) -> {
                        val errorDescription = extractDescription(responseBody)
                        DispatchResult.PermanentFailure("Telegram HTTP $code: Non-retryable error ($errorDescription)")
                    }
                    else -> {
                        DispatchResult.PermanentFailure("Telegram HTTP $code: Delivery failed ($responseBody)")
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            if (prefs.telegramRetryOnTimeout) {
                DispatchResult.RetryableFailure("Telegram socket timeout (${timeoutSec}s): ${e.message}")
            } else {
                DispatchResult.PermanentFailure("Telegram socket timeout (${timeoutSec}s): outcome unknown. Automatic retry suppressed to prevent double-sends (tap retry manually in History if needed).")
            }
        } catch (e: UnknownHostException) {
            DispatchResult.RetryableFailure("Telegram DNS resolution failed: ${e.message}")
        } catch (e: IOException) {
            DispatchResult.RetryableFailure("Telegram network I/O error: ${e.message}")
        } catch (e: Exception) {
            DispatchResult.PermanentFailure("Telegram unexpected error: ${e.message}")
        }
    }

    /**
     * Sends an immediate test message to Telegram without saving to Room.
     */
    suspend fun sendTestMessage(botToken: String, chatId: String, text: String = "Test notification from SMS Forwarder App"): Result<String> = withContext(Dispatchers.IO) {
        val cleanToken = botToken.trim()
        val cleanChat = chatId.trim()
        if (cleanToken.isBlank() || cleanChat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Bot token and Chat ID are required"))
        }

        val requestPayload = buildJsonObject {
            put("chat_id", cleanChat)
            put("text", "🧪 <b>SMS Forwarder Test</b>\n\n$text")
            put("parse_mode", "HTML")
        }.toString()

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$cleanToken/sendMessage")
            .post(requestPayload.toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success("Telegram test message sent successfully!")
                } else {
                    val desc = extractDescription(body)
                    Result.failure(IOException("HTTP ${response.code}: $desc"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Formats SMS into clean, strict HTML suitable for Telegram Bot API without Markdown escaping crashes.
     */
    private fun formatTelegramHtml(message: SmsMessage, rule: ForwardingRule?): String {
        val rawOrRenderedBody = if (rule?.customTemplate?.isNotBlank() == true) {
            TemplateRenderer.render(rule.customTemplate, message)
        } else {
            message.rawBody
        }

        val escapedSender = escapeHtml(message.sender)
        val escapedBody = escapeHtml(rawOrRenderedBody)
        val simLabel = if (message.simSlotIndex >= 0) " [SIM ${message.simSlotIndex + 1}]" else ""
        val carrierLabel = message.carrierName?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val formattedTime = dateFormat.format(Date(message.timestampMillis))

        return "📬 <b>New SMS Received$simLabel$carrierLabel</b>\n" +
                "👤 <b>From:</b> <code>$escapedSender</code>\n" +
                "🕒 <b>Time:</b> $formattedTime\n\n" +
                "💬 <b>Message:</b>\n$escapedBody"
    }

    /**
     * Replaces HTML reserved characters to prevent parsing failures in Telegram HTML mode.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    /**
     * Extracts retry_after duration from Telegram 429 response body or header.
     */
    private fun extractRetryAfter(responseBody: String, headerRetryAfter: String?): Long? {
        return try {
            val json = Json.decodeFromString<JsonObject>(responseBody)
            val parameters = json["parameters"] as? JsonObject
            parameters?.get("retry_after")?.jsonPrimitive?.longOrNull
        } catch (_: Exception) {
            null
        } ?: headerRetryAfter?.toLongOrNull()
    }

    /**
     * Extracts human-readable error description from Telegram error JSON.
     */
    private fun extractDescription(responseBody: String): String {
        return try {
            val json = Json.decodeFromString<JsonObject>(responseBody)
            json["description"]?.jsonPrimitive?.content ?: responseBody.take(150)
        } catch (_: Exception) {
            responseBody.take(150)
        }
    }
}
