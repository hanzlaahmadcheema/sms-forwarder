package com.smsforwarder.app.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * On-device contact name resolver querying ContactsContract.PhoneLookup.
 * Strictly adheres to privacy invariants: performs local ContentResolver lookup only,
 * with zero remote network egress or external telemetry.
 */
object ContactHelper {

    const val UNKNOWN_CONTACT = "Unknown Contact"

    /**
     * Resolves the display name of a contact for the given phone number.
     *
     * @param context Android Application Context
     * @param phoneNumber The originating phone number (e.g., "+15551234567")
     * @param fallbackToSender If true and contact is not found/accessible, returns the raw sender number.
     *                         If false (default), returns "Unknown Contact".
     * @return The contact display name, or the fallback string if unresolvable.
     */
    fun resolveContactName(
        context: Context,
        phoneNumber: String?,
        fallbackToSender: Boolean = false
    ): String {
        val cleanNumber = phoneNumber?.trim()
        if (cleanNumber.isNullOrEmpty() || cleanNumber.equals("UNKNOWN", ignoreCase = true)) {
            return UNKNOWN_CONTACT
        }

        val hasPermission = try {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }

        if (!hasPermission) {
            Timber.d("READ_CONTACTS permission not granted; returning fallback for %s", cleanNumber)
            return if (fallbackToSender) cleanNumber else UNKNOWN_CONTACT
        }

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(cleanNumber)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME
            )

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val displayName = cursor.getString(nameIndex)
                        if (!displayName.isNullOrBlank()) {
                            return displayName
                        }
                    }
                }
                if (fallbackToSender) cleanNumber else UNKNOWN_CONTACT
            } ?: (if (fallbackToSender) cleanNumber else UNKNOWN_CONTACT)
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException while querying ContactsContract for %s", cleanNumber)
            if (fallbackToSender) cleanNumber else UNKNOWN_CONTACT
        } catch (e: Throwable) {
            Timber.w(e, "Error querying ContactsContract for %s", cleanNumber)
            if (fallbackToSender) cleanNumber else UNKNOWN_CONTACT
        }
    }
}
