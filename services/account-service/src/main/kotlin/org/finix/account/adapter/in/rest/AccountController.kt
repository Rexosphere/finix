package org.finix.account.adapter.`in`.rest

import jakarta.validation.Valid
import org.finix.account.application.usecase.CommitHoldUseCase
import org.finix.account.application.usecase.CreditAccountUseCase
import org.finix.account.application.usecase.GetAccountUseCase
import org.finix.account.application.usecase.ListAccountsUseCase
import org.finix.account.application.usecase.OpenAccountUseCase
import org.finix.account.application.usecase.ReleaseHoldUseCase
import org.finix.account.application.usecase.ReserveFundsUseCase
import org.finix.account.application.usecase.SeedAccountsUseCase
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
@RequestMapping("/api/v1")
class AccountController(
    private val openAccount: OpenAccountUseCase,
    private val getAccount: GetAccountUseCase,
    private val listAccounts: ListAccountsUseCase,
    private val reserveFunds: ReserveFundsUseCase,
    private val commitHold: CommitHoldUseCase,
    private val releaseHold: ReleaseHoldUseCase,
    private val creditAccount: CreditAccountUseCase,
    private val seedAccounts: SeedAccountsUseCase,
) {

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@Valid @RequestBody body: OpenAccountRequest): AccountResponse =
        openAccount.execute(body.ownerUserId, body.type).toResponse()

    @GetMapping("/accounts/{id}")
    fun get(@PathVariable id: UUID): AccountResponse =
        getAccount.execute(id).toResponse()

    @GetMapping("/accounts")
    fun list(@RequestParam ownerUserId: UUID): List<AccountResponse> =
        listAccounts.execute(ownerUserId).map { it.toResponse() }

    @PostMapping("/accounts/{id}/reserves")
    fun reserve(
        @PathVariable id: UUID,
        @Valid @RequestBody body: ReserveFundsRequest,
    ): AccountResponse =
        reserveFunds.execute(id, body.amount, body.holdId).toResponse()

    @PostMapping("/accounts/{id}/reserves/{holdId}/commit")
    fun commit(
        @PathVariable id: UUID,
        @PathVariable holdId: UUID,
    ): AccountResponse =
        commitHold.execute(id, holdId).toResponse()

    @PostMapping("/accounts/{id}/reserves/{holdId}/release")
    fun release(
        @PathVariable id: UUID,
        @PathVariable holdId: UUID,
    ): AccountResponse =
        releaseHold.execute(id, holdId).toResponse()

    @PostMapping("/accounts/{id}/credits")
    fun credit(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CreditAccountRequest,
    ): AccountResponse =
        creditAccount.execute(id, body.amount, body.reference).toResponse()

    @PostMapping("/admin/seed")
    fun seed(): SeedAccountsResponse =
        SeedAccountsResponse(accounts = seedAccounts.execute().map { it.toResponse() })
}
