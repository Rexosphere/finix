package org.finix.enclave.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.finix.enclave.application.usecase.ReconstructUseCase
import org.finix.enclave.config.EnclaveKeyMaterial
import org.finix.enclave.domain.crypto.HybridSeal
import org.finix.enclave.domain.crypto.SecureBytes
import org.finix.enclave.domain.crypto.ShamirSplit
import org.finix.kernel.crypto.PqcCodec
import org.finix.kernel.crypto.PostQuantum
import org.finix.kernel.domain.DomainException
import java.util.Base64

class ReconstructUseCaseTest : StringSpec({

    fun material(
        kem: java.security.KeyPair,
        x: java.security.KeyPair,
    ): EnclaveKeyMaterial {
        val signing = PostQuantum.generateSigningKeyPair()
        return EnclaveKeyMaterial(
            moduleId = "finix-enclave-1",
            attestationSigningPrivate = signing.private,
            attestationSigningPublic = signing.public,
            attestationPublicKeyB64 = PqcCodec.encodePublicKey(signing.public),
            mlKemPrivate = kem.private,
            mlKemPublicB64 = PqcCodec.encodePublicKey(kem.public),
            x25519Private = x.private,
            x25519PublicB64 = Base64.getEncoder().encodeToString(x.public.encoded),
        )
    }

    "use case reconstructs sealed shares into network config" {
        val kem = PostQuantum.generateKemKeyPair()
        val x = HybridSeal.generateX25519KeyPair()
        val keyMaterial = material(kem, x)

        val masterKey = ByteArray(32) { 9 }
        val config = "unlocked"
        val sealedConfig = HybridSeal.sealWithRawKey(config.toByteArray(), masterKey)
        val shares = ShamirSplit.split(masterKey, 5, 3).take(3)
        val command = ReconstructUseCase.Command(
            sealedShares = shares.map { share ->
                val feldmanPad = ByteArray(HybridSeal.FELDMAN_SCALAR_BYTES)
                val payload = HybridSeal.packSharePayload(share.x, share.y, feldmanPad)
                val sealed = try {
                    HybridSeal.seal(payload, kem.public, x.public)
                } finally {
                    SecureBytes.wipe(payload)
                    SecureBytes.wipe(feldmanPad)
                }
                ReconstructUseCase.SealedShareCommand(
                    x = share.x,
                    sealedB64 = Base64.getEncoder().encodeToString(sealed),
                )
            },
            commitmentsB64 = emptyList(),
            sealedNetworkConfigB64 = Base64.getEncoder().encodeToString(sealedConfig),
        )
        shares.forEach { SecureBytes.wipe(it.y) }
        SecureBytes.wipe(masterKey)

        val result = ReconstructUseCase(keyMaterial).execute(command)
        result.networkConfig shouldBe config
        result.egressLog.last() shouldBe "master-key zeroed"
    }

    "too few shares is invalid" {
        val keyMaterial = material(PostQuantum.generateKemKeyPair(), HybridSeal.generateX25519KeyPair())
        shouldThrow<DomainException> {
            ReconstructUseCase(keyMaterial).execute(
                ReconstructUseCase.Command(
                    sealedShares = emptyList(),
                    commitmentsB64 = emptyList(),
                    sealedNetworkConfigB64 = "YQ==",
                ),
            )
        }
    }

    "corrupt sealed share becomes integrity failure" {
        val kem = PostQuantum.generateKemKeyPair()
        val x = HybridSeal.generateX25519KeyPair()
        val keyMaterial = material(kem, x)
        shouldThrow<DomainException> {
            ReconstructUseCase(keyMaterial).execute(
                ReconstructUseCase.Command(
                    sealedShares = listOf(
                        ReconstructUseCase.SealedShareCommand(1, Base64.getEncoder().encodeToString(ByteArray(64))),
                        ReconstructUseCase.SealedShareCommand(2, Base64.getEncoder().encodeToString(ByteArray(64))),
                    ),
                    commitmentsB64 = emptyList(),
                    sealedNetworkConfigB64 = Base64.getEncoder().encodeToString(ByteArray(32)),
                ),
            )
        }
    }

    "invalid base64 is Invalid" {
        val keyMaterial = material(PostQuantum.generateKemKeyPair(), HybridSeal.generateX25519KeyPair())
        shouldThrow<DomainException> {
            ReconstructUseCase(keyMaterial).execute(
                ReconstructUseCase.Command(
                    sealedShares = listOf(
                        ReconstructUseCase.SealedShareCommand(1, "!!!"),
                        ReconstructUseCase.SealedShareCommand(2, "!!!"),
                    ),
                    commitmentsB64 = emptyList(),
                    sealedNetworkConfigB64 = "!!!",
                ),
            )
        }
    }
})
