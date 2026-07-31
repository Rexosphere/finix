package org.finix.vault.application.usecase

import org.finix.vault.domain.crypto.Share
import org.finix.vault.domain.crypto.Shamir
import org.springframework.stereotype.Service
import java.security.SecureRandom

/**
 * Account-recovery share set (FR-01): 2-of-3 Shamir using the same GF(256) primitives as the
 * Master Key ceremony. Does not touch the ceremony aggregate — identity-service stores the shares.
 */
@Service
class SplitRecoverySharesUseCase(
    private val random: SecureRandom = SecureRandom(),
) {
    fun execute(secret: ByteArray, n: Int = 3, k: Int = 2): List<Share> {
        require(secret.isNotEmpty()) { "recovery secret must be non-empty" }
        return Shamir.split(secret, n = n, k = k, random = random)
    }

    fun reconstruct(shares: List<Share>): ByteArray = Shamir.reconstruct(shares)
}
