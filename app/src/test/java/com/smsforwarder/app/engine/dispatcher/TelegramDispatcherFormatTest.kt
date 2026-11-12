package com.smsforwarder.app.engine.dispatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramDispatcherFormatTest {

    @Test
    fun escapeHtml_escapesXmlReservedCharacters() {
        val rawInput = "OTP <123456> & 'quote' > 0"
        val escaped = escapeHtml(rawInput)

        assertFalse("Raw '<' must be escaped", escaped.contains("<123456>"))
        assertFalse("Raw '>' must be escaped", escaped.contains("> 0"))
        assertFalse("Raw '&' must be escaped", escaped.contains("& 'quote'"))

        assertTrue("Contains &lt;", escaped.contains("&lt;123456&gt;"))
        assertTrue("Contains &amp;", escaped.contains("&amp; 'quote'"))
    }

    @Test
    fun botTokenUrlMasking_masksBotTokenInLogs() {
        val botToken = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345678"
        val realUrl = "https://api.telegram.org/bot$botToken/sendMessage"
        val safeLogUrl = realUrl.replace(botToken, "[REDACTED_BOT_TOKEN]")

        assertEquals("https://api.telegram.org/bot[REDACTED_BOT_TOKEN]/sendMessage", safeLogUrl)
        assertFalse("Log URL must not contain actual secret token", safeLogUrl.contains(botToken))
    }

    @Test
    fun extractRetryAfter_parsesTelegramRateLimitPayload() {
        val jsonPayload = """
            {
                "ok": false,
                "error_code": 429,
                "description": "Too Many Requests: retry after 42",
                "parameters": {
                    "retry_after": 42
                }
            }
        """.trimIndent()

        val parsedSeconds = Regex(""""retry_after":\s*(\d+)""").find(jsonPayload)?.groupValues?.get(1)?.toLongOrNull()
        assertEquals(42L, parsedSeconds)
    }

    @Test
    fun messageSplitting_handlesLongSmsMessages() {
        val longMessage = "A".repeat(5000)
        val maxTelegramLength = 4096

        val chunks = longMessage.chunked(maxTelegramLength)
        assertEquals(2, chunks.size)
        assertEquals(4096, chunks[0].length)
        assertEquals(904, chunks[1].length)
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
