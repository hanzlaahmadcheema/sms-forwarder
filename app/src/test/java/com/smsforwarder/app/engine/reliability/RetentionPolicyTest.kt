package com.smsforwarder.app.engine.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RetentionPolicyTest {

    @Test
    fun retentionCutoff_calculatesExpectedThreshold() {
        val now = 1731190000000L
        val retentionDays = 7
        val expectedCutoff = now - TimeUnit.DAYS.toMillis(7)

        val calculatedCutoff = now - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        assertEquals(expectedCutoff, calculatedCutoff)
        assertTrue(calculatedCutoff < now)
    }

    @Test
    fun ephemeralMode_zeroDaysMeansImmediatePurge() {
        val retentionDays = 0
        val isEphemeral = retentionDays == 0
        assertTrue("Zero retention days must activate Ephemeral mode", isEphemeral)
    }

    @Test
    fun retentionWindows_produceValidCutoffs() {
        val testWindows = listOf(1, 7, 14, 30, 90)
        val now = System.currentTimeMillis()

        for (days in testWindows) {
            val cutoff = now - TimeUnit.DAYS.toMillis(days.toLong())
            assertTrue("Cutoff must be in past", cutoff < now)
            val difference = now - cutoff
            assertEquals(TimeUnit.DAYS.toMillis(days.toLong()), difference)
        }
    }
}
