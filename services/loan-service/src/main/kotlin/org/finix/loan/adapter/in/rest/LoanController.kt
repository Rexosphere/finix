package org.finix.loan.adapter.`in`.rest

import jakarta.validation.Valid
import org.finix.loan.application.ApplyLoanCommand
import org.finix.loan.application.DecideLoanCommand
import org.finix.loan.application.usecase.ApplyLoanUseCase
import org.finix.loan.application.usecase.GetLoanUseCase
import org.finix.loan.application.usecase.ListLoansUseCase
import org.finix.loan.application.usecase.ScoreAndDecideLoanUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/loans")
class LoanController(
    private val applyLoan: ApplyLoanUseCase,
    private val getLoan: GetLoanUseCase,
    private val listLoans: ListLoansUseCase,
    private val scoreAndDecide: ScoreAndDecideLoanUseCase,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun apply(@Valid @RequestBody body: ApplyLoanRequest): LoanResponse =
        applyLoan.execute(
            ApplyLoanCommand(
                borrowerUserId = body.borrowerUserId,
                accountId = body.accountId,
                principal = body.principal,
                termMonths = body.termMonths,
            ),
        ).toResponse()

    @GetMapping
    fun list(@RequestParam(required = false) borrowerUserId: UUID?): List<LoanResponse> =
        listLoans.execute(borrowerUserId).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): LoanResponse =
        getLoan.execute(id).toResponse()

    @PostMapping("/{id}/decide")
    fun decide(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: DecideLoanRequest?,
    ): LoanResponse =
        scoreAndDecide.execute(
            DecideLoanCommand(loanId = id, riskHint = body?.riskHint),
        ).toResponse()
}
