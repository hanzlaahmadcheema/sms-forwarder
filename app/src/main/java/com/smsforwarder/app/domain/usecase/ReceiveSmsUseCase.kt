package com.smsforwarder.app.domain.usecase

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.core.util.DomainError
import com.smsforwarder.app.domain.model.SimInfo
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.repository.SmsRepository
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID

/**
 * Ingestion and deduplication use case for incoming SMS messages.
 * Computes a deterministic SHA-256 hash using:
 * sender + "|" + rawBody + "|" + (timestampMillis / 10000) + "|" + simSlotIndex
 * (10-second bucket window avoids carrier re-transmit duplicates).
 */
class ReceiveSmsUseCase(
    private val smsRepository: SmsRepository
) {

    suspend operator fun invoke(
        sender: String,
        body: String,
        timestampMillis: Long,
        simInfo: SimInfo,
        contactName: String? = null
    ): AppResult<SmsMessage> {
        val cleanSender = sender.trim().ifEmpty { "UNKNOWN" }
        val cleanBody = body.trim()
        val timestamp = if (timestampMillis > 0) timestampMillis else System.currentTimeMillis()

        // 1. In-memory fast deduplication cache check (prevents rapid duplicate receiver triggers)
        val memKey = "$cleanSender:$cleanBody"
        val now = System.currentTimeMillis()
        synchronized(recentMessageMemoryCache) {
            val lastSeen = recentMessageMemoryCache[memKey]
            if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
                Timber.w("Duplicate SMS intercepted by in-memory cache: %s", cleanSender)
                return AppResult.Failure(DomainError.ValidationError("Duplicate SMS detected by in-memory cache"))
            }
            recentMessageMemoryCache[memKey] = now
            // Trim cache if too large
            if (recentMessageMemoryCache.size > 200) {
                val cutoff = now - DEDUPLICATION_WINDOW_MS
                recentMessageMemoryCache.entries.removeIf { it.value < cutoff }
            }
        }

        // 2. Room DB rolling window deduplication check (catches cross-process/reboot duplicates)
        val recentDuplicate = smsRepository.findRecentDuplicate(cleanSender, cleanBody, timestamp, DEDUPLICATION_WINDOW_MS)
        if (recentDuplicate != null) {
            Timber.w("Duplicate SMS detected via rolling window: sender=%s, id=%s", cleanSender, recentDuplicate.id)
            return AppResult.Failure(DomainError.ValidationError("Duplicate SMS detected within rolling window"))
        }

        // 3. Compute deterministic SHA-256 deduplication hash with 10s bucket
        val deduplicationHash = computeDeduplicationHash(
            sender = cleanSender,
            body = cleanBody,
            timestamp = timestamp,
            simSlot = simInfo.simSlotIndex
        )

        // 4. Check for duplicate SMS in Room DB by exact hash
        val existing = smsRepository.findByDeduplicationHash(deduplicationHash)
        if (existing != null) {
            Timber.w("Duplicate SMS detected and dropped. Hash: %s", deduplicationHash)
            return AppResult.Failure(DomainError.ValidationError("Duplicate SMS detected: $deduplicationHash"))
        }

        // 3. Construct domain entity
        val smsMessage = SmsMessage(
            id = UUID.randomUUID().toString(),
            sender = cleanSender,
            rawBody = cleanBody,
            timestampMillis = timestamp,
            simSlotIndex = simInfo.simSlotIndex,
            subId = simInfo.subId,
            carrierName = simInfo.carrierName,
            deduplicationHash = deduplicationHash,
            createdAt = System.currentTimeMillis(),
            contactName = contactName
        )

        // 4. Persist to Room database
        val insertResult = smsRepository.insertSms(smsMessage)
        return when (insertResult) {
            is AppResult.Success -> {
                Timber.i("New SMS persisted successfully: id=%s, simSlot=%d", smsMessage.id, smsMessage.simSlotIndex)
                AppResult.Success(smsMessage)
            }
            is AppResult.Failure -> {
                Timber.e("Failed to persist SMS record: %s", insertResult.error.message)
                AppResult.Failure(insertResult.error)
            }
        }
    }

    companion object {
        const val DEDUPLICATION_WINDOW_MS = 60_000L // 60-second rolling window
        private val recentMessageMemoryCache = mutableMapOf<String, Long>()

        fun resetMemoryCacheForTesting() {
            synchronized(recentMessageMemoryCache) {
                recentMessageMemoryCache.clear()
            }
        }

        fun computeDeduplicationHash(
            sender: String,
            body: String,
            timestamp: Long,
            simSlot: Int
        ): String {
            // 10-second timestamp bucket window to handle slight carrier timing variations
            val timeBucket = timestamp / 10000
            val input = "$sender|$body|$timeBucket|$simSlot"
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.fold("") { str, it -> str + "%02x".format(it) }
        }
    }
}
