package org.finix.vault.domain

/**
 * The five Master Key custodians from blueprint §3.2 / FR-07.
 *
 * Exactly five — one share each in the 3-of-5 ceremony. Ordinal order matches Shamir
 * evaluation points `x = 1..5` when shares are issued in enum declaration order.
 */
enum class CustodianId {
    CENTRAL_BANK,
    GOVT_DR,
    IEEE_VAULT,
    CLOUD_HSM_A,
    CLOUD_HSM_B,
    ;

    companion object {
        val ALL: List<CustodianId> = entries.toList()

        fun parse(raw: String): CustodianId =
            entries.find { it.name.equals(raw, ignoreCase = true) }
                ?: throw org.finix.kernel.domain.DomainException(
                    org.finix.kernel.domain.DomainError.Invalid(
                        detail = "Unknown custodian '$raw'",
                        properties = mapOf("custodianId" to raw),
                    ),
                )
    }
}
