package com.smsforwarder.app.domain.usecase

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SenderFilterType
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.repository.ForwardingRuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FilterSmsUseCaseTest {

    private lateinit var fakeRuleRepository: FakeRuleRepository
    private lateinit var filterSmsUseCase: FilterSmsUseCase

    @Before
    fun setUp() {
        fakeRuleRepository = FakeRuleRepository()
        filterSmsUseCase = FilterSmsUseCase(fakeRuleRepository)
    }

    private fun createTestSms(
        sender: String = "+15551234567",
        body: String = "Your Bank OTP is 482910",
        simSlot: Int = 0
    ): SmsMessage = SmsMessage(
        id = "test-sms-id",
        sender = sender,
        rawBody = body,
        timestampMillis = 1731175200000L,
        simSlotIndex = simSlot,
        subId = 1,
        carrierName = "CarrierA",
        deduplicationHash = "hash123",
        createdAt = 1731175200000L
    )

    private fun createRule(
        id: String,
        name: String,
        isEnabled: Boolean = true,
        simSlotFilter: Int = -1,
        senderFilterType: SenderFilterType = SenderFilterType.ALL,
        senderFilterValues: String = "",
        keywordFilterValues: String = ""
    ): ForwardingRule = ForwardingRule(
        id = id,
        name = name,
        isEnabled = isEnabled,
        targetDestinationType = DestinationType.WEBHOOK,
        destinationEndpoint = "https://example.com/webhook",
        vaultAliasRef = "vault_alias_$id",
        simSlotFilter = simSlotFilter,
        senderFilterType = senderFilterType,
        senderFilterValues = senderFilterValues,
        keywordFilterValues = keywordFilterValues,
        customTemplate = null,
        createdAt = System.currentTimeMillis()
    )

    @Test
    fun invoke_returnsEmptyWhenNoActiveRules() = runBlocking {
        fakeRuleRepository.rules.clear()
        val sms = createTestSms()
        val result = filterSmsUseCase(sms)
        assertTrue("Should return empty list when no active rules exist", result.isEmpty())
    }

    @Test
    fun invoke_ignoresDisabledRules() = runBlocking {
        fakeRuleRepository.rules.add(createRule(id = "r1", name = "Disabled Rule", isEnabled = false))
        val sms = createTestSms()
        val result = filterSmsUseCase(sms)
        assertTrue("Disabled rules must be excluded from evaluation", result.isEmpty())
    }

    @Test
    fun invoke_matchesSimSlotConstraint() = runBlocking {
        fakeRuleRepository.rules.add(createRule(id = "r_sim1", name = "SIM 1 Rule", simSlotFilter = 0))
        fakeRuleRepository.rules.add(createRule(id = "r_sim2", name = "SIM 2 Rule", simSlotFilter = 1))
        fakeRuleRepository.rules.add(createRule(id = "r_any", name = "Any SIM Rule", simSlotFilter = -1))

        val smsSim1 = createTestSms(simSlot = 0)
        val matchedSim1 = filterSmsUseCase(smsSim1)
        assertEquals(2, matchedSim1.size)
        assertTrue(matchedSim1.any { it.id == "r_sim1" })
        assertTrue(matchedSim1.any { it.id == "r_any" })
        assertFalse(matchedSim1.any { it.id == "r_sim2" })
    }

    @Test
    fun invoke_evaluatesSenderAllowlistAndWildcards() = runBlocking {
        fakeRuleRepository.rules.add(
            createRule(
                id = "r_allow_exact",
                name = "Allow Exact",
                senderFilterType = SenderFilterType.ALLOWLIST,
                senderFilterValues = "MYBANK, +15551234567"
            )
        )
        fakeRuleRepository.rules.add(
            createRule(
                id = "r_allow_wildcard",
                name = "Allow Wildcard",
                senderFilterType = SenderFilterType.ALLOWLIST,
                senderFilterValues = "*BANK*"
            )
        )
        fakeRuleRepository.rules.add(
            createRule(
                id = "r_allow_other",
                name = "Allow Other",
                senderFilterType = SenderFilterType.ALLOWLIST,
                senderFilterValues = "+19998887777"
            )
        )

        // Matching alphanumeric sender
        val smsBank = createTestSms(sender = "MYBANK")
        val matchedBank = filterSmsUseCase(smsBank)
        assertEquals(2, matchedBank.size)
        assertTrue(matchedBank.any { it.id == "r_allow_exact" })
        assertTrue(matchedBank.any { it.id == "r_allow_wildcard" })
        assertFalse(matchedBank.any { it.id == "r_allow_other" })
    }

    @Test
    fun invoke_evaluatesSenderBlocklist() = runBlocking {
        fakeRuleRepository.rules.add(
            createRule(
                id = "r_block_spam",
                name = "Block Spam",
                senderFilterType = SenderFilterType.BLOCKLIST,
                senderFilterValues = "SPAMMER, +15550000000"
            )
        )

        val spamSms = createTestSms(sender = "SPAMMER")
        val legitSms = createTestSms(sender = "+15551234567")

        val matchedSpam = filterSmsUseCase(spamSms)
        assertTrue("Blocked sender must not match rule", matchedSpam.isEmpty())

        val matchedLegit = filterSmsUseCase(legitSms)
        assertEquals(1, matchedLegit.size)
        assertEquals("r_block_spam", matchedLegit[0].id)
    }

    @Test
    fun invoke_evaluatesKeywordFilter() = runBlocking {
        fakeRuleRepository.rules.add(
            createRule(
                id = "r_keywords",
                name = "Keywords Rule",
                keywordFilterValues = "OTP, verification, code"
            )
        )

        val matchingSms = createTestSms(body = "Here is your verification OTP: 3918")
        val nonMatchingSms = createTestSms(body = "Hello, are you coming to dinner tonight?")

        val matched1 = filterSmsUseCase(matchingSms)
        assertEquals(1, matched1.size)
        assertEquals("r_keywords", matched1[0].id)

        val matched2 = filterSmsUseCase(nonMatchingSms)
        assertTrue("Message without required keywords should not match", matched2.isEmpty())
    }

    private class FakeRuleRepository : ForwardingRuleRepository {
        val rules = mutableListOf<ForwardingRule>()

        override fun getAllRulesFlow(): Flow<List<ForwardingRule>> = flowOf(rules)

        override suspend fun getActiveRules(): List<ForwardingRule> = rules.filter { it.isEnabled }

        override suspend fun getRuleById(id: String): ForwardingRule? = rules.find { it.id == id }

        override suspend fun insertOrUpdateRule(rule: ForwardingRule): AppResult<Unit> {
            rules.removeAll { it.id == rule.id }
            rules.add(rule)
            return AppResult.Success(Unit)
        }

        override suspend fun updateRuleEnabled(id: String, isEnabled: Boolean): AppResult<Unit> {
            val rule = rules.find { it.id == id }
            if (rule != null) {
                rules.remove(rule)
                rules.add(rule.copy(isEnabled = isEnabled))
            }
            return AppResult.Success(Unit)
        }

        override suspend fun deleteRule(id: String): AppResult<Unit> {
            rules.removeAll { it.id == id }
            return AppResult.Success(Unit)
        }

        override suspend fun deleteAllRules() {
            rules.clear()
        }
    }
}
