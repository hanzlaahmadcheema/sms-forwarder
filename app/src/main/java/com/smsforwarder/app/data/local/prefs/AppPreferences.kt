package com.smsforwarder.app.data.local.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight local persistent preferences for SMS Forwarder.
 * Manages user retention settings (days), Ephemeral (Zero-History) mode,
 * and service state. Zero analytics or remote syncing.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()

    var isEphemeralMode: Boolean
        get() = prefs.getBoolean(KEY_EPHEMERAL_MODE, false) || retentionDays == 0
        set(value) {
            prefs.edit().putBoolean(KEY_EPHEMERAL_MODE, value).apply()
            if (value && retentionDays != 0) {
                retentionDays = 0
            }
        }

    var isServiceRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_RUNNING, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_RUNNING, value).apply()

    var networkTimeoutSeconds: Int
        get() = prefs.getInt(KEY_NETWORK_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)
        set(value) = prefs.edit().putInt(KEY_NETWORK_TIMEOUT_SECONDS, value).apply()

    var telegramRetryOnTimeout: Boolean
        get() = prefs.getBoolean(KEY_TELEGRAM_RETRY_ON_TIMEOUT, false)
        set(value) = prefs.edit().putBoolean(KEY_TELEGRAM_RETRY_ON_TIMEOUT, value).apply()

    var deduplicationWindowSeconds: Int
        get() = prefs.getInt(KEY_DEDUPLICATION_WINDOW_SECONDS, DEFAULT_DEDUPLICATION_SECONDS)
        set(value) = prefs.edit().putInt(KEY_DEDUPLICATION_WINDOW_SECONDS, value).apply()

    var defaultTemplate: String
        get() = prefs.getString(KEY_DEFAULT_TEMPLATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEFAULT_TEMPLATE, value).apply()

    companion object {
        private const val PREFS_NAME = "sms_forwarder_preferences"
        private const val KEY_RETENTION_DAYS = "pref_retention_days"
        private const val KEY_EPHEMERAL_MODE = "pref_ephemeral_mode"
        private const val KEY_SERVICE_RUNNING = "pref_service_running"
        private const val KEY_NETWORK_TIMEOUT_SECONDS = "pref_network_timeout_seconds"
        private const val KEY_TELEGRAM_RETRY_ON_TIMEOUT = "pref_telegram_retry_on_timeout"
        private const val KEY_DEDUPLICATION_WINDOW_SECONDS = "pref_deduplication_window_seconds"
        private const val KEY_DEFAULT_TEMPLATE = "pref_default_template"

        const val DEFAULT_RETENTION_DAYS = 7
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val DEFAULT_DEDUPLICATION_SECONDS = 60
    }
}
