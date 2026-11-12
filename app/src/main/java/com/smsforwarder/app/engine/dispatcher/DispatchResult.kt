package com.smsforwarder.app.engine.dispatcher

/**
 * Result model representing the outcome of a protocol dispatch operation (Webhook, Telegram, Email).
 */
sealed class DispatchResult {
    /**
     * Dispatch succeeded with confirmation from remote endpoint.
     */
    data class Success(
        val responseMessage: String
    ) : DispatchResult()

    /**
     * Dispatch failed due to a transient error (e.g. 429 rate limit, 5xx server error, socket timeout).
     * The task will be rescheduled using exponential backoff.
     */
    data class RetryableFailure(
        val reason: String,
        val retryAfterSeconds: Long? = null
    ) : DispatchResult()

    /**
     * Dispatch failed due to a permanent configuration or client error (e.g. 401 unauthorized,
     * invalid credentials, blocked chat, malformed endpoint, rejected recipient).
     * The task will be marked FAILED_EXHAUSTED without further automatic retries.
     */
    data class PermanentFailure(
        val reason: String
    ) : DispatchResult()
}
