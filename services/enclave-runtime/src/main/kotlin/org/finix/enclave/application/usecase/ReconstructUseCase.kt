package org.finix.enclave.application.usecase

import org.finix.enclave.config.EnclaveKeyMaterial
import org.finix.enclave.domain.ReconstructSession
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.springframework.stereotype.Service
import java.util.Base64

@Service
class ReconstructUseCase(
    private val keys: EnclaveKeyMaterial,
) {
    data class Command(
        val sealedShares: List<SealedShareCommand>,
        val commitmentsB64: List<String>,
        val sealedNetworkConfigB64: String,
    )

    data class SealedShareCommand(
        val x: Int,
        val sealedB64: String,
    )

    fun execute(command: Command): ReconstructSession.ReconstructResult {
        // commitmentsB64 accepted for vault wire compatibility; Feldman verify runs in vault
        // before release. Enclave reconstruct-only trusts the hybrid seal.
        if (command.sealedShares.size < 2) {
            throw DomainException(DomainError.Invalid("need at least 2 sealed shares"))
        }
        return try {
            val sealed = command.sealedShares.map { s ->
                ReconstructSession.SealedShareInput(
                    x = s.x,
                    sealedBlob = b64(s.sealedB64),
                )
            }
            ReconstructSession.run(
                sealedShares = sealed,
                sealedNetworkConfig = b64(command.sealedNetworkConfigB64),
                mlKemPrivate = keys.mlKemPrivate,
                x25519Private = keys.x25519Private,
            )
        } catch (ex: DomainException) {
            throw ex
        } catch (ex: IllegalArgumentException) {
            throw DomainException(DomainError.Invalid(ex.message ?: "invalid reconstruct request"), ex)
        } catch (ex: Exception) {
            throw DomainException(
                DomainError.IntegrityViolation("reconstruct-failed", ex.message ?: "enclave reconstruct failed"),
                ex,
            )
        }
    }

    private fun b64(value: String): ByteArray = Base64.getDecoder().decode(value)
}
