package com.smsforwarder.app.domain.usecase

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.domain.model.SimInfo
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReceiveSmsUseCaseTest {

    private lateinit var fakeSmsRepository: FakeSmsRepository
    private lateinit var receiveSmsUseCase: ReceiveSmsUseCase

    @Before
    fun setUp() {
        ReceiveSmsUseCase.resetMemoryCacheForTesting()
        fakeSmsRepository = FakeSmsRepository()
        receiveSmsUseCase = ReceiveSmsUseCase(fakeSmsRepository)
    }

    @Test
    fun computeDeduplicationHash_sameInputsProduceSameHash() {
        val hash1 = ReceiveSmsUseCase.computeDeduplicationHash(
            sender = "+15551234567",
            body = "Verification code: 123456",
            timestamp = 1731175200000L,
            simSlot = 0
        )
        val hash2 = ReceiveSmsUseCase.computeDeduplicationHash(
            sender = "+15551234567",
            body = "Verification code: 123456",
            timestamp = 1731175200000L,
            simSlot = 0
        )
        assertEquals("Identical inputs must yield identical hash", hash1, hash2)
    }

    @Test
    fun computeDeduplicationHash_fallsInSameTenSecondBucket() {
        val baseTimestamp = 1731175200000L // multiple of 10,000ms
        val withinBucketTimestamp = baseTimestamp + 4500L // 4.5 seconds later (same 10s bucket)

        val hash1 = ReceiveSmsUseCase.computeDeduplicationHash(
            sender = "+15551234567",
            body = "Duplicate test message",
            timestamp = baseTimestamp,
            simSlot = 0
        )
        val hash2 = ReceiveSmsUseCase.computeDeduplicationHash(
            sender = "+15551234567",
            body = "Duplicate test message",
            timestamp = withinBucketTimestamp,
            simSlot = 0
        )
        assertEquals("Timestamps within the same 10-second bucket must yield identical deduplication hashes", hash1, hash2)
    }

    @Test
    fun computeDeduplicationHash_differentBucketsProduceDifferentHashes() {
        val baseTimestamp = 1731175200000L
        val outsideBucketTimestamp = baseTimestamp + 15000L // 15 seconds later

        val hash1 = ReceiveSmsUseCase.computeDeduplicationHash(
            sender = "+15551234567",
            body = "Duplicate test message",
            timestamp = baseTimestamp,
            simSlot = 0
        )
        val hash2 = ReceiveSmsUseCase.computeDeduplicationHash(
            sender = "+15551234567",
            body = "Duplicate test message",
            timestamp = outsideBucketTimestamp,
            simSlot = 0
        )
        assertNotEquals("Messages across different 10-second buckets must produce different hashes", hash1, hash2)
    }

    @Test
    fun computeDeduplicationHash_differentSimSlotsProduceDifferentHashes() {
        val timestamp = 1731175200000L
        val hashSim1 = ReceiveSmsUseCase.computeDeduplicationHash(
            sender = "+15551234567",
            body = "SIM slot test",
            timestamp = timestamp,
            simSlot = 0
        )
        val hashSim2 = ReceiveSmsUseCase.computeDeduplicationHash(
            sender = "+15551234567",
            body = "SIM slot test",
            timestamp = timestamp,
            simSlot = 1
        )
        assertNotEquals("Messages on different SIM slots must not collide", hashSim1, hashSim2)
    }

    @Test
    fun invoke_firstSmsSucceedsAndPersists() = runBlocking {
        val simInfo = SimInfo(simSlotIndex = 0, subId = 1, carrierName = "CarrierA")
        val result = receiveSmsUseCase(
            sender = "+15559876543",
            body = "First arrival message",
            timestampMillis = 1731175200000L,
            simInfo = simInfo
        )

        assertTrue("First SMS should be accepted", result is AppResult.Success)
        val savedSms = (result as AppResult.Success).data
        assertEquals("+15559876543", savedSms.sender)
        assertEquals("First arrival message", savedSms.rawBody)
        assertEquals(1, fakeSmsRepository.savedMessages.size)
    }

    @Test
    fun invoke_duplicateSmsWithinWindowIsDropped() = runBlocking {
        val simInfo = SimInfo(simSlotIndex = 0, subId = 1, carrierName = "CarrierA")
        val timestamp = 1731175200000L

        // First delivery
        val firstResult = receiveSmsUseCase(
            sender = "+15559876543",
            body = "Duplicate detection check",
            timestampMillis = timestamp,
            simInfo = simInfo
        )
        assertTrue(firstResult is AppResult.Success)

        // Duplicate delivery 3 seconds later
        val duplicateResult = receiveSmsUseCase(
            sender = "+15559876543",
            body = "Duplicate detection check",
            timestampMillis = timestamp + 3000L,
            simInfo = simInfo
        )
        assertTrue("Duplicate SMS within 10-second window must be rejected", duplicateResult is AppResult.Failure)
        assertEquals("Repository should only contain the single first persisted message", 1, fakeSmsRepository.savedMessages.size)
    }

    @Test
    fun invoke_withContactName_persistsContactNameSuccessfully() = runBlocking {
        val simInfo = SimInfo(simSlotIndex = 1, subId = 2, carrierName = "CarrierB")
        val result = receiveSmsUseCase(
            sender = "+15553334444",
            body = "Hello from Bob",
            timestampMillis = 1731175200000L,
            simInfo = simInfo,
            contactName = "Bob Jones"
        )

        assertTrue(result is AppResult.Success)
        val savedSms = (result as AppResult.Success).data
        assertEquals("Bob Jones", savedSms.contactName)
        assertEquals("+15553334444", savedSms.sender)
        assertEquals("Hello from Bob", savedSms.rawBody)
        assertEquals("Bob Jones", fakeSmsRepository.savedMessages.first().contactName)
    }

    private class FakeSmsRepository : SmsRepository {
        val savedMessages = mutableListOf<SmsMessage>()

        override fun getAllSmsFlow(): Flow<List<SmsMessage>> = flowOf(savedMessages)

        override suspend fun insertSms(sms: SmsMessage): AppResult<Long> {
            savedMessages.add(sms)
            return AppResult.Success(savedMessages.size.toLong())
        }

        override suspend fun getSmsById(id: String): SmsMessage? {
            return savedMessages.find { it.id == id }
        }

        override suspend fun findByDeduplicationHash(hash: String): SmsMessage? {
            return savedMessages.find { it.deduplicationHash == hash }
        }

        override suspend fun findRecentDuplicate(sender: String, body: String, timestamp: Long, windowMillis: Long): SmsMessage? {
            return savedMessages.find {
                it.sender == sender && it.rawBody == body && Math.abs(it.timestampMillis - timestamp) <= windowMillis
            }
        }

        override suspend fun getRecentSms(limit: Int): List<SmsMessage> {
            return savedMessages.takeLast(limit)
        }

        override suspend fun deleteSmsOlderThan(cutoffMillis: Long): Int {
            val initial = savedMessages.size
            savedMessages.removeAll { it.timestampMillis < cutoffMillis }
            return initial - savedMessages.size
        }

        override suspend fun purgeSmsBody(id: String): AppResult<Unit> {
            val idx = savedMessages.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val original = savedMessages[idx]
                savedMessages[idx] = original.copy(rawBody = "[PURGED]")
            }
            return AppResult.Success(Unit)
        }

        override suspend fun deleteSmsById(id: String): AppResult<Unit> {
            savedMessages.removeAll { it.id == id }
            return AppResult.Success(Unit)
        }

        override suspend fun deleteAllSms() {
            savedMessages.clear()
        }
    }
}
