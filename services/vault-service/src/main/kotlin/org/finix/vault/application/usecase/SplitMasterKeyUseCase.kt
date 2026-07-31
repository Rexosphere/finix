package org.finix.vault.application.usecase

import org.finix.kernel.crypto.PostQuantum
import org.finix.vault.application.HybridSeal
import org.finix.vault.application.port.CeremonyRepository
import org.finix.vault.application.port.EnclaveKeyPort
import org.finix.vault.domain.Ceremony
import org.finix.vault.domain.CustodianId
import org.finix.vault.domain.SealedShard
import org.finix.vault.domain.crypto.FeldmanVss
import org.finix.vault.domain.crypto.Shamir
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Generate a fresh 32-byte Master Key, Shamir-split 3-of-5, attach Feldman commitments, hybrid-seal
 * each share to the enclave, persist, then zero the plaintext key.
 *
 * The network-config blob is AES-GCM-sealed under the Master Key so the enclave can decrypt it
 * after reconstruction without ever returning the key to vault-service.
 */
@Service
class SplitMasterKeyUseCase(
    private val ceremonies: CeremonyRepository,
    private val enclaveKeys: EnclaveKeyPort,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    @Transactional
    fun execute(
        networkConfigPlaintext: String = DEFAULT_NETWORK_CONFIG,
        ceremonyId: UUID = UUID.randomUUID(),
    ): Ceremony {
        val masterKey = ByteArray(MASTER_KEY_BYTES)
        random.nextBytes(masterKey)
        try {
            val shamirShares = Shamir.split(
                masterKey,
                n = CustodianId.ALL.size,
                k = Ceremony.DEFAULT_THRESHOLD,
                random = random,
            )
            val (feldmanShares, commitments) = FeldmanVss.split(
                masterKey,
                n = CustodianId.ALL.size,
                k = Ceremony.DEFAULT_THRESHOLD,
                random = random,
            )
            val sealedConfig = HybridSeal.sealWithRawKey(
                networkConfigPlaintext.toByteArray(StandardCharsets.UTF_8),
                masterKey,
            )
            val now = Instant.now(clock)
            val ceremony = Ceremony.create(
                id = ceremonyId,
                commitments = commitments,
                sealedNetworkConfig = sealedConfig,
                at = now,
            )
            ceremonies.save(ceremony)

            val recipientKem = KeyFactory.getInstance(PostQuantum.KEM_ALGORITHM, "BC")
                .generatePublic(X509EncodedKeySpec(enclaveKeys.kemPublicKeyEncoded()))
            val recipientX25519 = HybridSeal.decodeX25519PublicKey(enclaveKeys.x25519PublicKeyEncoded())

            val shards = CustodianId.ALL.mapIndexed { index, custodian ->
                val shamir = shamirShares[index]
                val feldman = feldmanShares[index]
                require(shamir.x == feldman.x) { "Shamir/Feldman x mismatch" }
                val payload = packSharePayload(shamir.x, shamir.y, feldman.y)
                try {
                    val ciphertext = HybridSeal.seal(payload, recipientKem, recipientX25519)
                    SealedShard(
                        id = UUID.randomUUID(),
                        ceremonyId = ceremony.id,
                        custodianId = custodian,
                        shareIndex = shamir.x,
                        ciphertext = ciphertext,
                        createdAt = now,
                    )
                } finally {
                    payload.fill(0)
                    shamir.y.fill(0)
                    feldman.y.fill(0)
                }
            }
            ceremonies.replaceShards(ceremony.id, shards)
            return ceremony
        } finally {
            masterKey.fill(0)
        }
    }

    companion object {
        const val MASTER_KEY_BYTES: Int = 32
        const val DEFAULT_NETWORK_CONFIG: String =
            """{"network":"finix-core","region":"colombo","status":"unlocked","mesh":"mTLS"}"""

        /** Pack share index + Shamir ordinate + Feldman scalar for hybrid sealing. */
        fun packSharePayload(shareIndex: Int, shamirY: ByteArray, feldmanY: ByteArray): ByteArray {
            require(shareIndex in 1..255) { "shareIndex out of range" }
            require(feldmanY.size == FeldmanVss.SCALAR_BYTES) {
                "Feldman ordinate must be ${FeldmanVss.SCALAR_BYTES} bytes"
            }
            return ByteBuffer.allocate(1 + 2 + shamirY.size + feldmanY.size)
                .put(shareIndex.toByte())
                .putShort(shamirY.size.toShort())
                .put(shamirY)
                .put(feldmanY)
                .array()
        }

        fun unpackSharePayload(payload: ByteArray): Triple<Int, ByteArray, ByteArray> {
            val buf = ByteBuffer.wrap(payload)
            val shareIndex = buf.get().toInt() and 0xff
            val shamirLen = buf.short.toInt() and 0xffff
            val shamirY = ByteArray(shamirLen)
            buf.get(shamirY)
            val feldmanY = ByteArray(buf.remaining())
            buf.get(feldmanY)
            return Triple(shareIndex, shamirY, feldmanY)
        }
    }
}
