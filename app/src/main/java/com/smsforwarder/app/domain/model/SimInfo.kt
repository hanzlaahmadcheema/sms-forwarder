package com.smsforwarder.app.domain.model

data class SimInfo(
    val simSlotIndex: Int, // 0 = SIM 1, 1 = SIM 2, -1 = Unknown / Default
    val subId: Int, // SubscriptionManager subscription ID
    val carrierName: String? = null // Display carrier name e.g. "T-Mobile", "Vodafone"
)
