package org.finix.vault.application

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.finix.kernel.crypto.PostQuantum
import org.finix.vault.application.usecase.SplitMasterKeyUseCase
import java.nio.charset.StandardCharsets

class HybridSealTest : StringSpec({

    "seal/open round-trips with hybrid X25519 + ML-KEM-768" {
        val kem = PostQuantum.generateKemKeyPair()
        val x25519 = HybridSeal.generateX25519KeyPair()
        val plaintext = "shard-bytes".toByteArray(StandardCharsets.UTF_8)
        val sealed = HybridSeal.seal(plaintext, kem.public, x25519.public)
        val opened = HybridSeal.open(sealed, kem.private, x25519.private)
        opened.contentEquals(plaintext) shouldBe true
    }

    "raw-key seal wraps network config" {
        val key = ByteArray(32) { it.toByte() }
        val config = SplitMasterKeyUseCase.DEFAULT_NETWORK_CONFIG.toByteArray(StandardCharsets.UTF_8)
        val sealed = HybridSeal.sealWithRawKey(config, key)
        HybridSeal.openWithRawKey(sealed, key).contentEquals(config) shouldBe true
    }

    "pack/unpack share payload preserves index and ordinates" {
        val shamir = byteArrayOf(1, 2, 3, 4)
        val feldman = ByteArray(32) { (it + 1).toByte() }
        val packed = SplitMasterKeyUseCase.packSharePayload(3, shamir, feldman)
        val (x, s, f) = SplitMasterKeyUseCase.unpackSharePayload(packed)
        x shouldBe 3
        s.contentEquals(shamir) shouldBe true
        f.contentEquals(feldman) shouldBe true
    }
})
