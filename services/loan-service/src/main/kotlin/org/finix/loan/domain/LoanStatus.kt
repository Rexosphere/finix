package org.finix.loan.domain

enum class LoanStatus {
    PENDING,
    APPROVED,
    DISBURSED,
    REPAYING,
    CLOSED,
    REJECTED,
}

enum class RepaymentStatus {
    DUE,
    PAID,
    OVERDUE,
    WAIVED,
}
