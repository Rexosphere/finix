package org.finix.kernel.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.math.BigDecimal

class CanonicalJsonTest : StringSpec({
    val mapper = ObjectMapper()

    "object keys are emitted in code-unit order regardless of input order" {
        val a = mapper.readTree("""{"b":1,"a":2,"C":3}""")
        CanonicalJson.canonicalize(a) shouldBe """{"C":3,"a":2,"b":1}"""
    }

    "the same logical document canonicalizes identically however it was written" {
        val spaced = mapper.readTree("""{ "z" : [1, 2] , "a" : { "n" : true } }""")
        val tight = mapper.readTree("""{"a":{"n":true},"z":[1,2]}""")
        CanonicalJson.canonicalize(spaced) shouldBe CanonicalJson.canonicalize(tight)
    }

    "array order is preserved because it is semantically meaningful" {
        CanonicalJson.canonicalize(mapper.readTree("""[3,1,2]""")) shouldBe "[3,1,2]"
    }

    "control characters use the short escapes required by RFC 8785" {
        val node = mapper.readTree(""" {"k":"a\tb\nc\"d\\e"} """)
        CanonicalJson.canonicalize(node) shouldBe """{"k":"a\tb\nc\"d\\e"}"""
    }

    "numbers follow ECMAScript formatting, not Java's" {
        CanonicalJson.formatNumber(BigDecimal("0")) shouldBe "0"
        CanonicalJson.formatNumber(BigDecimal("1")) shouldBe "1"
        CanonicalJson.formatNumber(BigDecimal("-42")) shouldBe "-42"
        CanonicalJson.formatNumber(BigDecimal("1.5")) shouldBe "1.5"
        CanonicalJson.formatNumber(BigDecimal("0.001")) shouldBe "0.001"
    }

    "booleans, nulls and nesting all canonicalize" {
        val node = mapper.readTree("""{"t":true,"f":false,"n":null,"o":{"i":[1,{"k":"v"}]}}""")
        CanonicalJson.canonicalize(node) shouldBe
            """{"f":false,"n":null,"o":{"i":[1,{"k":"v"}]},"t":true}"""
    }

    "unicode and low control characters are escaped per the spec" {
        val node = mapper.createObjectNode().put("k", "a\u0001b\u001fc")
        CanonicalJson.canonicalize(node) shouldBe """{"k":"a\u0001b\u001fc"}"""
    }

    "very large and very small magnitudes use ECMAScript exponent form" {
        CanonicalJson.formatNumber(BigDecimal("1e21")) shouldBe "1e+21"
        CanonicalJson.formatNumber(BigDecimal("1e-7")) shouldBe "1e-7"
        CanonicalJson.formatNumber(BigDecimal("0.0000001")) shouldBe "1e-7"
        CanonicalJson.formatNumber(BigDecimal("-1.5e-9")) shouldBe "-1.5e-9"
        CanonicalJson.formatNumber(BigDecimal("123456789012345678901")) shouldBe "123456789012345680000"
    }

    "non-finite numbers are rejected because JSON cannot represent them" {
        shouldThrow<IllegalArgumentException> {
            CanonicalJson.formatNumber(BigDecimal.valueOf(Double.MAX_VALUE).multiply(BigDecimal(10)))
        }
    }

    "canonicalization is deterministic across repeated runs" {
        checkAll(Arb.list(Arb.string(1..8), 1..6)) { keys ->
            val node = mapper.createObjectNode().apply { keys.forEachIndexed { i, k -> put(k, i) } }
            CanonicalJson.canonicalize(node) shouldBe CanonicalJson.canonicalize(node)
        }
    }
})

class MerkleTreeTest : StringSpec({

    fun leaves(n: Int) = (1..n).map { Hashing.sha256Hex("entry-$it".toByteArray()) }

    "an empty window still anchors, with the zero root" {
        MerkleTree.root(emptyList()) shouldBe Hashing.ZERO_DIGEST
    }

    "every leaf in a window has a proof that recomputes the root" {
        checkAll(Arb.int(1..64)) { size ->
            val window = leaves(size)
            val root = MerkleTree.root(window)
            window.indices.forEach { i ->
                val proof = MerkleTree.proof(window, i)
                proof.root shouldBe root
                MerkleTree.verify(proof) shouldBe true
            }
        }
    }

    "an out-of-range leaf index is rejected" {
        shouldThrow<IllegalArgumentException> { MerkleTree.proof(leaves(4), 9) }
    }

    "an odd node is promoted rather than duplicated (CVE-2012-2459 malleability)" {
        // A three-leaf window must not have the same root as [a, b, c, c].
        val three = leaves(3)
        MerkleTree.root(three) shouldNotBe MerkleTree.root(three + three.last())
    }

    "a single-entry window proves inclusion with an empty path" {
        val proof = MerkleTree.proof(leaves(1), 0)
        proof.path shouldHaveSize 0
        MerkleTree.verify(proof) shouldBe true
    }

    "a proof for a different transaction does not verify against the root" {
        val window = leaves(9)
        val proof = MerkleTree.proof(window, 4)
        val forged = proof.copy(leafHash = Hashing.sha256Hex("not-in-the-ledger".toByteArray()))
        MerkleTree.verify(forged) shouldBe false
    }

    "tampering with any sibling in the path invalidates the proof" {
        val window = leaves(16)
        val proof = MerkleTree.proof(window, 7)
        proof.path shouldHaveSize 4
        val tamperedPath = proof.path.toMutableList()
        tamperedPath[2] = tamperedPath[2].copy(siblingHash = Hashing.ZERO_DIGEST)
        MerkleTree.verify(proof.copy(path = tamperedPath)) shouldBe false
    }

    "leaf and internal nodes are domain-separated (RFC 6962)" {
        // Without the 0x00/0x01 prefixes these two would collide, letting an internal node be
        // presented as a leaf.
        val h = Hashing.sha256Hex("x".toByteArray())
        MerkleTree.leafHash(h) shouldNotBe MerkleTree.nodeHash(h, h)
    }

    "changing one entry changes the root" {
        val a = MerkleTree.root(leaves(8))
        val b = MerkleTree.root(leaves(8).toMutableList().also { it[3] = Hashing.ZERO_DIGEST })
        a shouldNotBe b
    }
})

class HashingTest : StringSpec({
    "chaining hashes digest bytes, not their hex printing" {
        val payload = """{"a":1}""".toByteArray()
        val prev = Hashing.sha256Hex("genesis".toByteArray())
        Hashing.chain(prev, payload) shouldBe
            Hashing.sha256Hex(Hashing.unhex(prev), payload)
    }

    "a chain diverges permanently once any link changes" {
        val p = """{"amount":100}""".toByteArray()
        val q = """{"amount":101}""".toByteArray()
        val genesis = Hashing.ZERO_DIGEST
        val chainA = Hashing.chain(Hashing.chain(genesis, p), p)
        val chainB = Hashing.chain(Hashing.chain(genesis, q), p)
        chainA shouldNotBe chainB
    }
})

class PostQuantumTest : StringSpec({

    "ML-KEM-768 encapsulation and decapsulation agree on the shared secret" {
        val kp = PostQuantum.generateKemKeyPair()
        val enc = PostQuantum.encapsulate(kp.public)
        val recovered = PostQuantum.decapsulate(kp.private, enc.ciphertext)
        recovered.encoded.contentEquals(enc.sharedSecret.encoded) shouldBe true
    }

    "a shard sealed to one enclave cannot be opened by another" {
        val enclave = PostQuantum.generateKemKeyPair()
        val impostor = PostQuantum.generateKemKeyPair()
        val enc = PostQuantum.encapsulate(enclave.public)
        val wrong = runCatching { PostQuantum.decapsulate(impostor.private, enc.ciphertext) }
        // ML-KEM is designed to yield an unrelated secret rather than fail loudly.
        val agreed = wrong.getOrNull()?.encoded?.contentEquals(enc.sharedSecret.encoded) ?: false
        agreed shouldBe false
    }

    "ML-DSA-65 signatures verify and are bound to the exact message" {
        val kp = PostQuantum.generateSigningKeyPair()
        val root = Hashing.sha256("anchor-root".toByteArray())
        val sig = PostQuantum.sign(kp.private, root)
        PostQuantum.verify(kp.public, root, sig) shouldBe true
        PostQuantum.verify(kp.public, Hashing.sha256("other-root".toByteArray()), sig) shouldBe false
    }

    "a signature does not verify under an unrelated public key" {
        val signer = PostQuantum.generateSigningKeyPair()
        val other = PostQuantum.generateSigningKeyPair()
        val message = "audit".toByteArray()
        PostQuantum.verify(other.public, message, PostQuantum.sign(signer.private, message)) shouldBe false
    }

    "keys survive the base64 encoding used to persist and publish them" {
        val kem = PostQuantum.generateKemKeyPair()
        val decoded = PqcCodec.decodeKemPublicKey(PqcCodec.encodePublicKey(kem.public))
        decoded.encoded.contentEquals(kem.public.encoded) shouldBe true

        val dsa = PostQuantum.generateSigningKeyPair()
        val message = "verify-me".toByteArray()
        val sig = PostQuantum.sign(
            PqcCodec.decodeSigningPrivateKey(PqcCodec.encodePrivateKey(dsa.private)),
            message,
        )
        PostQuantum.verify(
            PqcCodec.decodeSigningPublicKey(PqcCodec.encodePublicKey(dsa.public)),
            message,
            sig,
        ) shouldBe true
    }
})
