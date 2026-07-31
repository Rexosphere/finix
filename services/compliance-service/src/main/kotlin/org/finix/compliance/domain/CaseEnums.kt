package org.finix.compliance.domain

enum class CaseType {
    AML,
    SANCTIONS,
    FRAUD,
    SAR,
    TRAVEL_RULE,
}

enum class CaseStatus {
    OPEN,
    INVESTIGATING,
    CLOSED,
}

enum class CaseSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}
