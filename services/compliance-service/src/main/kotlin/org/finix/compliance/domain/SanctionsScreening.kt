package org.finix.compliance.domain

/**
 * Deterministic sanctions/AML party screen for demos.
 *
 * Hit when the party name contains "BLOCKED" (case-insensitive) or the NIC ends with `X`.
 */
object SanctionsScreening {

    fun screen(name: String, nic: String?): ScreeningHit {
        val reasons = mutableListOf<String>()
        if (name.contains("BLOCKED", ignoreCase = true)) {
            reasons += "name_contains_BLOCKED"
        }
        if (nic != null && nic.endsWith("X", ignoreCase = true)) {
            reasons += "nic_ends_with_X"
        }
        return ScreeningHit(hit = reasons.isNotEmpty(), reasons = reasons)
    }
}

data class ScreeningHit(
    val hit: Boolean,
    val reasons: List<String>,
)
