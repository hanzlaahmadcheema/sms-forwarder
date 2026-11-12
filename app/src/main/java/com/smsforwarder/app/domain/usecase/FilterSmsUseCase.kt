package com.smsforwarder.app.domain.usecase

import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SenderFilterType
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.repository.ForwardingRuleRepository
import timber.log.Timber

/**
 * Filter and routing engine use case.
 * Evaluates an incoming SMS message against all active ForwardingRuleEntity definitions:
 * - SIM slot constraint (-1 = Any, 0 = SIM 1, 1 = SIM 2)
 * - Sender filters: ALL, ALLOWLIST (exact or regex match), BLOCKLIST
 * - Keyword filters: case-insensitive containment match
 */
class FilterSmsUseCase(
    private val ruleRepository: ForwardingRuleRepository
) {

    suspend operator fun invoke(sms: SmsMessage): List<ForwardingRule> {
        val activeRules = ruleRepository.getActiveRules()
        if (activeRules.isEmpty()) {
            Timber.i("No active forwarding rules configured. Dropping message %s from routing.", sms.id)
            return emptyList()
        }

        val matchingRules = activeRules.filter { rule ->
            matchesRule(rule, sms)
        }

        Timber.i(
            "FilterSmsUseCase evaluated %d active rules for SMS %s: %d matched",
            activeRules.size,
            sms.id,
            matchingRules.size
        )
        return matchingRules
    }

    private fun matchesRule(rule: ForwardingRule, sms: SmsMessage): Boolean {
        // 1. Evaluate SIM slot constraint
        if (rule.simSlotFilter >= 0 && sms.simSlotIndex >= 0) {
            if (rule.simSlotFilter != sms.simSlotIndex) {
                return false
            }
        }

        // 2. Evaluate Sender Filter
        val senderMatches = when (rule.senderFilterType) {
            SenderFilterType.ALL -> true
            SenderFilterType.ALLOWLIST -> matchesSenderList(rule.senderFilterValues, sms.sender)
            SenderFilterType.BLOCKLIST -> !matchesSenderList(rule.senderFilterValues, sms.sender)
        }
        if (!senderMatches) return false

        // 3. Evaluate Keyword Filters (if any specified)
        if (rule.keywordFilterValues.isNotBlank()) {
            val keywords = rule.keywordFilterValues
                .split(",", "\n", ";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (keywords.isNotEmpty()) {
                val bodyLower = sms.rawBody.lowercase()
                val hasKeywordMatch = keywords.any { keyword ->
                    bodyLower.contains(keyword.lowercase())
                }
                if (!hasKeywordMatch) return false
            }
        }

        return true
    }

    private fun matchesSenderList(filterValues: String, sender: String): Boolean {
        if (filterValues.isBlank()) return false

        val tokens = filterValues
            .split(",", "\n", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val cleanSender = sender.trim()
        val digitsOnlySender = cleanSender.filter { it.isDigit() }

        return tokens.any { token ->
            // Check exact match (e.g. alphanumeric "MYBANK")
            if (cleanSender.equals(token, ignoreCase = true)) return@any true

            // Check digit-only suffix/prefix match (handling +1 country codes)
            val digitsToken = token.filter { it.isDigit() }
            if (digitsToken.isNotEmpty() && digitsOnlySender.isNotEmpty()) {
                if (digitsOnlySender == digitsToken || digitsOnlySender.endsWith(digitsToken) || digitsToken.endsWith(digitsOnlySender)) {
                    return@any true
                }
            }

            // Check wildcard or regex match
            try {
                if (token.contains("*") || token.contains("?")) {
                    val regexPattern = token
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".")
                    if (Regex(regexPattern, RegexOption.IGNORE_CASE).matches(cleanSender)) {
                        return@any true
                    }
                } else if (Regex(token, RegexOption.IGNORE_CASE).matches(cleanSender)) {
                    return@any true
                }
            } catch (_: Exception) {
                // Ignore invalid regex syntax and fall back to literal comparison
            }

            false
        }
    }
}
