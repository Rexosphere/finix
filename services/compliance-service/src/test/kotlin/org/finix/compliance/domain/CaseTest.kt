package org.finix.compliance.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CaseTest : StringSpec({

    val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    "open creates an OPEN case" {
        val opened = Case.open(
            type = CaseType.AML,
            subjectRef = "tx-1",
            severity = CaseSeverity.MEDIUM,
            notes = "velocity spike",
            clock = clock,
        )
        opened.status shouldBe CaseStatus.OPEN
        opened.subjectRef shouldBe "tx-1"
        opened.openedAt shouldBe Instant.parse("2026-07-31T12:00:00Z")
    }

    "updateStatus moves OPEN to INVESTIGATING then CLOSED" {
        val opened = Case.open(
            type = CaseType.FRAUD,
            subjectRef = "tx-2",
            severity = CaseSeverity.HIGH,
            clock = clock,
        )
        opened.updateStatus(CaseStatus.INVESTIGATING, notes = "analyst assigned", clock = clock)
        opened.status shouldBe CaseStatus.INVESTIGATING
        opened.notes shouldBe "analyst assigned"

        opened.updateStatus(CaseStatus.CLOSED, clock = clock)
        opened.status shouldBe CaseStatus.CLOSED
        opened.closedAt shouldBe Instant.parse("2026-07-31T12:00:00Z")
    }

    "closed case cannot reopen" {
        val opened = Case.open(
            type = CaseType.SAR,
            subjectRef = "party-1",
            severity = CaseSeverity.CRITICAL,
            clock = clock,
        )
        opened.updateStatus(CaseStatus.CLOSED, clock = clock)
        val ex = shouldThrow<DomainException> {
            opened.updateStatus(CaseStatus.OPEN, clock = clock)
        }
        (ex.error is DomainError.Conflict) shouldBe true
    }

    "sanctions screen hits on BLOCKED name or NIC ending X" {
        SanctionsScreening.screen("Alice BLOCKED", "123").hit shouldBe true
        SanctionsScreening.screen("Bob", "NIC00X").hit shouldBe true
        SanctionsScreening.screen("Carol", "NIC001").hit shouldBe false
    }

    "severityFromRiskScore bands" {
        Case.severityFromRiskScore(95) shouldBe CaseSeverity.CRITICAL
        Case.severityFromRiskScore(90) shouldBe CaseSeverity.CRITICAL
        Case.severityFromRiskScore(80) shouldBe CaseSeverity.HIGH
        Case.severityFromRiskScore(75) shouldBe CaseSeverity.HIGH
        Case.severityFromRiskScore(55) shouldBe CaseSeverity.MEDIUM
        Case.severityFromRiskScore(50) shouldBe CaseSeverity.MEDIUM
        Case.severityFromRiskScore(10) shouldBe CaseSeverity.LOW
    }

    "open refuses blank subjectRef" {
        val ex = shouldThrow<DomainException> {
            Case.open(
                type = CaseType.AML,
                subjectRef = "  ",
                severity = CaseSeverity.LOW,
                clock = clock,
            )
        }
        (ex.error is DomainError.Invalid) shouldBe true
    }

    "updateStatus is idempotent for same status without note change" {
        val opened = Case.open(
            type = CaseType.AML,
            subjectRef = "tx-3",
            severity = CaseSeverity.LOW,
            notes = "n1",
            clock = clock,
        )
        opened.updateStatus(CaseStatus.OPEN, notes = null, clock = clock)
        opened.status shouldBe CaseStatus.OPEN
        opened.notes shouldBe "n1"
    }

    "closed to closed is allowed" {
        val opened = Case.open(
            type = CaseType.AML,
            subjectRef = "tx-4",
            severity = CaseSeverity.LOW,
            clock = clock,
        )
        opened.updateStatus(CaseStatus.CLOSED, clock = clock)
        opened.updateStatus(CaseStatus.CLOSED, clock = clock)
        opened.status shouldBe CaseStatus.CLOSED
    }

    "sanctions screen collects both reasons" {
        val hit = SanctionsScreening.screen("BLOCKED Corp", "999X")
        hit.hit shouldBe true
        hit.reasons shouldBe listOf("name_contains_BLOCKED", "nic_ends_with_X")
    }
})
