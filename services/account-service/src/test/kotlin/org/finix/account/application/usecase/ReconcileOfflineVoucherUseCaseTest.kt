package org.finix.account.application.usecase

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.finix.account.application.ReconcileVoucherCommand
import org.finix.account.application.port.AccountRepository
import org.finix.account.application.port.OfflineDeviceRepository
import org.finix.account.application.port.OfflineEventPublisher
import org.finix.account.application.port.VoucherSignatureVerifier
import org.finix.account.config.OfflineProperties
import org.finix.account.domain.Account
import org.finix.account.domain.AccountType
import org.finix.account.domain.DemoAccounts
import org.finix.account.domain.OfflineDevice
import org.finix.account.domain.OfflineVoucherStatus
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class ReconcileOfflineVoucherUseCaseTest : StringSpec({

    val now = Instant.parse("2026-07-31T12:00:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)

    fun payer(): Account = Account.open(
        id = DemoAccounts.FARMER_ACCOUNT_ID,
        ownerUserId = DemoAccounts.FARMER_USER_ID,
        accountNumber = DemoAccounts.FARMER_ACCOUNT_NUMBER,
        type = AccountType.SAVINGS,
        initialAvailable = "25000.00".lkr(),
    )

    fun payee(): Account = Account.open(
        id = DemoAccounts.SME_ACCOUNT_ID,
        ownerUserId = DemoAccounts.SME_USER_ID,
        accountNumber = DemoAccounts.SME_ACCOUNT_NUMBER,
        type = AccountType.CURRENT,
        initialAvailable = "150000.00".lkr(),
    )

    fun device(seq: Long = 0): OfflineDevice = OfflineDevice(
        deviceId = "dev-farmer-1",
        ownerUserId = DemoAccounts.FARMER_USER_ID,
        accountId = DemoAccounts.FARMER_ACCOUNT_ID,
        publicKeySpki = byteArrayOf(1, 2, 3),
        lastDeviceSeq = seq,
    )

    "settles a valid voucher and moves balances" {
        val devices = mockk<OfflineDeviceRepository>()
        val accounts = mockk<AccountRepository>()
        val verifier = mockk<VoucherSignatureVerifier>()
        val events = mockk<OfflineEventPublisher>()
        val source = payer()
        val dest = payee()
        val offlineDevice = device()

        every { devices.findById("dev-farmer-1") } returns offlineDevice
        every { devices.nonceExists(any(), any()) } returns false
        every { devices.saveNonce(any(), any()) } just runs
        every { devices.save(any()) } answers { firstArg() }
        every { devices.saveVoucher(any()) } answers { firstArg() }
        every { accounts.findById(DemoAccounts.FARMER_ACCOUNT_ID) } returns source
        every { accounts.findById(DemoAccounts.SME_ACCOUNT_ID) } returns dest
        every { accounts.save(any()) } answers { firstArg() }
        every { verifier.verify(any(), any(), any()) } returns true
        every { events.publishSettled(any()) } just runs

        val result = ReconcileOfflineVoucherUseCase(
            devices, accounts, verifier, events, OfflineProperties(), clock,
        ).execute(
            ReconcileVoucherCommand(
                deviceId = "dev-farmer-1",
                payerAccountId = DemoAccounts.FARMER_ACCOUNT_ID,
                payeeAccountId = DemoAccounts.SME_ACCOUNT_ID,
                amount = "100.00".lkr(),
                deviceSeq = 1,
                nonce = "n1",
                validUntil = now.plusSeconds(3600),
                signatureBase64 = "AA==",
            ),
        )

        result.status shouldBe OfflineVoucherStatus.SETTLED
        source.availableBalance shouldBe "24900.00".lkr()
        dest.availableBalance shouldBe "150100.00".lkr()
        offlineDevice.lastDeviceSeq shouldBe 1
        verify(exactly = 1) { events.publishSettled(any()) }
    }

    "quarantines on nonce reuse double-spend" {
        val devices = mockk<OfflineDeviceRepository>()
        val events = mockk<OfflineEventPublisher>()
        val offlineDevice = device(seq = 1)
        every { devices.findById("dev-farmer-1") } returns offlineDevice
        every { devices.nonceExists("dev-farmer-1", "n1") } returns true
        every { devices.save(any()) } answers { firstArg() }
        every { devices.saveVoucher(any()) } answers { firstArg() }
        every { events.publishAnomaly(any(), any(), any(), any()) } just runs

        val verifier = mockk<VoucherSignatureVerifier>()
        every { verifier.verify(any(), any(), any()) } returns true

        shouldThrow<DomainException> {
            ReconcileOfflineVoucherUseCase(
                devices, mockk(), verifier, events, OfflineProperties(), clock,
            ).execute(
                ReconcileVoucherCommand(
                    deviceId = "dev-farmer-1",
                    payerAccountId = DemoAccounts.FARMER_ACCOUNT_ID,
                    payeeAccountId = DemoAccounts.SME_ACCOUNT_ID,
                    amount = "50.00".lkr(),
                    deviceSeq = 2,
                    nonce = "n1",
                    validUntil = now.plusSeconds(3600),
                    signatureBase64 = "AA==",
                ),
            )
        }
        offlineDevice.quarantined shouldBe true
        verify { events.publishAnomaly("dev-farmer-1", "nonce-reuse", 2, "n1") }
    }

    "register is idempotent" {
        val devices = object : OfflineDeviceRepository {
            private val map = ConcurrentHashMap<String, OfflineDevice>()
            override fun findById(deviceId: String) = map[deviceId]
            override fun save(device: OfflineDevice): OfflineDevice {
                map[device.deviceId] = device
                return device
            }
            override fun nonceExists(deviceId: String, nonce: String) = false
            override fun saveNonce(deviceId: String, nonce: String) = Unit
            override fun saveVoucher(voucher: org.finix.account.domain.OfflineVoucher) = voucher
        }
        val accounts = mockk<AccountRepository>()
        every { accounts.findById(DemoAccounts.FARMER_ACCOUNT_ID) } returns payer()
        val useCase = RegisterOfflineDeviceUseCase(devices, accounts)
        val first = useCase.execute(
            "dev-1",
            DemoAccounts.FARMER_USER_ID,
            DemoAccounts.FARMER_ACCOUNT_ID,
            Base64.getEncoder().encodeToString(byteArrayOf(9)),
        )
        val second = useCase.execute(
            "dev-1",
            DemoAccounts.FARMER_USER_ID,
            DemoAccounts.FARMER_ACCOUNT_ID,
            Base64.getEncoder().encodeToString(byteArrayOf(9)),
        )
        first.deviceId shouldBe second.deviceId
        first.deviceId shouldBe "dev-1"
    }
})
