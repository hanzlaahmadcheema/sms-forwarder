package com.smsforwarder.app.core.util

import android.util.Log
import com.smsforwarder.app.BuildConfig
import timber.log.Timber

/**
 * SecurityLogTree intercepts all Timber log invocations across debug and release builds.
 * It enforces strict regex masking of sensitive tokens, passwords, authorization headers,
 * telephone numbers, and one-time passwords (OTPs).
 */
class SecurityLogTree : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // In release builds, suppress low-priority verbose/debug logs
        if (!BuildConfig.DEBUG && priority < Log.INFO) {
            return
        }

        val sanitizedMessage = sanitize(message)
        val sanitizedTag = tag ?: "SmsForwarder"

        if (t != null) {
            val sanitizedThrowableMessage = t.message?.let { sanitize(it) } ?: "Exception"
            Log.println(priority, sanitizedTag, "$sanitizedMessage\nCause: $sanitizedThrowableMessage")
        } else {
            Log.println(priority, sanitizedTag, sanitizedMessage)
        }
    }

    companion object {
        private val TELEGRAM_BOT_TOKEN_REGEX = Regex("""\d{8,11}:[A-Za-z0-9_-]{30,45}""")
        private val BEARER_AUTH_REGEX = Regex("""(?i)Bearer\s+[A-Za-z0-9\-._~+/]+=*""")
        private val BASIC_AUTH_REGEX = Regex("""(?i)Basic\s+[A-Za-z0-9+/=]+""")
        private val PHONE_NUMBER_REGEX = Regex("""(?<=\+?\d{2,3})\d{4,8}(\d{2})""")
        private val POTENTIAL_OTP_REGEX = Regex("""\b\d{4,8}\b""")

        fun sanitize(input: String): String {
            if (input.isEmpty()) return input
            return input
                // Redact Telegram Bot Tokens (e.g. 123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ)
                .replace(TELEGRAM_BOT_TOKEN_REGEX, "[REDACTED_BOT_TOKEN]")
                // Redact Bearer authorization headers
                .replace(BEARER_AUTH_REGEX, "Bearer [REDACTED_TOKEN]")
                // Redact Basic auth credentials
                .replace(BASIC_AUTH_REGEX, "Basic [REDACTED]")
                // Redact phone numbers preserving only country code prefix and last 2 digits
                .replace(PHONE_NUMBER_REGEX, "***$1")
                // Redact standalone 4 to 8 digit OTP candidates
                .replace(POTENTIAL_OTP_REGEX, "[REDACTED_CODE]")
        }
    }
}
