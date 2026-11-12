package com.smsforwarder.app.engine.worker

import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryWorkerBackoffTest {

    @Test
    fun calculateExponentialBackoff_staysWithinBounds() {
        for (attempt in 1..5) {
            val delayMs = DeliveryWorker.calculateExponentialBackoffMs(attempt)
            val delaySec = delayMs / 1000L

            // Verify bounds: delay should be between base/2 and min(1800, base * 2^attempt)
            assertTrue("Delay for attempt $attempt was $delaySec s, should be >= 15s", delaySec >= 15L)
            assertTrue("Delay for attempt $attempt was $delaySec s, should be <= 1800s", delaySec <= 1800L)
        }
    }

    @Test
    fun calculateExponentialBackoff_respectsRetryAfter() {
        val retryAfterSeconds = 120L
        val delayMs = DeliveryWorker.calculateExponentialBackoffMs(1, retryAfterSeconds = retryAfterSeconds)
        val delaySec = delayMs / 1000L

        assertTrue("Delay should respect retry-after of $retryAfterSeconds s, got $delaySec s", delaySec >= retryAfterSeconds)
    }
}
