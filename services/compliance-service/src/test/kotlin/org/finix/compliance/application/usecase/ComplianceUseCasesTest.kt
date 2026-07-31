package org.finix.compliance.application.usecase

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.finix.compliance.application.OpenCaseCommand
import org.finix.compliance.application.ScreenPartyCommand
import org.finix.compliance.application.UpdateCaseStatusCommand
import org.finix.compliance.application.port.CaseRepository
import org.finix.compliance.domain.Case
import org.finix.compliance.domain.CaseSeverity
import org.finix.compliance.domain.CaseStatus
import org.finix.compliance.domain.CaseType
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ComplianceUseCasesTest : StringSpec({

    val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    "OpenCaseUseCase persists an OPEN case" {
        val repo = mockk<CaseRepository>()
        val saved = slot<Case>()
        every { repo.save(capture(saved)) } answers { saved.captured }

        val result = OpenCaseUseCase(repo, clock).execute(
            OpenCaseCommand(
                type = CaseType.AML,
                subjectRef = "acct-1",
                severity = CaseSeverity.MEDIUM,
                notes = "rule hit",
            ),
        )

        result.status shouldBe CaseStatus.OPEN
        result.type shouldBe CaseType.AML
        verify(exactly = 1) { repo.save(any()) }
    }

    "UpdateCaseStatusUseCase closes a case" {
        val repo = mockk<CaseRepository>()
        val existing = Case.open(
            type = CaseType.FRAUD,
            subjectRef = "tx-9",
            severity = CaseSeverity.HIGH,
            clock = clock,
        )
        every { repo.findById(existing.id) } returns existing
        every { repo.save(any()) } answers { firstArg() }

        val result = UpdateCaseStatusUseCase(repo, clock).execute(
            UpdateCaseStatusCommand(caseId = existing.id, status = CaseStatus.CLOSED),
        )
        result.status shouldBe CaseStatus.CLOSED
    }

    "ScreenPartyUseCase opens a SANCTIONS case on hit" {
        val repo = mockk<CaseRepository>()
        val saved = slot<Case>()
        every { repo.save(capture(saved)) } answers { saved.captured }

        val result = ScreenPartyUseCase(repo, clock).execute(
            ScreenPartyCommand(name = "Mr BLOCKED", nic = "123"),
        )

        result.hit shouldBe true
        result.reasons shouldBe listOf("name_contains_BLOCKED")
        result.caseId.shouldNotBeNull()
        saved.captured.type shouldBe CaseType.SANCTIONS
        saved.captured.severity shouldBe CaseSeverity.HIGH
    }

    "ScreenPartyUseCase returns clean result without persisting" {
        val repo = mockk<CaseRepository>(relaxed = true)
        val result = ScreenPartyUseCase(repo, clock).execute(
            ScreenPartyCommand(name = "Clean Party", nic = "123456789V"),
        )
        result.hit shouldBe false
        result.caseId.shouldBeNull()
        verify(exactly = 0) { repo.save(any()) }
    }

    "ListCasesUseCase returns repository contents" {
        val repo = mockk<CaseRepository>()
        val opened = Case.open(
            type = CaseType.TRAVEL_RULE,
            subjectRef = "xfer-1",
            severity = CaseSeverity.LOW,
            clock = clock,
        )
        every { repo.findAll() } returns listOf(opened)
        ListCasesUseCase(repo).execute().size shouldBe 1
    }

    "UpdateCaseStatusUseCase raises NotFound when missing" {
        val repo = mockk<CaseRepository>()
        every { repo.findById(any()) } returns null
        val ex = shouldThrow<DomainException> {
            UpdateCaseStatusUseCase(repo, clock).execute(
                UpdateCaseStatusCommand(caseId = UUID.randomUUID(), status = CaseStatus.CLOSED),
            )
        }
        (ex.error is DomainError.NotFound) shouldBe true
    }

    "UpdateCaseStatusUseCase moves to INVESTIGATING" {
        val repo = mockk<CaseRepository>()
        val existing = Case.open(
            type = CaseType.AML,
            subjectRef = "tx-10",
            severity = CaseSeverity.MEDIUM,
            clock = clock,
        )
        every { repo.findById(existing.id) } returns existing
        every { repo.save(any()) } answers { firstArg() }

        val result = UpdateCaseStatusUseCase(repo, clock).execute(
            UpdateCaseStatusCommand(
                caseId = existing.id,
                status = CaseStatus.INVESTIGATING,
                notes = "looking into it",
            ),
        )
        result.status shouldBe CaseStatus.INVESTIGATING
        result.notes shouldBe "looking into it"
    }

    "ScreenPartyUseCase hits on NIC ending with X" {
        val repo = mockk<CaseRepository>()
        every { repo.save(any()) } answers { firstArg() }

        val result = ScreenPartyUseCase(repo, clock).execute(
            ScreenPartyCommand(name = "Clean", nic = "ABCX", subjectRef = "party-nic"),
        )
        result.hit shouldBe true
        result.reasons shouldBe listOf("nic_ends_with_X")
        result.caseId.shouldNotBeNull()
    }

    "ScreenPartyUseCase refuses blank name" {
        val repo = mockk<CaseRepository>(relaxed = true)
        val ex = shouldThrow<DomainException> {
            ScreenPartyUseCase(repo, clock).execute(ScreenPartyCommand(name = " "))
        }
        (ex.error is DomainError.Invalid) shouldBe true
    }
})
