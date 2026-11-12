package com.smsforwarder.app.domain.model

enum class DestinationType {
    WEBHOOK,
    TELEGRAM,
    EMAIL,
    WHATSAPP_AGENT
}

enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    FAILED_RETRYABLE,
    FAILED_EXHAUSTED
}

enum class SenderFilterType {
    ALL,
    ALLOWLIST,
    BLOCKLIST
}
